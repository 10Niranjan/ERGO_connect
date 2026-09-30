package com.ergo.connect.quality.domain;

/** SRS §14.7 inspection workflow: OPEN -> IN_INSPECTION -> (PASSED | REJECTED | RELEASED_WITH_DEVIATION) -> CLOSED,
 * with CLOSED -> OPEN as the only reopen path (reason required, {@link InspectionService#reopen}). */
public enum ResultStatus {
	OPEN, IN_INSPECTION, PASSED, REJECTED, RELEASED_WITH_DEVIATION, CLOSED
}
