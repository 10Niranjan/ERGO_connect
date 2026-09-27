package com.ergo.connect.ofp.domain;

import java.util.List;

/**
 * SRS §13.5.1/§14.6 order lifecycle. Phase 4a drives the linear sequence up to {@link #PACKAGING_IN_PROGRESS} only
 * ({@link #SEQUENCE}); {@link #DISPATCH_READY} onward is 4b/4c. {@link #ON_HOLD} is a side-transition reachable from
 * any non-terminal status, released back to whatever status it was held from (recovered from the automatic audit
 * trail rather than a new column, ARCHITECTURE.md §8 2026-09-28).
 */
public enum OrderStatus {

	ORDER_CREATED, MATERIAL_ORDERED, MATERIAL_RECEIVED, PRODUCTION_STARTED, IN_INSPECTION, PACKAGING_IN_PROGRESS,
	DISPATCH_READY, DISPATCHED, IN_TRANSIT, DELIVERED, CLOSED, ON_HOLD;

	/** The forward-only path 4a actually drives (ERGO OFP/Admin, one step at a time via {@code advance}). */
	static final List<OrderStatus> SEQUENCE = List.of(ORDER_CREATED, MATERIAL_ORDERED, MATERIAL_RECEIVED,
			PRODUCTION_STARTED, IN_INSPECTION, PACKAGING_IN_PROGRESS);

}
