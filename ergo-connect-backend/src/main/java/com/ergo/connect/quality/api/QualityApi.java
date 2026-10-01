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

}
