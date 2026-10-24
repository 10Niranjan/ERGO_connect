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
import com.ergo.connect.common.security.ModuleSwitch;
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
	private static final Set<Role> FULL_VIEWERS = ModuleAccess.viewersExcept(Module.NPD, CompanyType.ERGO, ERGO_KAM);

	private static final Set<Role> CUSTOMER_VIEWERS = ModuleAccess.viewers(Module.NPD, CompanyType.CUSTOMER);

	private static final Set<Role> SUPPLIER_VIEWERS = ModuleAccess.viewers(Module.NPD, CompanyType.SUPPLIER);

	private final NpdProjectRepository projects;

	private final NpdApprovalRepository approvals;

	private final NpdService npdService;

	private final SamplePoService samplePoService;

	private final ProductionPoService productionPoService;

	private final ProductionPoRepository productionPos;

	private final Directory directory;

	NpdApiService(NpdProjectRepository projects, NpdApprovalRepository approvals, NpdService npdService,
			SamplePoService samplePoService, ProductionPoService productionPoService, ProductionPoRepository productionPos,
			Directory directory) {
		this.productionPoService = productionPoService;
		this.productionPos = productionPos;
		this.projects = projects;
		this.approvals = approvals;
		this.npdService = npdService;
		this.samplePoService = samplePoService;
		this.directory = directory;
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.List<com.ergo.connect.common.event.OverdueItem> overdueItems(java.time.LocalDate today) {
		// Reminders ask someone to act: not while NPD is read-only, and never from a hidden module. The scan is
		// stateless, so it simply resumes when NPD accepts changes again.
		if (!ModuleSwitch.writableNow(Module.NPD)) {
			return java.util.List.of();
		}
		return npdService.overdue(today);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean writableForRecord(long npdProjectId) {
		if (!ModuleSwitch.readable(Module.NPD)) {
			return false;
		}
		// an OFP project belongs to NPD and OFP: both must accept changes (a project that is not there changes nothing)
		return projects.findById(npdProjectId).map(p -> NpdRights.of(p).writable()).orElse(ModuleSwitch.writable(Module.NPD));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<com.ergo.connect.common.record.RecordParties> partiesVisibleToCaller(long npdProjectId) {
		if (!ModuleSwitch.readable(Module.NPD) || !npdService.inScope(CurrentUser.get(), npdProjectId)) {
			return Optional.empty();
		}
		return projects.findById(npdProjectId).map(p -> new com.ergo.connect.common.record.RecordParties(p.customerCompanyId, p.supplierCompanyId));
	}

	@Override
	public HandoverProject handover(long npdProjectId) {
		// Marking the project handed over is an NPD change made on OFP's behalf: NPD must accept changes (and OFP,
		// which creates the order in this same transaction), or neither side is touched.
		ModuleSwitch.requireWritable(Module.NPD, Module.OFP);
		CurrentUser me = CurrentUser.get();
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.notFound("NPD project");
		}
		NpdProject project = projects.findById(npdProjectId).orElseThrow(() -> ApiException.notFound("NPD project"));
		// Security review M1 (ARCHITECTURE.md §8 2026-09-29): a project must actually have reached the stage that
		// gates production handover before OFP can create an order from it, not just "any ERGO-visible project".
		// flow doc step 11: production starts from the customer's production order, so the order is created from the OFP project
		if (project.kind != ProjectKind.PRODUCTION) {
			throw ApiException.conflict("An order is created from an OFP project: the customer releases a production order first");
		}
		if (project.stage != NpdStage.APPROVED_FOR_PRODUCTION_HANDOVER) {
			throw ApiException.conflict("This NPD project is not yet approved for production handover");
		}
		if (project.handoverReadyFlag) {
			throw ApiException.conflict("This NPD project has already been handed over to OFP");
		}
		project.handoverReadyFlag = true;
		ProductionPo po = productionPos.findById(project.productionPoId).orElseThrow();
		return new HandoverProject(project.id, project.customerCompanyId, project.supplierCompanyId,
				project.sourceRfqId, project.partNumber, project.revision, po.customerPoNumber, po.quantity, po.requiredDate);
	}

	@Override
	public Optional<ProjectRef> projectRef(long npdProjectId) {
		// A quality record can only be linked to a project while NPD is not hidden. The caller's own NPD level is not
		// asked: the role grid is unchanged, and Quality's own rules (who creates, company ownership) govern the link.
		if (!ModuleSwitch.readable(Module.NPD)) {
			return Optional.empty();
		}
		// Until the plan is released to the supplier (flow doc step 7) nothing may be linked to the project: a quality
		// record carries the project id and part number, and the supplier reads its own company's records.
		return projects.findById(npdProjectId)
				.filter(p -> p.planStatus != PlanStatus.AWAITING_PLAN)
			.filter(p -> p.kind != ProjectKind.PRODUCTION || ModuleSwitch.readable(Module.OFP))
			.map(p -> new ProjectRef(p.id, p.customerCompanyId, p.supplierCompanyId));
	}

	@Override
	@Transactional(readOnly = true)
	public List<NpdDashboardItem> dashboardItems() {
		if (!ModuleSwitch.readable(Module.NPD)) {
			return List.of();
		}
		CurrentUser me = CurrentUser.get();
		List<NpdProject> candidates;
		if (me.companyType() == CompanyType.CUSTOMER) {
			candidates = projects.findByCustomerCompanyIdInOrderByIdDesc(Set.of(me.companyId()));
		}
		else if (me.companyType() == CompanyType.SUPPLIER) {
			candidates = projects.findBySupplierCompanyIdAndPlanStatusNotOrderByIdDesc(me.companyId(), PlanStatus.AWAITING_PLAN);
		}
		else if (me.role() == ERGO_KAM) {
			Set<Long> accounts = directory.accountsOf(me.userId());
			candidates = accounts.isEmpty() ? List.of() : projects.findByCustomerCompanyIdInOrderByIdDesc(accounts);
		}
		else {
			candidates = projects.findAllByOrderByIdDesc();
		}
		// the record scope decides per project and its kind (an OFP project also needs OFP visible and the role on it)
		List<NpdProject> visible = candidates.stream().filter(p -> npdService.inScope(me, p)).toList();
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
	public int samplePosAwaitingCount() {
		return ModuleSwitch.readable(Module.NPD) ? samplePoService.awaitingCount() : 0;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<com.ergo.connect.common.record.RecordParties> samplePoPartiesVisibleToCaller(long samplePoId) {
		return samplePoService.customerIfVisible(samplePoId).map(c -> new com.ergo.connect.common.record.RecordParties(c, null));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<com.ergo.connect.common.record.RecordParties> productionPoPartiesVisibleToCaller(long productionPoId) {
		return productionPoService.customerIfVisible(productionPoId).map(c -> new com.ergo.connect.common.record.RecordParties(c, null));
	}

	@Override
	@Transactional(readOnly = true)
	public int pendingApprovalsCount() {
		CurrentUser me = CurrentUser.get();
		if (!ModuleSwitch.readable(Module.NPD) || me.companyType() != CompanyType.ERGO
				|| (!ModuleAccess.canApprove(me.role(), Module.NPD) && me.role() != ERGO_ADMIN)) {
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
