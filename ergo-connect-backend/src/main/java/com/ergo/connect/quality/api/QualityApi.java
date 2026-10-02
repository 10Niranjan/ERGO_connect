package com.ergo.connect.quality.api;

/**
 * What other modules may ask of the Quality module. Phase 5d: OFP's dispatch gate and NPD's Final Sample Readiness
 * stage gate both reach forward into Quality's own facade - a deliberate, accepted exception to the documented
 * "the later-built module reaches into the earlier one, never the reverse" direction ({@code NpdApi}'s own class
 * comment): Quality (Phase 5) did not exist when OFP (Phase 4) and NPD (Phase 3) were built, so their original
 * dispatch/stage gates were stubs (OFP's own {@code OrderMaterialStatus.inspectionStatus}, a self-decided field with
 * no SRS-given value list). This phase closes that gap the same way every other cross-module read in this codebase
 * is built: a narrow, purpose-built facade, never a reach into {@code quality.domain} directly (enforced either way
 * by ModuleBoundaryTest, which polices the {@code <module>.api} boundary itself, not a build-order direction).
 */
public interface QualityApi {

	/**
	 * True once the most recently raised inspection linked to this OFP order ({@code linkedModule = OFP}, {@code
	 * linkedRecordId = orderId}) has resolved to PASSED or RELEASED_WITH_DEVIATION, and at least one such inspection
	 * exists - an order with no inspection recorded at all has not "passed", it simply hasn't been checked yet (the
	 * same "zero rows is not a pass" rule the stub this replaces already used). Only the latest inspection is
	 * considered (client decision, 2026-10-01, ARCHITECTURE.md §8), so a later re-inspection that passes clears an
	 * earlier REJECTED one on the same record. An inspection that has since moved to CLOSED retains no disposition of
	 * its own on the entity (PASSED/REJECTED/RELEASED_WITH_DEVIATION all collapse into CLOSED); its effective
	 * disposition is resolved from its own audit history's last such value before it was closed.
	 */
	boolean ofpDispatchGateMet(long orderId);

	/**
	 * The same gate, for the inspection(s) linked to this NPD project ({@code linkedModule = NPD}, {@code
	 * linkedRecordId = npdProjectId}) - NPD's own Final Sample Readiness stage-close gate.
	 */
	boolean npdFinalSampleGateMet(long npdProjectId);

	/**
	 * True if the current caller may see the NCR that owns this CAPA action (quality.domain's own NCR record scope,
	 * SRS §10.8) - CAPA evidence files ({@code linked_module = "QUALITY_CAPA"} documents) are keyed by CAPA action
	 * id, not NCR id. False if the CAPA action doesn't exist. Used by the documents module (ARCHITECTURE.md §8,
	 * 2026-10-02 Phase 6a).
	 */
	boolean capaActionVisibleToCaller(long capaActionId);

	/** Both companies of the inspection / NCR / deviation if the current caller may see that record (each service's
	 * own record scope, including the customerVisible / supplierVisible gates); empty otherwise. Used by the
	 * communication module (ARCHITECTURE.md §8, Phase 6c). */
	java.util.Optional<com.ergo.connect.common.record.RecordParties> inspectionPartiesVisibleToCaller(long inspectionId);

	java.util.Optional<com.ergo.connect.common.record.RecordParties> ncrPartiesVisibleToCaller(long ncrId);

	java.util.Optional<com.ergo.connect.common.record.RecordParties> deviationPartiesVisibleToCaller(long deviationId);

	/**
	 * True if quality.domain's own CAPA-evidence edit window ({@code NcrService#requireCapaEditable} plus the CAPA
	 * action's own CLOSED check) currently allows a new file for this CAPA action - used by the documents module's
	 * generic {@code replace} so it can't be used to bypass this module's own workflow rule (e.g. a supplier
	 * replacing evidence after the CAPA action, or its parent NCR, has closed). False if the CAPA action doesn't
	 * exist. Used by the documents module (ARCHITECTURE.md §8, 2026-10-02 Phase 6a security review H4 fix).
	 */
	boolean capaReplaceOpenForCaller(long capaActionId);

	/**
	 * Repoints {@code CapaAction.evidenceDocumentId} at the Document Library's new version after a {@code replace},
	 * so {@code NcrService#downloadEvidence} doesn't keep serving the superseded file while the Library shows the
	 * new one. No-op if the CAPA action doesn't exist. Used by the documents module (ARCHITECTURE.md §8, 2026-10-02
	 * Phase 6a security review H4 fix).
	 */
	void capaEvidenceReplaced(long capaActionId, long newDocumentId);

}
