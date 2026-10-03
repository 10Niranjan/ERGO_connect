package com.ergo.connect.common.event;

/**
 * Published (as a Spring application event) when something notifiable happens. Handled by the communication module
 * after the publishing transaction commits, so a rolled-back change notifies nobody and a notification failure can
 * never undo business work.
 *
 * @param linkedModule the record the notification opens: rfq, quotation, npd, ofp, inspection, ncr, deviation, or
 *        document
 * @param label how the record is named to the people told (RFQ number, order number, file name); never counterpart
 *        identity
 * @param detail optional short neutral context (new status, decision); appended to the message as-is, so publishers
 *        must not put anything in it that every possible recipient may not see
 * @param actorUserId who caused it, so they are never told about their own action; null for scheduled events
 * @param dedupeKey set for scheduled reminders: a recipient is told at most once per key
 */
public record NotificationEvent(NotificationKind kind, String linkedModule, long linkedRecordId, String label,
		String detail, Long actorUserId, Recipients recipients, String dedupeKey) {
}
