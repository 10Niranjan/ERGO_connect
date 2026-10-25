package com.ergo.connect.rfq.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.ergo.connect.common.record.Awaiting;
import com.ergo.connect.documents.api.DocumentStore.DocumentInfo;

/**
 * What other modules may ask of the RFQ module (the quotation module: distribution to suppliers, per-part quotes,
 * quotations to the customer). Implemented inside rfq.domain, which applies its own record scope and status rules
 * behind these calls. An RFQ holds several items (part numbers, flow doc §1); suppliers see only the items sent to them.
 */
public interface RfqApi {

	/** The customer's targets for one part, in the RFQ currency. ERGO-internal: never shown to suppliers. */
	record Targets(BigDecimal toolingCost, BigDecimal ppapPiecePrice, BigDecimal productionPiecePrice, Integer moq,
			Integer materialLeadTimeWeeks, Integer toolingLeadTimeDays, Integer sampleLeadTimeDays,
			Integer productionLeadTimeDays) {
	}

	/** One part of an RFQ as ERGO sees it. */
	record ItemRef(long id, int lineNo, String partNumber, String revision, String description, Targets targets) {
	}

	/**
	 * An RFQ as an ERGO user in whose scope it is sees it for quotation work. {@code acceptsInvitations}: parts may be
	 * sent to suppliers (the RFQ is under review or already out, and not finished); {@code quotingOpen}: the RFQ is
	 * out with suppliers and not finished, so quote work on its parts is possible (each part's own status decides
	 * what exactly).
	 */
	record RfqRef(long id, String number, long customerCompanyId, long currencyId, Long paymentTermId,
			boolean acceptsInvitations, boolean quotingOpen, List<ItemRef> items) {
	}

	/** An RFQ part as its own customer sees it (no targets, nothing about suppliers). */
	record CustomerItemRef(long id, int lineNo, String partNumber, String revision, String description) {
	}

	/** An RFQ as its own customer sees it for ERGO's quotations. {@code awaitingAnswer}: a sent quotation may be answered. */
	record CustomerRfqRef(long id, String number, long customerCompanyId, boolean awaitingAnswer,
			List<CustomerItemRef> items) {
	}

	/** SRS §14.4 customer responses (flow doc §5: approve, correction required, reject, on hold). */
	enum CustomerResponse {
		APPROVED, REVISION_REQUESTED, DECLINED, ON_HOLD
	}

	/**
	 * What a supplier may see of one RFQ part sent to it. Client decision: no customer name, no targets, no notes, no
	 * remarks, and nothing of the RFQ's other parts (they may be with other suppliers).
	 */
	record SupplierRfqItem(long rfqId, String rfqNumber, long itemId, String partNumber, String revision,
			String description, Integer eau, BigDecimal netWeightKg, BigDecimal grossWeightKg, String material,
			String materialSpec, String coating, String coatingSpec, List<PlannedStep> processSteps,
			boolean quotingOpen) {
	}

	/**
	 * One step of the part's planned route as suppliers see it: process and step name only. Cycle times, sourcing,
	 * sub-suppliers and remarks are the customer's or ERGO's and may name third parties; each supplier fills in its own.
	 */
	record PlannedStep(Long processId, String processName, String stepName) {
	}

	/** For the current ERGO user; 404 when the RFQ is outside their scope. */
	RfqRef forErgo(long rfqId);

	/**
	 * True if the current caller may know this RFQ exists at all (ERGO's own RFQ scope per role, or the customer's
	 * own RFQ) - the record-scope fact the documents module needs to resolve Library/Detail visibility for
	 * {@code linked_module = "rfq"} documents (ARCHITECTURE.md §8, 2026-10-02 Phase 6a). Always {@code false} for a
	 * supplier caller: RfqApi has no invitation data of its own (that lives in quotation.domain) - see
	 * {@code QuotationApi#supplierInvitedToRfq} for the supplier case.
	 */
	boolean visibleToCaller(long rfqId);

	/** The RFQ's customer company if the current caller may see the RFQ (same scope as {@link #visibleToCaller});
	 * empty otherwise. Used by the communication module (ARCHITECTURE.md §8, Phase 6c). */
	java.util.Optional<com.ergo.connect.common.record.RecordParties> partiesVisibleToCaller(long rfqId);

	/**
	 * True if rfq.domain's own UPLOAD rule (SRS §17.1) currently allows a new file for this RFQ - used by the
	 * documents module to gate {@code replace} by the owning module's own edit-window rule, not just the generic
	 * lock/approval state (ARCHITECTURE.md §8, 2026-10-02 Phase 6a security review H4 fix).
	 */
	boolean replaceOpenForCaller(long rfqId);

	/** For the current customer user; 404 unless it is their own company's RFQ. */
	CustomerRfqRef forCustomer(long rfqId);

	/**
	 * Just the RFQ's number, with no scope check of its own: for a module whose own record (an NPD project, an order)
	 * the caller has already proven in scope, so an ERGO role with no RFQ access can still read which RFQ it came from.
	 * External callers get 404: they read the number through {@link #forCustomer} / {@link #forSupplierItem}.
	 */
	String numberOf(long rfqId);

	/** One part sent to the caller's supplier. Callers must have checked the supplier's assignment first. */
	SupplierRfqItem forSupplierItem(long itemId);

	/** The parts as {@link #forSupplierItem}, by item id; parts that do not exist (or while the RFQ module is hidden) are absent. */
	Map<Long, SupplierRfqItem> forSupplierItems(Collection<Long> itemIds);

	/**
	 * RFQ files ERGO has shared with suppliers (visibility SUPPLIER or ALL) for at least one of the given parts: the
	 * caller passes the parts its supplier holds, so a file shared for another supplier's part is never returned.
	 */
	List<DocumentInfo> supplierDocuments(long rfqId, Collection<Long> itemIds);

	/** The RFQ part ids of the caller's own company's RFQs that are still live, with their RFQ (a customer's dashboard). */
	record CustomerPart(long rfqId, String rfqNumber, long itemId, String partNumber) {
	}

	List<CustomerPart> customerParts();

	/**
	 * How the RFQ's parts stand, reported by the quotation module after every change to a part. The RFQ's own status
	 * (sent to suppliers, quotes partly / fully in, with the customer, on hold, correction required, approved,
	 * declined) is worked out from it here. The counts cover the parts that are with a supplier or further; the RFQ's
	 * other parts are unassigned.
	 */
	record QuotationSummary(int withSupplier, int underErgoReview, int accepted, int withCustomer, int onHold,
			int correction, int approved, int rejected, int sentBack) {

		/** {@code sentBack}: how many of the {@code withSupplier} parts are back with the supplier after it answered. */
		public QuotationSummary(int withSupplier, int underErgoReview, int accepted, int withCustomer, int onHold,
				int correction, int approved, int rejected) {
			this(withSupplier, underErgoReview, accepted, withCustomer, onHold, correction, approved, rejected, 0);
		}
	}

	void quotationProgress(long rfqId, QuotationSummary summary);

	/**
	 * Distribution (flow doc §2): the RFQ must be complete, and its first distribution moves it to "sent to
	 * suppliers"; 409 unless suppliers can be sent parts, 422 listing what is missing from an incomplete RFQ.
	 */
	void distributing(long rfqId);

	/**
	 * One part as ERGO sees it, for the modules that start from an approved part (NPD). Empty unless the part exists
	 * in an RFQ within the caller's ERGO scope.
	 */
	java.util.Optional<ItemRef> itemForErgo(long itemId);

	/**
	 * SRS §12.5.1/§12.5.2 dashboards: the caller's own in-scope, still-open RFQs (same role-scope switch as
	 * {@code list()} - ERGO Admin/Management/RFQ get every RFQ, ERGO KAM its assigned accounts, customers their own
	 * company, suppliers nothing here (RfqApi has no invitation data of its own)). Excludes the terminal statuses
	 * (CLOSED, CANCELLED, CUSTOMER_DECLINED) and CUSTOMER_APPROVED. {@code customerName} is null for a customer
	 * caller (it's their own company). {@link Awaiting} is this module's own judgment call (ARCHITECTURE.md §8,
	 * 2026-10-05): not an SRS-defined field. {@code parts}: the RFQ's part numbers.
	 */
	record RfqDashboardItem(long id, String number, String customerName, String status, Awaiting awaiting,
			LocalDate targetQuoteDate, boolean overdue, String priority, List<String> parts) {
	}

	List<RfqDashboardItem> dashboardItems();

}
