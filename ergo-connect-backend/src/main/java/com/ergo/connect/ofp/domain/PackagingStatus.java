package com.ergo.connect.ofp.domain;

/** SRS §13.5.4 order_packaging_dispatch.packaging_status; no SRS-given value list (self-decided, same footing as
 * MaterialStatus/ProductionStatus), matches V14's own CHECK constraint. */
public enum PackagingStatus {
	PENDING, IN_PROGRESS, PACKED
}
