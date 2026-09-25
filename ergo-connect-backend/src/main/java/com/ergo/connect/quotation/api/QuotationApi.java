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

}
