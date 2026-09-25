package com.ergo.connect.quotation.domain;

import java.util.EnumSet;
import java.util.Set;

/** SRS §14.3 supplier quote workflow, plus REGRETTED (the supplier declined to quote, with a reason). */
public enum QuoteStatus {

	NOT_STARTED, DRAFT_SAVED, CLARIFICATION_REQUESTED, SUBMITTED, REOPENED, RESUBMITTED, SHORTLISTED,
	SELECTED_FOR_CUSTOMER, NOT_SELECTED, REGRETTED;

	/** A submitted quote exists (and is locked unless re-opened). */
	static final Set<QuoteStatus> SUBMITTED_STATES = EnumSet.of(SUBMITTED, RESUBMITTED, SHORTLISTED,
			SELECTED_FOR_CUSTOMER, NOT_SELECTED);

	/** The supplier may still work on its quote. */
	static final Set<QuoteStatus> EDITABLE = EnumSet.of(NOT_STARTED, DRAFT_SAVED, CLARIFICATION_REQUESTED, REOPENED);

	/** ERGO's evaluation of a submitted quote (SRS §17.3). ERGO-internal. */
	static final Set<QuoteStatus> DECIDED = EnumSet.of(SHORTLISTED, SELECTED_FOR_CUSTOMER, NOT_SELECTED);

	/**
	 * The status a supplier sees: ERGO's shortlisting and selection are internal rankings (CLAUDE.md), so a supplier
	 * sees its quote as submitted, or resubmitted from the second version on.
	 */
	public QuoteStatus forSupplier(int version) {
		return DECIDED.contains(this) ? (version > 1 ? RESUBMITTED : SUBMITTED) : this;
	}

}
