package com.ergo.connect.ofp.domain;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.ofp.api.OfpApi;

/** Implements {@link OfpApi} for Quality's security review M5 (ARCHITECTURE.md §8): a narrow, purpose-built
 * cross-module read, the same shape as {@code NpdApiService#projectRef}. */
@Service
@Transactional
class OfpApiService implements OfpApi {

	private final OrderRepository orders;

	private final ShipmentRepository shipments;

	private final OrderService orderService;

	OfpApiService(OrderRepository orders, ShipmentRepository shipments, OrderService orderService) {
		this.orders = orders;
		this.shipments = shipments;
		this.orderService = orderService;
	}

	@Override
	public Optional<OrderRef> orderRef(long orderId) {
		return orders.findById(orderId).map(o -> new OrderRef(o.id, o.customerCompanyId, o.supplierCompanyId));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean shipmentVisibleToCaller(long shipmentId) {
		Shipment shipment = shipments.findById(shipmentId).orElse(null);
		return shipment != null && orderService.podReaderInScope(CurrentUser.get(), shipment.orderId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean podReplaceOpenForCaller(long shipmentId) {
		Shipment shipment = shipments.findById(shipmentId).orElse(null);
		return shipment != null && orderService.shipmentUploadOpen(CurrentUser.get(), shipment.orderId);
	}

	@Override
	public void podDocumentReplaced(long shipmentId, long newDocumentId) {
		Shipment shipment = shipments.findById(shipmentId).orElse(null);
		if (shipment != null) {
			shipment.podDocumentId = newDocumentId;
		}
	}

}
