package com.ergo.connect.quotation.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * What other modules may ask of the quotation module (the npd module, to convert an approved RFQ). Implemented
 * inside quotation.domain, which applies its own scope behind these calls.
 */
public interface QuotationApi {

	/**
	 * A part whose quotation the customer approved (flow doc §5: it proceeds to Customer NPD): the supplier and reference
	 * behind it, never exposed to the customer (client decision: NPD keeps the same supplier/customer anonymity as RFQ).
	 */
	record ApprovedPart(long quoteId, long rfqId, long rfqItemId, long customerCompanyId, long supplierCompanyId,
			String supplierName, String quoteReference, java.time.Instant approvedAt) {
	}

	/** The approved parts within the current ERGO caller's scope (RFQ scope per role; empty for anyone else). */
	List<ApprovedPart> approvedParts();

	/** One approved part, if it is within the current ERGO caller's scope. */
	Optional<ApprovedPart> approvedPart(long rfqItemId);

	/**
	 * Flow doc §4 dashboards: where each part stands, per caller - ERGO the full status and the supplier, the RFQ's
	 * customer where its quotation stands (no supplier), a supplier its own parts (never what the customer decided).
	 */
	record PartStatusItem(long rfqId, String rfqNumber, long rfqItemId, String partNumber, String status,
			String supplierName) {
	}

	List<PartStatusItem> partStatusItems();

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

	/** Supplier quotes past their response date that the supplier can still submit, for the daily reminder scan. */
	java.util.List<com.ergo.connect.common.event.OverdueItem> overdueItems(java.time.LocalDate today);

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
	 * True if this RFQ file is shared with suppliers for a part the current caller's company holds (flow doc: a supplier
	 * sees only the parts sent to it) - the record-scope fact the documents module needs for {@code linked_module =
	 * "rfq"} documents, since {@code RfqApi} itself has no invitation data. False for non-supplier callers.
	 */
	boolean supplierMayReadRfqDocument(long rfqId, long documentId);

	/**
	 * SRS §12.5.4 Supplier Dashboard ("Assigned RFQs", "Quote due items"): the caller's own parts, status
	 * already collapsed through {@link com.ergo.connect.quotation.domain.QuoteStatus#forSupplier} (never what
	 * the customer decided about ERGO's quotation). Non-empty only for a supplier caller who actually quotes
	 * (same role set as {@code QuoteService.SUPPLIER_QUOTERS}) - empty for every ERGO/customer role.
	 */
	record SupplierQuoteItem(long invitationId, String rfqNumber, String partNumber, String status,
			LocalDate responseDueDate, boolean overdue) {
	}

	List<SupplierQuoteItem> dashboardItems();

	/** SRS §12.5.1 Management Dashboard "Quotes pending submission": count of invitations still editable by their
	 * supplier (not yet submitted), across every supplier - unrestricted-view roles only (ERGO Admin/Management/
	 * RFQ), 0 for everyone else. */
	int pendingSubmissionCount();

}
