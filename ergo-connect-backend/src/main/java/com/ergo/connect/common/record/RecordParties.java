package com.ergo.connect.common.record;

/**
 * The customer and supplier companies behind a record another module owns, as returned by a module's non-throwing
 * "visible to caller" probe (Phase 6c, communication). Only returned for a record the current caller may see, and used
 * by the consuming module for its own audience rules - never copied into an HTTP response. Either side may be null
 * (an RFQ has no supplier of its own; a supplier's quote has no customer-facing side).
 */
public record RecordParties(Long customerCompanyId, Long supplierCompanyId) {
}
