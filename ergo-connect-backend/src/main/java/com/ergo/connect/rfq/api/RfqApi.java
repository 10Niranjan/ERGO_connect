package com.ergo.connect.rfq.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.ergo.connect.documents.api.DocumentStore.DocumentInfo;

/**
 * What other modules may ask of the RFQ module (the quotation module: supplier work, comparison, quotes to the
 * customer). Implemented inside rfq.domain, which applies its own record scope and status rules behind these calls.
 */
public interface RfqApi {

	/**
	 * An RFQ as an ERGO user in whose scope it is sees it for quotation work. {@code evaluating}: ERGO may score and
	 * shortlist quotes; {@code offering}: ERGO may send the customer a quote (not while one awaits an answer).
	 */
	record RfqRef(long id, String number, long customerCompanyId, String partNumber, String revision,
			String description, long currencyId, boolean acceptsInvitations, boolean quotingOpen, boolean evaluating,
			boolean offering, Targets targets) {
	}

	/** The customer's targets, in the RFQ currency. ERGO-internal: never shown to suppliers. */
	record Targets(BigDecimal toolingCost, BigDecimal ppapPiecePrice, BigDecimal productionPiecePrice,
			Long paymentTermId, Integer moq, Integer materialLeadTimeWeeks, Integer toolingLeadTimeDays,
			Integer sampleLeadTimeDays, Integer productionLeadTimeDays) {
	}

	/** An RFQ as its own customer sees it for ERGO's quotes. {@code awaitingAnswer}: a sent quote may be answered. */
	record CustomerRfqRef(long id, String number, long customerCompanyId, boolean awaitingAnswer) {
	}

	/** SRS §14.4 customer responses. */
	enum CustomerResponse {
		APPROVED, REVISION_REQUESTED, DECLINED
	}

	/** What a supplier may see of an RFQ. Client decision: no customer name, no targets, no notes, no remarks. */
	record SupplierRfq(long id, String number, String partNumber, String revision, String description, Integer eau,
			BigDecimal netWeightKg, BigDecimal grossWeightKg, String material, String materialSpec, String coating,
			String coatingSpec, List<PlannedStep> processSteps, boolean quotingOpen) {
	}

	/**
	 * One step of the RFQ's planned route as suppliers see it: process and step name only. Cycle times, sourcing,
	 * sub-suppliers and remarks are the customer's or ERGO's and may name third parties; each supplier fills in its own.
	 */
	record PlannedStep(Long processId, String processName, String stepName) {
	}

	/** For the current ERGO user; 404 when the RFQ is outside their scope. */
	RfqRef forErgo(long rfqId);

	/** For the current customer user; 404 unless it is their own company's RFQ. */
	CustomerRfqRef forCustomer(long rfqId);

	/** Callers must have checked the supplier's invitation first. */
	SupplierRfq forSupplier(long rfqId);

	Map<Long, SupplierRfq> forSupplier(Collection<Long> rfqIds);

	/** RFQ files ERGO has shared with suppliers (visibility SUPPLIER or ALL). */
	List<DocumentInfo> supplierDocuments(long rfqId);

	/** SRS §14.2: the first invitations went out (Validated → Sent to Supplier). */
	void sentToSuppliers(long rfqId);

	/**
	 * SRS §14.2: quotes received partially or completely. {@code received}: suppliers with a submitted version;
	 * {@code awaited}: suppliers still working (not yet submitted, or re-opened).
	 */
	void quoteProgress(long rfqId, int received, int awaited);

	/** SRS §14.2 Under Comparison: ERGO has started evaluating the quotes. */
	void comparing(long rfqId);

	/** SRS §14.2 Sent to Customer; 409 unless the RFQ is ready for a quote to the customer. */
	void sentToCustomer(long rfqId);

	/** SRS §14.4: the customer's answer (customer approved / revision requested / declined); 409 if not awaited. */
	void customerResponded(long rfqId, CustomerResponse response);

	/**
	 * A customer-approved RFQ, eligible for manual NPD conversion (SRS §12.8.2, §14.5). {@code approvedAt} is the RFQ's
	 * last-updated time while approved: an approximation (the RFQ has no dedicated approval timestamp), close enough
	 * for the conversion queue's display and safe to use since nothing else changes a customer-approved RFQ in 3a.
	 */
	record ApprovedRfq(long id, String number, long customerCompanyId, String partNumber, String revision,
			String description, Instant approvedAt) {
	}

	/**
	 * Customer-approved RFQs in the caller's ERGO scope, for the "ready for NPD conversion" queue. Empty for anyone
	 * else (customers, suppliers, and ERGO roles with no RFQ visibility).
	 */
	List<ApprovedRfq> customerApproved();

}
