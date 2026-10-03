package com.ergo.connect.common.event;

/**
 * Something past its due date, reported by the owning module to the communication module's daily reminder scan (SRS
 * §14.9 "overdue activity"). Same rules as any notification: {@code recipients} is a request, and each person is
 * re-checked against the record at delivery; {@code detail} is shown to every recipient, so it carries only the date.
 */
public record OverdueItem(NotificationKind kind, String linkedModule, long recordId, String label, String detail,
		Recipients recipients, String itemKey) {

	/** {@code itemKey} names the late thing itself (an activity, a CAPA action), which can be one of several on the
	 * same record: it is what keeps reminders to one per person per item per day. */
}
