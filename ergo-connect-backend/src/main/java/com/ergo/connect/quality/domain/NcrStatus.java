package com.ergo.connect.quality.domain;

/** SRS §14.7 NCR/CAPA workflow: OPEN -> CONTAINMENT_IN_PROGRESS -> ROOT_CAUSE_IN_PROGRESS -> CAPA_IN_PROGRESS ->
 * PENDING_CLOSURE_APPROVAL -> CLOSED, linear (V18 migration's CHECK constraint order), plus SEND_BACK
 * (PENDING_CLOSURE_APPROVAL -> CAPA_IN_PROGRESS, reason required) and REOPEN (CLOSED -> OPEN, reason required) as the
 * two reverse paths - same shape as {@link ResultStatus}'s own forward-path-plus-reopen precedent. */
public enum NcrStatus {
	OPEN, CONTAINMENT_IN_PROGRESS, ROOT_CAUSE_IN_PROGRESS, CAPA_IN_PROGRESS, PENDING_CLOSURE_APPROVAL, CLOSED
}
