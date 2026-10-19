package com.ergo.connect.quality.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.record.Awaiting;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.ModuleAccess;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.quality.api.QualityApi;

/**
 * Implements {@link QualityApi} for OFP's dispatch gate and NPD's Final Sample Readiness stage gate (Phase 5d), and
 * for the Phase 6a documents module's CAPA-evidence scope check: a narrow, purpose-built cross-module read, the same
 * shape as {@code OfpApiService}/{@code NpdApiService}. Read is unrestricted by caller type for the gate methods,
 * same as those two - the consuming module's own controller-level role check already confines who actually reaches
 * them. {@code capaActionVisibleToCaller} duplicates {@code NcrService#visible}'s record-scope switch with its own
 * repository/{@code Directory} access, deliberately not a dependency on {@code NcrService} itself: {@code
 * OfpApiService} (Phase 6a) now depends on {@code OrderService}, which already depends on {@code QualityApi}, and
 * {@code NcrService} already depends on {@code OfpApi} - injecting {@code NcrService} here would close that into a
 * circular bean dependency (OfpApiService -&gt; OrderService -&gt; QualityApi -&gt; NcrService -&gt; OfpApi). Keeping
 * this service's only dependencies its own repositories and {@code Directory} (never another module's api facade)
 * preserves its original Phase 5d design and keeps it safe for any other module to depend on. If this duplicated
 * switch and {@code NcrService#visible} ever drift, that is the cost of breaking the cycle this way.
 */
@Service
@Transactional(readOnly = true)
class QualityApiService implements QualityApi {

	private final InspectionRepository inspections;

	private final AuditLogService audit;

	private final CapaActionRepository capaActions;

	private final NcrRepository ncrs;

	private final Directory directory;

	private final DeviationRepository deviations;

	QualityApiService(InspectionRepository inspections, AuditLogService audit, CapaActionRepository capaActions,
			NcrRepository ncrs, Directory directory, DeviationRepository deviations) {
		this.inspections = inspections;
		this.audit = audit;
		this.capaActions = capaActions;
		this.ncrs = ncrs;
		this.directory = directory;
		this.deviations = deviations;
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.List<com.ergo.connect.common.event.OverdueItem> overdueItems(java.time.LocalDate today) {
		java.util.List<com.ergo.connect.common.event.OverdueItem> items = new java.util.ArrayList<>();
		for (Ncr n : ncrs.findByTargetClosureDateBeforeAndCurrentStatusNot(today, NcrStatus.CLOSED)) {
			items.add(new com.ergo.connect.common.event.OverdueItem(com.ergo.connect.common.event.NotificationKind.OVERDUE_NCR, "ncr", n.id, n.ncrNumber,
					"Target closure date was " + n.targetClosureDate, ncrSide(n, null), "ncr:" + n.id));
		}
		for (CapaAction a : capaActions.findByTargetDateBeforeAndStatusNot(today, CapaActionStatus.CLOSED)) {
			Ncr n = a.ncrId == null ? null : ncrs.findById(a.ncrId).orElse(null);
			if (n != null && n.currentStatus != NcrStatus.CLOSED) {
				items.add(new com.ergo.connect.common.event.OverdueItem(com.ergo.connect.common.event.NotificationKind.OVERDUE_CAPA, "ncr", n.id,
						n.ncrNumber, "CAPA action was due " + a.targetDate, ncrSide(n, a.ownerUserId), "capa:" + a.id));
			}
		}
		return items;
	}

	/** ERGO Quality, the NCR's supplier and the named owner (NCR or CAPA action). The customer is not reminded. While
	 * closure awaits approval only ERGO can act, so only ERGO is reminded (security review 6e M2). */
	private static com.ergo.connect.common.event.Recipients ncrSide(Ncr n, Long actionOwner) {
		if (n.currentStatus == NcrStatus.PENDING_CLOSURE_APPROVAL) {
			return QualityNotices.ergo();
		}
		com.ergo.connect.common.event.Recipients to = QualityNotices.ergo().and(QualityNotices.supplier(n.supplierCompanyId));
		for (Long owner : new Long[] { n.ownerUserId, actionOwner }) {
			if (owner != null) {
				to = to.and(com.ergo.connect.common.event.Recipients.users(java.util.Set.of(owner)));
			}
		}
		return to;
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Optional<com.ergo.connect.common.record.RecordParties> inspectionPartiesVisibleToCaller(long inspectionId) {
		return inspections.findById(inspectionId)
			.flatMap(i -> parties(i.linkedModule, i.customerCompanyId, i.supplierCompanyId, i.customerVisible, true));
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Optional<com.ergo.connect.common.record.RecordParties> ncrPartiesVisibleToCaller(long ncrId) {
		return ncrs.findById(ncrId)
			.flatMap(n -> parties(n.linkedModule, n.customerCompanyId, n.supplierCompanyId, n.customerVisible, true));
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Optional<com.ergo.connect.common.record.RecordParties> deviationPartiesVisibleToCaller(long deviationId) {
		return deviations.findById(deviationId)
			.flatMap(d -> parties(d.linkedModule, d.customerCompanyId, d.supplierCompanyId, d.customerVisible,
					d.supplierVisible));
	}

	/** Duplicates the record-scope switch every quality service has (see this class's own note on why it cannot
	 * depend on those services): {@code customerVisible} / {@code supplierVisible} gate each external side. */
	private java.util.Optional<com.ergo.connect.common.record.RecordParties> parties(LinkedModule linkedModule, Long customerCompanyId,
			Long supplierCompanyId, boolean customerVisible, boolean supplierVisible) {
		CurrentUser me = CurrentUser.get();
		boolean inScope = switch (QualityScope.of(me.role())) {
			case FULL -> true;
			case KAM -> customerCompanyId != null && directory.accountsOf(me.userId()).contains(customerCompanyId);
			case NPD_LINKED -> linkedModule == LinkedModule.NPD;
			case OFP_LINKED -> linkedModule == LinkedModule.OFP;
			case SUPPLIER ->
					supplierVisible && supplierCompanyId != null && me.companyId() == supplierCompanyId;
			case CUSTOMER ->
					customerVisible && customerCompanyId != null && me.companyId() == customerCompanyId;
			default -> false;
		};
		return inScope ? java.util.Optional.of(new com.ergo.connect.common.record.RecordParties(customerCompanyId, supplierCompanyId))
				: java.util.Optional.empty();
	}

	@Override
	public boolean ofpDispatchGateMet(long orderId) {
		return gateMet(LinkedModule.OFP, orderId);
	}

	@Override
	public boolean npdFinalSampleGateMet(long npdProjectId) {
		return gateMet(LinkedModule.NPD, npdProjectId);
	}

	/** The most recently raised inspection linked to this (module, record) decides the gate (client decision,
	 * 2026-10-01, ARCHITECTURE.md §8): a re-inspection that passes clears an earlier REJECTED one on the same record
	 * without reopening and overwriting it. At least one inspection must exist - an unchecked record has not
	 * "passed" (same rule the stub this replaces already used). {@code findByLinkedModuleAndLinkedRecordIdOrderById}
	 * orders by id ascending, so the last element is the most recently raised. */
	private boolean gateMet(LinkedModule module, long recordId) {
		List<Inspection> linked = inspections.findByLinkedModuleAndLinkedRecordIdOrderById(module, recordId);
		return !linked.isEmpty() && resolvesToPassOrDeviation(linked.get(linked.size() - 1));
	}

	private boolean resolvesToPassOrDeviation(Inspection i) {
		ResultStatus effective = i.resultStatus == ResultStatus.CLOSED ? lastDispositionBeforeClose(i.id)
				: i.resultStatus;
		return effective == ResultStatus.PASSED || effective == ResultStatus.RELEASED_WITH_DEVIATION;
	}

	/** Same recovery {@code OrderService.statusBeforeHold} uses for ON_HOLD: {@code Inspection.resultStatus}
	 * collapses PASSED/REJECTED/RELEASED_WITH_DEVIATION into CLOSED with no disposition retained on the entity
	 * itself, so the last pre-close value is read back from the automatic field-audit trail instead - the most
	 * recent CLOSE transition, in case the inspection was ever reopened and closed again. Null or unparseable
	 * audit data (no CLOSE transition on record, which should not happen for a genuinely CLOSED inspection) resolves
	 * to "gate not met" rather than throwing: this is a read-only gate check, not a user-facing mutation, and must
	 * fail closed rather than 500 the caller's OFP/NPD request. */
	private ResultStatus lastDispositionBeforeClose(long inspectionId) {
		try {
			return audit.history("quality", "Inspection", inspectionId).stream()
				.filter(e -> "resultStatus".equals(e.field()) && "CLOSED".equals(e.newValue()))
				.reduce((first, last) -> last)
				.map(e -> ResultStatus.valueOf(e.oldValue()))
				.orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Security review H7 fix (ARCHITECTURE.md §8, Phase 6a): no {@code CUSTOMER_*} branch, by design - {@code
	 * NcrService#downloadEvidence} blocks every {@code CUSTOMER_VIEWERS} role from CAPA evidence full stop,
	 * regardless of {@code customerVisible} or NCR record scope, so this probe must be strictly narrower than
	 * {@code NcrService#visible}'s general record-scope switch, not a copy of it. */
	@Override
	@Transactional(readOnly = true)
	public boolean capaActionVisibleToCaller(long capaActionId) {
		CapaAction a = capaActions.findById(capaActionId).orElse(null);
		if (a == null || a.ncrId == null) {
			return false;
		}
		Ncr n = ncrs.findById(a.ncrId).orElse(null);
		if (n == null) {
			return false;
		}
		CurrentUser me = CurrentUser.get();
		return switch (QualityScope.of(me.role())) {
			case FULL -> true;
			case KAM -> n.customerCompanyId != null
					&& directory.accountsOf(me.userId()).contains(n.customerCompanyId);
			case NPD_LINKED -> n.linkedModule == LinkedModule.NPD;
			case OFP_LINKED -> n.linkedModule == LinkedModule.OFP;
			case SUPPLIER ->
					n.supplierCompanyId != null && me.companyId() == n.supplierCompanyId;
			default -> false;
		};
	}

	/** Duplicates {@code NcrService#requireCapaEditable} plus its own extra CLOSED check on the CAPA action itself
	 * (not just its parent NCR), deliberately not a dependency on {@code NcrService} - same circular-dependency
	 * reason as {@link #capaActionVisibleToCaller}'s own class-level note. */
	@Override
	@Transactional(readOnly = true)
	public boolean capaReplaceOpenForCaller(long capaActionId) {
		CapaAction a = capaActions.findById(capaActionId).orElse(null);
		if (a == null || a.ncrId == null || a.status == CapaActionStatus.CLOSED) {
			return false;
		}
		Ncr n = ncrs.findById(a.ncrId).orElse(null);
		if (n == null || n.currentStatus == NcrStatus.CLOSED
				|| n.currentStatus == NcrStatus.PENDING_CLOSURE_APPROVAL) {
			return false;
		}
		CurrentUser me = CurrentUser.get();
		boolean ergoEditor = ModuleAccess.actors(Module.QUALITY, CompanyType.ERGO).contains(me.role());
		boolean ownSupplier = ModuleAccess.actors(Module.QUALITY, CompanyType.SUPPLIER).contains(me.role())
				&& n.supplierCompanyId != null
				&& me.companyId() == n.supplierCompanyId;
		return ergoEditor || ownSupplier;
	}

	// This class defaults to @Transactional(readOnly = true); this is the one write, so it needs its own override.
	@Override
	@Transactional
	public void capaEvidenceReplaced(long capaActionId, long newDocumentId) {
		CapaAction a = capaActions.findById(capaActionId).orElse(null);
		if (a != null) {
			a.evidenceDocumentId = newDocumentId;
		}
	}

	@Override
	@Transactional(readOnly = true)
	public List<NcrDashboardItem> ncrDashboardItems() {
		CurrentUser me = CurrentUser.get();
		List<Ncr> visible = switch (QualityScope.of(me.role())) {
			case FULL -> ncrs.findAllByOrderByIdDesc();
			case KAM -> {
				java.util.Set<Long> accounts = directory.accountsOf(me.userId());
				yield accounts.isEmpty() ? List.of() : ncrs.findByCustomerCompanyIdInOrderByIdDesc(accounts);
			}
			case NPD_LINKED -> ncrs.findByLinkedModuleOrderByIdDesc(LinkedModule.NPD);
			case OFP_LINKED -> ncrs.findByLinkedModuleOrderByIdDesc(LinkedModule.OFP);
			case SUPPLIER ->
				ncrs.findBySupplierCompanyIdOrderByIdDesc(me.companyId());
			case CUSTOMER ->
				ncrs.findByCustomerCompanyIdAndCustomerVisibleTrueOrderByIdDesc(me.companyId());
			default -> List.of();
		};
		boolean ergo = me.companyType() == CompanyType.ERGO;
		List<Ncr> open = visible.stream().filter(n -> n.currentStatus != NcrStatus.CLOSED).toList();
		Map<Long, CompanyRef> customers = ergo ? directory.companies(ids(open, n -> n.customerCompanyId)) : Map.of();
		Map<Long, CompanyRef> suppliers = ergo ? directory.companies(ids(open, n -> n.supplierCompanyId)) : Map.of();
		LocalDate today = LocalDate.now();
		return open.stream()
			.map(n -> new NcrDashboardItem(n.id, n.ncrNumber, ergo ? name(customers, n.customerCompanyId) : null,
					ergo ? name(suppliers, n.supplierCompanyId) : null, n.currentStatus.name(),
					ergo ? ncrAwaiting(n.currentStatus) : null, n.targetClosureDate,
					n.targetClosureDate != null && n.targetClosureDate.isBefore(today)))
			.toList();
	}

	/** This module's own judgment call on whose turn it is to act (ARCHITECTURE.md §8, 2026-10-05 Phase 7c) - the
	 * SRS gives no such field. Containment/root-cause/CAPA are the supplier's own driven phases (SRS §10.8's
	 * Supplier Quality edit right), even though ERGO Quality may also edit during them. */
	private static Awaiting ncrAwaiting(NcrStatus status) {
		return switch (status) {
			case OPEN, PENDING_CLOSURE_APPROVAL -> Awaiting.ERGO;
			case CONTAINMENT_IN_PROGRESS, ROOT_CAUSE_IN_PROGRESS, CAPA_IN_PROGRESS -> Awaiting.SUPPLIER;
			default -> Awaiting.NONE;
		};
	}

	@Override
	@Transactional(readOnly = true)
	public List<DeviationDashboardItem> deviationDashboardItems() {
		CurrentUser me = CurrentUser.get();
		List<Deviation> visible = switch (QualityScope.of(me.role())) {
			case FULL -> deviations.findAllByOrderByIdDesc();
			case KAM -> {
				java.util.Set<Long> accounts = directory.accountsOf(me.userId());
				yield accounts.isEmpty() ? List.of() : deviations.findByCustomerCompanyIdInOrderByIdDesc(accounts);
			}
			case NPD_LINKED -> deviations.findByLinkedModuleOrderByIdDesc(LinkedModule.NPD);
			case OFP_LINKED -> deviations.findByLinkedModuleOrderByIdDesc(LinkedModule.OFP);
			case SUPPLIER ->
				deviations.findBySupplierCompanyIdAndSupplierVisibleTrueOrderByIdDesc(me.companyId());
			case CUSTOMER ->
				deviations.findByCustomerCompanyIdAndCustomerVisibleTrueOrderByIdDesc(me.companyId());
			default -> List.of();
		};
		boolean ergo = me.companyType() == CompanyType.ERGO;
		// "Open" = not in the terminal set (ARCHITECTURE.md §8, 2026-10-05 Phase 7c: CLOSED/EXPIRED/REJECTED).
		// APPROVED is not terminal on its own - a deviation stays usable until its validity window ends or ERGO
		// closes it - so it is deliberately not excluded here (fixed after security review: the first cut over-
		// excluded it, contradicting this phase's own logged "open" definition).
		List<Deviation> open = visible.stream().filter(d -> d.currentStatus != DeviationStatus.CLOSED
				&& d.currentStatus != DeviationStatus.REJECTED && d.currentStatus != DeviationStatus.EXPIRED)
			.toList();
		Map<Long, CompanyRef> customers = ergo ? directory.companies(ids(open, d -> d.customerCompanyId)) : Map.of();
		Map<Long, CompanyRef> suppliers = ergo ? directory.companies(ids(open, d -> d.supplierCompanyId)) : Map.of();
		return open.stream()
			.map(d -> new DeviationDashboardItem(d.id, d.deviationNumber, ergo ? name(customers, d.customerCompanyId) : null,
					ergo ? name(suppliers, d.supplierCompanyId) : null, d.currentStatus.name(),
					ergo ? deviationAwaiting(d.currentStatus) : null, d.validityEndDate))
			.toList();
	}

	/** This module's own judgment call (ARCHITECTURE.md §8, 2026-10-05 Phase 7c) - the SRS gives no such field. */
	private static Awaiting deviationAwaiting(DeviationStatus status) {
		return switch (status) {
			case REQUESTED, UNDER_REVIEW -> Awaiting.ERGO;
			case CUSTOMER_APPROVAL_PENDING -> Awaiting.CUSTOMER;
			default -> Awaiting.NONE;
		};
	}

	@Override
	@Transactional(readOnly = true)
	public List<SupplierNcrCount> openNcrCountsBySupplier() {
		CurrentUser me = CurrentUser.get();
		boolean unrestricted = switch (QualityScope.of(me.role())) {
			case FULL -> true;
			default -> false;
		};
		if (!unrestricted) {
			return List.of(); // never one supplier's numbers to another, or to any customer/supplier caller
		}
		Map<Long, List<Ncr>> bySupplier = ncrs.findAllByOrderByIdDesc().stream()
			.filter(n -> n.currentStatus != NcrStatus.CLOSED && n.supplierCompanyId != null)
			.collect(Collectors.groupingBy(n -> n.supplierCompanyId));
		return bySupplier.entrySet().stream()
			.map(e -> new SupplierNcrCount(e.getKey(), e.getValue().size()))
			.toList();
	}

	private static <T> java.util.Set<Long> ids(List<T> items, Function<T, Long> field) {
		return items.stream().map(field).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
	}

	private static String name(Map<Long, CompanyRef> names, Long id) {
		CompanyRef ref = id == null ? null : names.get(id);
		return ref == null ? null : ref.name();
	}

}
