package com.ergo.connect.quality.domain;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.quality.api.QualityApi;

/**
 * Implements {@link QualityApi} for OFP's dispatch gate and NPD's Final Sample Readiness stage gate (Phase 5d): a
 * narrow, purpose-built cross-module read, the same shape as {@code OfpApiService}/{@code NpdApiService}. Read is
 * unrestricted by caller type, same as those two - the consuming module's own controller-level role check already
 * confines who actually reaches this.
 */
@Service
@Transactional(readOnly = true)
class QualityApiService implements QualityApi {

	private final InspectionRepository inspections;

	private final AuditLogService audit;

	QualityApiService(InspectionRepository inspections, AuditLogService audit) {
		this.inspections = inspections;
		this.audit = audit;
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

}
