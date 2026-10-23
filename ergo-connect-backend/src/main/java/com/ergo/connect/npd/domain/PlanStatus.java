package com.ergo.connect.npd.domain;

/**
 * Where an NPD project's plan stands (flow doc steps 7-8). The supplier sees a project only from ASSIGNED_TO_SUPPLIER
 * on; the project goes to execution only once the plan is AGREED.
 * <pre>
 * AWAITING_PLAN -> ASSIGNED_TO_SUPPLIER -> SUBMITTED -> AGREED
 *                                  ^           |
 *                                  +-- CHANGES_REQUESTED (ERGO NPD sent it back, comment mandatory)
 * </pre>
 */
public enum PlanStatus {

	AWAITING_PLAN, ASSIGNED_TO_SUPPLIER, SUBMITTED, CHANGES_REQUESTED, AGREED;

	/** The supplier may fill in its steps (dates, responsible person, remarks) and submit. */
	public boolean openForSupplier() {
		return this == ASSIGNED_TO_SUPPLIER || this == CHANGES_REQUESTED;
	}

}
