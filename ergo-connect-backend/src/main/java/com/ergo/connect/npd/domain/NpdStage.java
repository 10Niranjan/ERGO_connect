package com.ergo.connect.npd.domain;

/**
 * SRS §14.5's fixed, forward-only 9-stage sequence (10 values counting Pending Conversion). Changed only by
 * NpdService's workflow actions (conversion, Close Stage); there is no reopen action in 3a.
 */
public enum NpdStage {

	PENDING_CONVERSION, PLANNING, COMMERCIAL_SOURCING_READINESS, TOOLING_MATERIAL_RELEASE, TRIAL_PREPARATION,
	TRIAL_EXECUTION, FINAL_SAMPLE_READINESS, CUSTOMER_FEEDBACK, APPROVED_FOR_PRODUCTION_HANDOVER, CLOSED;

	/** Stages a production (OFP) project does not go through: tool development and trial preparation (flow doc step 11). */
	public boolean skippedBy(ProjectKind kind) {
		return kind == ProjectKind.PRODUCTION && (this == TOOLING_MATERIAL_RELEASE || this == TRIAL_PREPARATION);
	}

	/** The next stage this kind of project goes through. */
	NpdStage next(ProjectKind kind) {
		NpdStage next = next();
		while (next.skippedBy(kind)) {
			next = next.next();
		}
		return next;
	}

	/** The next stage in the fixed sequence. There is none after {@link #CLOSED}. */
	NpdStage next() {
		NpdStage[] all = values();
		int i = ordinal();
		if (i + 1 == all.length) {
			throw new IllegalStateException("CLOSED has no next stage");
		}
		return all[i + 1];
	}

}
