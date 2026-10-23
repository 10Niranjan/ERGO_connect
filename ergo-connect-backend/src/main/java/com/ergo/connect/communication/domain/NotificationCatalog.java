package com.ergo.connect.communication.domain;

import static com.ergo.connect.common.security.CompanyType.CUSTOMER;
import static com.ergo.connect.common.security.CompanyType.ERGO;
import static com.ergo.connect.common.security.CompanyType.SUPPLIER;
import static com.ergo.connect.communication.api.NotificationType.ACTION_REQUIRED;
import static com.ergo.connect.communication.api.NotificationType.APPROVAL_PENDING;
import static com.ergo.connect.communication.api.NotificationType.DOCUMENT_UPLOADED;
import static com.ergo.connect.communication.api.NotificationType.OVERDUE_REMINDER;
import static com.ergo.connect.communication.api.NotificationType.RECORD_UPDATED;
import static com.ergo.connect.communication.api.NotificationType.RESPONSE_RECEIVED;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.ergo.connect.common.event.NotificationKind;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.communication.api.NotificationType;

/**
 * The one place that decides what each event is called, what drawer type it is, and who it may be addressed to
 * (SRS §12.12.2, §14.9). Wording is written per audience here, never by the publishing module: ERGO sees what
 * happened, a customer or supplier sees only a neutral line about its own side - no counterpart, no ERGO staff name.
 * {@code audiences} is a second guard: whatever a publisher asks for, a recipient from any other side gets nothing.
 */
final class NotificationCatalog {

	/** {@code ergoTitle} / {@code externalTitle} are null where that side is not an audience of the event. */
	record Spec(NotificationType type, Set<CompanyType> audiences, String ergoTitle, String externalTitle) {

		String titleFor(CompanyType recipient) {
			return recipient == ERGO ? ergoTitle : externalTitle;
		}

	}

	private static final Map<NotificationKind, Spec> SPECS = new EnumMap<>(NotificationKind.class);

	static {
		// RFQ and quotation
		put(NotificationKind.RFQ_SUBMITTED, ACTION_REQUIRED, EnumSet.of(ERGO), "RFQ submitted for review", null);
		put(NotificationKind.RFQ_CLARIFICATION_REQUESTED, ACTION_REQUIRED, EnumSet.of(CUSTOMER), null,
				"ERGO needs clarification on your RFQ");
		put(NotificationKind.RFQ_STATUS_CHANGED, RECORD_UPDATED, EnumSet.of(ERGO, CUSTOMER), "RFQ status changed",
				"Your RFQ status changed");
		put(NotificationKind.QUOTE_REQUESTED, ACTION_REQUIRED, EnumSet.of(SUPPLIER), null, "New quote request");
		put(NotificationKind.QUOTE_SUBMITTED, RESPONSE_RECEIVED, EnumSet.of(ERGO), "Supplier quote submitted", null);
		put(NotificationKind.QUOTE_CLARIFICATION_REQUESTED, ACTION_REQUIRED, EnumSet.of(ERGO),
				"Supplier asked for clarification", null);
		put(NotificationKind.QUOTE_CLARIFICATION_ANSWERED, RESPONSE_RECEIVED, EnumSet.of(SUPPLIER), null,
				"Your clarification request was answered");
		put(NotificationKind.QUOTE_REOPENED, ACTION_REQUIRED, EnumSet.of(SUPPLIER), null,
				"Revision required on your quote");
		put(NotificationKind.QUOTE_REGRETTED, RESPONSE_RECEIVED, EnumSet.of(ERGO), "Supplier declined to quote", null);
		put(NotificationKind.CUSTOMER_QUOTE_SENT, ACTION_REQUIRED, EnumSet.of(CUSTOMER), null,
				"A quote is ready for your decision");
		put(NotificationKind.CUSTOMER_QUOTE_RESPONDED, RESPONSE_RECEIVED, EnumSet.of(ERGO),
				"Customer responded to a quote", null);

		// NPD
		put(NotificationKind.NPD_STAGE_CHANGED, RECORD_UPDATED, EnumSet.of(ERGO, CUSTOMER, SUPPLIER),
				"NPD stage changed", "NPD project stage changed");
		put(NotificationKind.NPD_APPROVAL_REQUESTED, APPROVAL_PENDING, EnumSet.of(ERGO, CUSTOMER),
				"Approval requested", "Your approval is requested");
		put(NotificationKind.NPD_APPROVAL_DECIDED, RESPONSE_RECEIVED, EnumSet.of(ERGO), "Approval decision recorded",
				null);
		put(NotificationKind.NPD_ACTIVITY_ASSIGNED, ACTION_REQUIRED, EnumSet.of(ERGO, CUSTOMER, SUPPLIER),
				"Activity assigned to you", "Activity assigned to you");

		// OFP
		put(NotificationKind.ORDER_STATUS_CHANGED, RECORD_UPDATED, EnumSet.of(ERGO, CUSTOMER, SUPPLIER),
				"Order status changed", "Order status changed");
		put(NotificationKind.DISPATCH_RECORDED, RECORD_UPDATED, EnumSet.of(ERGO, CUSTOMER), "Shipment dispatched",
				"Your order has been dispatched");
		put(NotificationKind.DELIVERY_CONFIRMED, RECORD_UPDATED, EnumSet.of(ERGO, CUSTOMER), "Delivery confirmed",
				"Your order was delivered");

		// Quality
		put(NotificationKind.INSPECTION_UPDATED, RECORD_UPDATED, EnumSet.of(ERGO, CUSTOMER, SUPPLIER),
				"Inspection result recorded", "Inspection result recorded");
		put(NotificationKind.NCR_RAISED, ACTION_REQUIRED, EnumSet.of(ERGO, SUPPLIER), "NCR needs attention",
				"An NCR needs your attention");
		put(NotificationKind.NCR_UPDATED, RECORD_UPDATED, EnumSet.of(ERGO, CUSTOMER, SUPPLIER), "NCR updated",
				"NCR updated");
		put(NotificationKind.NCR_CLOSURE_REQUESTED, APPROVAL_PENDING, EnumSet.of(ERGO),
				"NCR closure awaiting approval", null);
		put(NotificationKind.DEVIATION_APPROVAL_REQUESTED, APPROVAL_PENDING, EnumSet.of(ERGO, CUSTOMER),
				"Deviation awaiting approval", "A deviation awaits your approval");
		put(NotificationKind.DEVIATION_DECIDED, RESPONSE_RECEIVED, EnumSet.of(ERGO, CUSTOMER, SUPPLIER),
				"Deviation decision recorded", "Deviation decision recorded");

		// Documents and communication
		put(NotificationKind.DOCUMENT_UPLOADED, DOCUMENT_UPLOADED, EnumSet.of(ERGO, CUSTOMER, SUPPLIER),
				"Document uploaded", "A document was added");
		put(NotificationKind.DOCUMENT_DECIDED, RECORD_UPDATED, EnumSet.of(ERGO, CUSTOMER, SUPPLIER),
				"Document review update", "Your document was reviewed");
		put(NotificationKind.MENTIONED, RESPONSE_RECEIVED, EnumSet.of(ERGO, CUSTOMER, SUPPLIER),
				"You were mentioned in a comment", "You were mentioned in a comment");
		put(NotificationKind.COMMENT_REPLY, RESPONSE_RECEIVED, EnumSet.of(ERGO, CUSTOMER, SUPPLIER),
				"New reply to your comment", "New reply to your comment");
		put(NotificationKind.THREAD_ESCALATED, ACTION_REQUIRED, EnumSet.of(ERGO, CUSTOMER, SUPPLIER),
				"A thread was escalated", "Your conversation was escalated");

		// Company onboarding (ERGO staff only)
		put(NotificationKind.COMPANY_APPROVAL_REQUESTED, APPROVAL_PENDING, EnumSet.of(ERGO),
				"Company awaiting approval", null);
		put(NotificationKind.COMPANY_APPROVAL_DECIDED, RESPONSE_RECEIVED, EnumSet.of(ERGO),
				"Company approval decision recorded", null);

		// Scheduled reminders
		put(NotificationKind.OVERDUE_ACTIVITY, OVERDUE_REMINDER, EnumSet.of(ERGO, CUSTOMER, SUPPLIER),
				"NPD activity is overdue", "An NPD activity is overdue");
		put(NotificationKind.OVERDUE_QUOTE, OVERDUE_REMINDER, EnumSet.of(ERGO, SUPPLIER),
				"Supplier quote response is overdue", "Your quote response is overdue");
		put(NotificationKind.OVERDUE_NCR, OVERDUE_REMINDER, EnumSet.of(ERGO, SUPPLIER),
				"NCR is past its target closure date", "An NCR is past its target closure date");
		put(NotificationKind.OVERDUE_CAPA, OVERDUE_REMINDER, EnumSet.of(ERGO, SUPPLIER),
				"CAPA action is overdue", "A CAPA action is overdue");
	}

	private NotificationCatalog() {
	}

	private static void put(NotificationKind kind, NotificationType type, Set<CompanyType> audiences,
			String ergoTitle, String externalTitle) {
		SPECS.put(kind, new Spec(type, audiences, ergoTitle, externalTitle));
	}

	static Spec of(NotificationKind kind) {
		return SPECS.get(kind);
	}

}
