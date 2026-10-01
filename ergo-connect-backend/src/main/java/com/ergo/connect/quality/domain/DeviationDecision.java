package com.ergo.connect.quality.domain;

/** {@code deviation_approvals.decision} (V19 migration). Unlike {@code npd_approvals.ApprovalStatus}, this adds
 * SENT_BACK (CLAUDE.md "reopen, reject and send-back require a reason") and the row is reused across review cycles
 * (reset back to PENDING by {@code DeviationService} whenever the deviation re-enters the status this row decides
 * for - see {@code DeviationStatus}'s javadoc) rather than one fresh row per decision like {@code NpdApproval}. */
public enum DeviationDecision {
	PENDING, APPROVED, REJECTED, SENT_BACK
}
