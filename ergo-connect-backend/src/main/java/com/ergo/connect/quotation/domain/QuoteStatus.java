package com.ergo.connect.quotation.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The status of one part's quote (flow doc §3-5; the invitation row is "one part sent to one supplier"). The supplier
 * quotes and submits the part, ERGO sends it back (revision required) or accepts it, the generated customer quotation
 * then carries the part to the customer, whose answer ends up here.
 */
public enum QuoteStatus {

	NOT_STARTED, DRAFT_SAVED, SUBMITTED, REVISION_REQUIRED, RESUBMITTED, ACCEPTED, WITH_CUSTOMER, CUSTOMER_ON_HOLD,
	CUSTOMER_CORRECTION, CUSTOMER_APPROVED, CUSTOMER_REJECTED, RECALLED,
	/** The supplier's quote was unfinished when the customer decided the part; nothing more to do (supplier not told why). */
	CLOSED;

	/** The supplier may still work on the part's quote. */
	static final Set<QuoteStatus> EDITABLE = EnumSet.of(NOT_STARTED, DRAFT_SAVED, REVISION_REQUIRED);

	/** A submitted quote exists and waits for ERGO's review. */
	static final Set<QuoteStatus> UNDER_REVIEW = EnumSet.of(SUBMITTED, RESUBMITTED);

	/** ERGO may generate a customer quotation for the part: accepted, or sent back by the customer for correction. */
	static final Set<QuoteStatus> READY_FOR_CUSTOMER = EnumSet.of(ACCEPTED, CUSTOMER_CORRECTION);

	/** The part is with the customer, who has not given a final answer. */
	static final Set<QuoteStatus> WITH_CUSTOMER_OPEN = EnumSet.of(WITH_CUSTOMER, CUSTOMER_ON_HOLD);

	/** ERGO may send the quote back to the supplier: it was submitted and is not with the customer or decided. */
	static final Set<QuoteStatus> RETURNABLE = EnumSet.of(SUBMITTED, RESUBMITTED, ACCEPTED, CUSTOMER_CORRECTION);

	/** The part's quote is final for the customer's decision. */
	static final Set<QuoteStatus> DECIDED = EnumSet.of(CUSTOMER_APPROVED, CUSTOMER_REJECTED);

	/** The quote ERGO selected for the part, from selection to the customer's answer: at most one per part (V45 index). */
	static final Set<QuoteStatus> SELECTED = EnumSet.of(ACCEPTED, WITH_CUSTOMER, CUSTOMER_ON_HOLD, CUSTOMER_CORRECTION,
			CUSTOMER_APPROVED, CUSTOMER_REJECTED);

	/** The selected quote is with the customer or decided: no other supplier's quote on the part can change the outcome. */
	static final Set<QuoteStatus> LOCKS_PART = EnumSet.of(WITH_CUSTOMER, CUSTOMER_ON_HOLD, CUSTOMER_APPROVED,
			CUSTOMER_REJECTED);

	/**
	 * Where a part stands when several suppliers quote it: the selected quote's status; else submitted (waiting for
	 * ERGO's review) if any quote is; else sent back, started or not started. {@code null} when no supplier holds it.
	 */
	public static QuoteStatus ofPart(java.util.Collection<QuoteStatus> active) {
		for (QuoteStatus s : active) {
			if (SELECTED.contains(s)) {
				return s;
			}
		}
		for (QuoteStatus wanted : new QuoteStatus[] { SUBMITTED, RESUBMITTED, REVISION_REQUIRED, DRAFT_SAVED, NOT_STARTED }) {
			if (active.contains(wanted)) {
				return wanted;
			}
		}
		return active.isEmpty() ? null : CLOSED;
	}

	/**
	 * The status a supplier sees: what the customer does with ERGO's quotation is none of the supplier's business
	 * (flow doc: no customer data reaches a supplier without ERGO), so everything from "accepted" on reads as accepted.
	 */
	public QuoteStatus forSupplier() {
		return switch (this) {
			case ACCEPTED, WITH_CUSTOMER, CUSTOMER_ON_HOLD, CUSTOMER_CORRECTION, CUSTOMER_APPROVED, CUSTOMER_REJECTED ->
				ACCEPTED;
			// CLOSED only happens when the customer decided the part for ANOTHER supplier; showing it would tell this
			// supplier that a competitor exists. It reads as an unfinished draft that can no longer be edited.
			case CLOSED -> DRAFT_SAVED;
			default -> this;
		};
	}

	/** What the customer sees for a part: where its quotation stands, nothing about suppliers. */
	public CustomerPartStatus forCustomer() {
		return switch (this) {
			case WITH_CUSTOMER -> CustomerPartStatus.QUOTATION_RECEIVED;
			case CUSTOMER_ON_HOLD -> CustomerPartStatus.ON_HOLD;
			case CUSTOMER_CORRECTION -> CustomerPartStatus.CORRECTION_REQUIRED;
			case CUSTOMER_APPROVED -> CustomerPartStatus.APPROVED;
			case CUSTOMER_REJECTED -> CustomerPartStatus.REJECTED;
			default -> CustomerPartStatus.IN_PROGRESS;
		};
	}

	public enum CustomerPartStatus {
		IN_PROGRESS, QUOTATION_RECEIVED, ON_HOLD, CORRECTION_REQUIRED, APPROVED, REJECTED
	}

}
