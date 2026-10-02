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

	/**
	 * True if the current caller is an ERGO POD reader in scope of the order behind this shipment (ofp.domain's
	 * {@code OrderController#POD_READERS} restriction, not generic order record scope) - shipments are the linked
	 * record for {@code linked_module = "OFP_SHIPMENT"} documents (POD files), keyed by shipment id, not order id.
	 * Suppliers and customers never see the POD file itself, only receipt fields, so this is deliberately narrower
	 * than an order-visibility check. False if the shipment doesn't exist. Used by the documents module
	 * (ARCHITECTURE.md §8, 2026-10-02 Phase 6a security review C1 fix).
	 */
	boolean shipmentVisibleToCaller(long shipmentId);

	/**
	 * True if ofp.domain's own POD upload rule ({@code UPDATE_SHIPMENT}: ERGO OFP/Admin, order dispatched or later)
	 * currently allows the caller to put a new POD on this shipment - the documents module's {@code replace} gate.
	 */
	boolean podReplaceOpenForCaller(long shipmentId);

	/**
	 * Repoints {@code Shipment.podDocumentId} at the Document Library's new version after a {@code replace}, so this
	 * module's own POD download endpoint doesn't keep serving the superseded file while the Library shows the new
	 * one. No-op if the shipment doesn't exist. Used by the documents module (ARCHITECTURE.md §8, 2026-10-02 Phase
	 * 6a security review H4 fix).
	 */
	void podDocumentReplaced(long shipmentId, long newDocumentId);

}
