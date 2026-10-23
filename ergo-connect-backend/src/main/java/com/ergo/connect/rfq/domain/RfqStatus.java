package com.ergo.connect.rfq.domain;

import java.util.Set;

/** SRS §14.2 plus CANCELLED (SRS §12.6.3 "Cancel RFQ"). Changed only by RfqService's workflow actions. */
public enum RfqStatus {

	DRAFT, SUBMITTED, UNDER_REVIEW, CLARIFICATION_REQUIRED, VALIDATED, SENT_TO_SUPPLIERS, QUOTES_PARTIAL,
	QUOTES_COMPLETE, UNDER_COMPARISON, SENT_TO_CUSTOMER, CUSTOMER_ON_HOLD, CUSTOMER_REVISION_REQUESTED,
	CUSTOMER_APPROVED, CUSTOMER_DECLINED, CLOSED, CANCELLED;

	static final Set<RfqStatus> FINAL = Set.of(CUSTOMER_DECLINED, CLOSED, CANCELLED);

	/** What customers see: internal progress (supplier float, comparison) is shown as one step. */
	public CustomerStatus forCustomer() {
		return switch (this) {
			case DRAFT -> CustomerStatus.DRAFT;
			case SUBMITTED -> CustomerStatus.SUBMITTED;
			case UNDER_REVIEW -> CustomerStatus.UNDER_REVIEW;
			case CLARIFICATION_REQUIRED -> CustomerStatus.CLARIFICATION_REQUIRED;
			case VALIDATED, SENT_TO_SUPPLIERS, QUOTES_PARTIAL, QUOTES_COMPLETE, UNDER_COMPARISON ->
				CustomerStatus.IN_PROGRESS;
			case SENT_TO_CUSTOMER -> CustomerStatus.QUOTE_RECEIVED;
			case CUSTOMER_ON_HOLD -> CustomerStatus.ON_HOLD;
			case CUSTOMER_REVISION_REQUESTED -> CustomerStatus.REVISION_REQUESTED;
			case CUSTOMER_APPROVED -> CustomerStatus.APPROVED;
			case CUSTOMER_DECLINED -> CustomerStatus.DECLINED;
			case CLOSED -> CustomerStatus.CLOSED;
			case CANCELLED -> CustomerStatus.CANCELLED;
		};
	}

	public enum CustomerStatus {
		DRAFT, SUBMITTED, UNDER_REVIEW, CLARIFICATION_REQUIRED, IN_PROGRESS, QUOTE_RECEIVED, ON_HOLD, REVISION_REQUESTED,
		APPROVED, DECLINED, CLOSED, CANCELLED
	}

}
