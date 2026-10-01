package com.ergo.connect.quality.domain;

/** SRS §14.7 deviation workflow (V19 migration's CHECK constraint order): REQUESTED -&gt; UNDER_REVIEW (ERGO Quality
 * starts review) -&gt; [CUSTOMER_APPROVAL_PENDING, only when {@code customerApprovalRequiredFlag} is set, once ERGO
 * Quality approves its row] -&gt; APPROVED (once every required {@link DeviationApproval} row is APPROVED) or REJECTED
 * (any required approver rejects, terminal - no further transition is named by the brief). APPROVED can later become
 * EXPIRED automatically ({@code DeviationService#expireOverdueDeviations}) once {@code validityEndDate} passes;
 * APPROVED or EXPIRED can be manually CLOSED by ERGO Quality/Admin. SENT_BACK is not a status of its own - it is a
 * {@link DeviationDecision} on one approval row that routes {@code currentStatus} back to REQUESTED (ERGO's own row)
 * or UNDER_REVIEW (the customer's row, sending it back to ERGO for rework), reason required. */
public enum DeviationStatus {
	REQUESTED, UNDER_REVIEW, CUSTOMER_APPROVAL_PENDING, APPROVED, REJECTED, EXPIRED, CLOSED
}
