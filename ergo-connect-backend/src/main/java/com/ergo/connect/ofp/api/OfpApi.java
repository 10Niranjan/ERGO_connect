package com.ergo.connect.ofp.api;

import java.util.Optional;

/**
 * What other modules may ask of the OFP module. New for Quality's security review M5 (ARCHITECTURE.md §8): a
 * narrow, purpose-built cross-module read, the same shape as {@code npd.api.NpdApi#projectRef}.
 */
public interface OfpApi {

	/** An order's own company columns, no state change. Read is unrestricted by caller type: the consuming module
	 * does its own authorization with the result. Empty if the order doesn't exist. */
	Optional<OrderRef> orderRef(long orderId);

	record OrderRef(long id, long customerCompanyId, long supplierCompanyId) {
	}

}
