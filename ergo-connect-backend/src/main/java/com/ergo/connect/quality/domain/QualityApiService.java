package com.ergo.connect.quality.domain;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.api.Directory;
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

	QualityApiService(InspectionRepository inspections, AuditLogService audit, CapaActionRepository capaActions,
			NcrRepository ncrs, Directory directory) {
		this.inspections = inspections;
		this.audit = audit;
		this.capaActions = capaActions;
		this.ncrs = ncrs;
		this.directory = directory;
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
		return switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_QUALITY -> true;
			case ERGO_KAM -> n.customerCompanyId != null
					&& directory.accountsOf(me.userId()).contains(n.customerCompanyId);
			case ERGO_NPD -> n.linkedModule == LinkedModule.NPD;
			case ERGO_OFP -> n.linkedModule == LinkedModule.OFP;
			case SUPPLIER_QUALITY, SUPPLIER_NPD, SUPPLIER_OFP, SUPPLIER_KAM ->
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
		boolean ergoEditor = me.role() == Role.ERGO_ADMIN || me.role() == Role.ERGO_QUALITY;
		boolean ownSupplier = me.role() == Role.SUPPLIER_QUALITY && n.supplierCompanyId != null
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

}
