package com.ergo.connect.npd.domain;

import static com.ergo.connect.common.security.Role.CUSTOMER_NPD;
import static com.ergo.connect.common.security.Role.CUSTOMER_QUALITY;
import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;
import static com.ergo.connect.common.security.Role.ERGO_NPD;
import static com.ergo.connect.common.security.Role.ERGO_RFQ;
import static com.ergo.connect.common.security.Role.SUPPLIER_KAM;
import static com.ergo.connect.common.security.Role.SUPPLIER_NPD;
import static com.ergo.connect.common.security.Role.SUPPLIER_OFP;
import static com.ergo.connect.common.security.Role.SUPPLIER_QUALITY;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.record.Awaiting;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.ModuleAccess;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.npd.api.NpdApi;

/**
 * Implements {@link NpdApi} for OFP's "Handover to OFP" action. Deliberately narrower than {@link NpdService}'s own
 * record scope (which restricts most ERGO roles' NPD visibility): any ERGO caller may read a specific project's own
 * handover data by id (the OFP controller's own {@code @PreAuthorize} already confines who actually reaches this),
 * the same "narrow, purpose-built cross-module read" shape as {@code QuotationApiService.selectedQuoteFor}.
 */
@Service
@Transactional
class NpdApiService implements NpdApi {

	/** The role sheet (ModuleAccess), NPD column: every ERGO role with access except the account-scoped KAM. */
	private static final Set<Role> FULL_VIEWERS = ModuleAccess.viewers(Module.NPD, CompanyType.ERGO).stream()
		.filter(r -> r != ERGO_KAM)
		.collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));

	private static final Set<Role> CUSTOMER_VIEWERS = ModuleAccess.viewers(Module.NPD, CompanyType.CUSTOMER);

	private static final Set<Role> SUPPLIER_VIEWERS = ModuleAccess.viewers(Module.NPD, CompanyType.SUPPLIER);

	private final NpdProjectRepository projects;

	private final NpdApprovalRepository approvals;

	private final NpdService npdService;

	private final Directory directory;

	NpdApiService(NpdProjectRepository projects, NpdApprovalRepository approvals, NpdService npdService,
			Directory directory) {
		this.projects = projects;
		this.approvals = approvals;
		this.npdService = npdService;
		this.directory = directory;
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.List<com.ergo.connect.common.event.OverdueItem> overdueItems(java.time.LocalDate today) {
		return npdService.overdue(today);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<com.ergo.connect.common.record.RecordParties> partiesVisibleToCaller(long npdProjectId) {
		if (!npdService.inScope(CurrentUser.get(), npdProjectId)) {
			return Optional.empty();
		}
		return projects.findById(npdProjectId).map(p -> new com.ergo.connect.common.record.RecordParties(p.customerCompanyId, p.supplierCompanyId));
	}

	@Override
	public HandoverProject handover(long npdProjectId) {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.notFound("NPD project");
		}
		NpdProject project = projects.findById(npdProjectId).orElseThrow(() -> ApiException.notFound("NPD project"));
		// Security review M1 (ARCHITECTURE.md §8 2026-09-29): a project must actually have reached the stage that
		// gates production handover before OFP can create an order from it, not just "any ERGO-visible project".
		if (project.stage != NpdStage.APPROVED_FOR_PRODUCTION_HANDOVER) {
			throw ApiException.conflict("This NPD project is not yet approved for production handover");
		}
		if (project.handoverReadyFlag) {
			throw ApiException.conflict("This NPD project has already been handed over to OFP");
		}
		project.handoverReadyFlag = true;
		return new HandoverProject(project.id, project.customerCompanyId, project.supplierCompanyId,
				project.sourceRfqId, project.partNumber, project.revision);
	}

	@Override
	public Optional<ProjectRef> projectRef(long npdProjectId) {
		return projects.findById(npdProjectId)
			.map(p -> new ProjectRef(p.id, p.customerCompanyId, p.supplierCompanyId));
	}

	@Override
	@Transactional(readOnly = true)
	public List<NpdDashboardItem> dashboardItems() {
		CurrentUser me = CurrentUser.get();
		List<NpdProject> visible;
		if (FULL_VIEWERS.contains(me.role())) {
			visible = projects.findAllByOrderByIdDesc();
		}
		else if (me.role() == ERGO_KAM) {
			Set<Long> accounts = directory.accountsOf(me.userId());
			visible = accounts.isEmpty() ? List.of() : projects.findByCustomerCompanyIdInOrderByIdDesc(accounts);
		}
		else if (CUSTOMER_VIEWERS.contains(me.role())) {
			visible = projects.findByCustomerCompanyIdInOrderByIdDesc(Set.of(me.companyId()));
		}
		else if (SUPPLIER_VIEWERS.contains(me.role())) {
			visible = projects.findBySupplierCompanyIdOrderByIdDesc(me.companyId());
		}
		else {
			visible = List.of();
		}
		boolean ergo = me.companyType() == CompanyType.ERGO;
		List<NpdProject> open = visible.stream().filter(p -> p.stage != NpdStage.CLOSED).toList();
		Map<Long, CompanyRef> customers = ergo ? directory.companies(ids(open, p -> p.customerCompanyId)) : Map.of();
		Map<Long, CompanyRef> suppliers = ergo ? directory.companies(ids(open, p -> p.supplierCompanyId)) : Map.of();
		return open.stream()
			.map(p -> new NpdDashboardItem(p.id, p.npdNumber, ergo ? name(customers, p.customerCompanyId) : null,
					ergo ? name(suppliers, p.supplierCompanyId) : null, p.stage.name(), ergo ? awaiting(p.stage) : null,
					p.targetSampleDate, delayDays(p),
					ergo && p.riskLevel != null ? p.riskLevel.name() : null))
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public int unconvertedApprovedRfqCount() {
		return npdService.unconvertedApprovedRfqCount();
	}

	@Override
	@Transactional(readOnly = true)
	public int pendingApprovalsCount() {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() != CompanyType.ERGO || (!ModuleAccess.canApprove(me.role(), Module.NPD) && me.role() != ERGO_ADMIN)) {
			return 0; // Management-only widget (ARCHITECTURE.md §8, 2026-10-05): not shown on the KAM dashboard
		}
		return (int) approvals.countByApprovalStatus(ApprovalStatus.PENDING);
	}

	/** This module's own judgment call on whose turn it is to act (ARCHITECTURE.md §8, 2026-10-05 Phase 7c) - the
	 * SRS does not define it. A real pending {@code NpdApproval} would be a more precise signal, but that needs a
	 * per-project join this dashboard read does not otherwise require; the stage is a reasonable proxy. */
	private static Awaiting awaiting(NpdStage stage) {
		return switch (stage) {
			case CUSTOMER_FEEDBACK -> Awaiting.CUSTOMER;
			case CLOSED -> Awaiting.NONE;
			default -> Awaiting.ERGO;
		};
	}

	/** Same formula as {@code NpdService}'s own private helper - duplicated rather than exposed, since it is a
	 * one-line computation over fields this class already reads. */
	private static Long delayDays(NpdProject p) {
		if (p.targetSampleDate == null) {
			return null;
		}
		LocalDate reference = p.actualSampleSubmissionDate != null ? p.actualSampleSubmissionDate : LocalDate.now();
		long days = ChronoUnit.DAYS.between(p.targetSampleDate, reference);
		return days > 0 ? days : 0L;
	}

	private static Set<Long> ids(List<NpdProject> items, Function<NpdProject, Long> field) {
		return items.stream().map(field).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
	}

	private static String name(Map<Long, CompanyRef> names, Long id) {
		CompanyRef ref = id == null ? null : names.get(id);
		return ref == null ? null : ref.name();
	}

}
