package com.ergo.connect.rfq.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.ergo.connect.documents.api.DocumentStore.DocumentInfo;

/**
 * What other modules may ask of the RFQ module (the quotation module, for supplier work). Implemented inside
 * rfq.domain. The RFQ module applies its own record scope and status rules behind these calls.
 */
public interface RfqApi {

	/** An RFQ as an ERGO user in whose scope it is sees it for supplier work. */
	record RfqRef(long id, String number, long customerCompanyId, String partNumber, String revision,
			String description, long currencyId, boolean acceptsInvitations, boolean quotingOpen) {
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

}
