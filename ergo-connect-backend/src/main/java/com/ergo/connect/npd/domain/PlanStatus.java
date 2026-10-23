package com.ergo.connect.npd.domain;

/** Where an NPD project's plan stands (flow doc steps 7-8). The supplier sees a project only from ASSIGNED_TO_SUPPLIER on. */
public enum PlanStatus {

	AWAITING_PLAN, ASSIGNED_TO_SUPPLIER, AGREED

}
