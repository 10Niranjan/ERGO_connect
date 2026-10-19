package com.ergo.connect.npd.domain;

import com.ergo.connect.common.security.Role;

/**
 * SRS §13.4.7 npd_approvals.approval_type, each mapped here to exactly one canonical approver role
 * (ARCHITECTURE.md §8 2026-09-26): {@code approver_role} is computed from this mapping and never accepted from the
 * client. ERGO Admin may additionally decide an ERGO-owned type (STAGE_GATE, QUALITY_GATE) on top of the named role
 * — confirmed with the user that this override must NOT extend to the CUSTOMER_* types, which are the customer's own
 * gate (SRS §10.6, the customer edit/approve footnotes); that exception lives in NpdService, not here.
 */
public enum ApprovalType {

	STAGE_GATE(Role.ERGO_NPD, true), QUALITY_GATE(Role.ERGO_NPD, true),
	CUSTOMER_SAMPLE_APPROVAL(Role.CUSTOMER_NPD, false),
	CUSTOMER_MILESTONE_ACKNOWLEDGMENT(Role.CUSTOMER_NPD, false),
	CUSTOMER_QUALITY_ACCEPTANCE(Role.CUSTOMER_NPD, false);

	private final Role approverRole;

	private final boolean ergoOwned;

	ApprovalType(Role approverRole, boolean ergoOwned) {
		this.approverRole = approverRole;
		this.ergoOwned = ergoOwned;
	}

	Role approverRole() {
		return approverRole;
	}

	/** Whether ERGO Admin's blanket override applies to this type, on top of the named approver role. */
	boolean ergoOwned() {
		return ergoOwned;
	}

}
