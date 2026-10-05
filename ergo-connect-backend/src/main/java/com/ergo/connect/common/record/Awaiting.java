package com.ergo.connect.common.record;

/**
 * Whose turn it is to act on a record, for the functional dashboards (SRS §12.5.2: "own pending actions" vs. "items
 * waiting for external response" are two different widgets over the same in-scope list). Each owning module derives
 * this from its own status/workflow logic - the SRS does not define it, so it is a per-module judgment call
 * (ARCHITECTURE.md §8, 2026-10-05 Phase 7c). {@code NONE} means the record is open but nobody is specifically
 * blocked on it (e.g. an RFQ sitting in the customer-approved/ready-for-conversion queue).
 */
public enum Awaiting {
	ERGO, CUSTOMER, SUPPLIER, NONE
}
