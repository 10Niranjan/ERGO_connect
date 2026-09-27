package com.ergo.connect.ofp.domain;

/** SRS §13.5.3 order_production_progress.status; no SRS-given value list (self-decided), reuses npd_activities'
 * own step-status set for consistency (V12's own migration comment). {@link #BLOCKED} requires a blocker_reason at
 * the service layer (SRS §14.6). */
public enum ProductionStatus {
	NOT_STARTED, IN_PROGRESS, DONE, BLOCKED
}
