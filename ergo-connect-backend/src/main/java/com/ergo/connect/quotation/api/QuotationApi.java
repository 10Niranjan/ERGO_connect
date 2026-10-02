package com.ergo.connect.quotation.api;

import java.util.Optional;

/**
 * What other modules may ask of the quotation module (the npd module, to convert an approved RFQ). Implemented
 * inside quotation.domain, which applies its own scope behind these calls.
 */
public interface QuotationApi {

	/** The supplier and reference behind an RFQ's approved customer quote. Never exposed to the customer (client
	 * decision: NPD keeps the same supplier/customer anonymity as RFQ). */
	record SelectedQuote(long supplierCompanyId, String supplierName, String quoteReference) {
	}

	/** Empty until the RFQ has an approved customer quote (SRS §14.4 CUSTOMER_APPROVED). */
	Optional<SelectedQuote> selectedQuoteFor(long rfqId);

	/**
	 * True if the current caller may see this supplier invitation (quotation.domain's own record scope, SRS §10.5) -
	 * invitations are the linked record for {@code linked_module = "quotation"} documents (keyed by invitation id,
	 * not RFQ id). Used by the documents module (ARCHITECTURE.md §8, 2026-10-02 Phase 6a).
	 */
	boolean visibleToCaller(long invitationId);

	/** The invitation's supplier company if the current caller may see the invitation (same scope as {@link
	 * #visibleToCaller}); empty otherwise. The customer side is deliberately never returned: customers and
	 * suppliers never see each other. Used by the communication module (ARCHITECTURE.md §8, Phase 6c). */
	java.util.Optional<com.ergo.connect.common.record.RecordParties> partiesVisibleToCaller(long invitationId);

	/**
	 * True if quotation.domain's own UPLOAD rule (SRS §17.2) currently allows a new file for this invitation - used
	 * by the documents module to gate {@code replace} by the owning module's own edit-window rule, not just the
	 * generic lock/approval state (ARCHITECTURE.md §8, 2026-10-02 Phase 6a security review H4 fix; e.g. a supplier
	 * must not be able to replace its quote file through the Document Library after its quote is submitted, which
	 * {@code quoteEditOpen=false} already forbids through quotation.domain's own upload endpoint).
	 */
	boolean replaceOpenForCaller(long invitationId);

	/**
	 * True if the current caller may see this one file of the invitation's quote: quotation.domain's own file rule
	 * (ERGO sees its own files and the supplier's submitted ones, drafts stay private; a supplier sees its own).
	 * False if the invitation or file doesn't exist. Security review M2 (ARCHITECTURE.md §8, Phase 6a).
	 */
	boolean fileVisibleToCaller(long invitationId, long documentId);

	/**
	 * True if the current caller's company holds a supplier invitation for this RFQ, in any status - the
	 * record-scope fact the documents module needs for {@code linked_module = "rfq"} documents shared with suppliers
	 * (visibility SUPPLIER/ALL), since {@code RfqApi} itself has no invitation data. False for non-supplier callers.
	 */
	boolean supplierInvitedToRfq(long rfqId);

}
