package com.ergo.connect.npd.domain;

/** SRS §13.4.3 npd_activities.status. BLOCKED is only ever set through {@code markBlocked} (a reason is required). */
public enum ActivityStatus {
	NOT_STARTED, IN_PROGRESS, DONE, BLOCKED
}
