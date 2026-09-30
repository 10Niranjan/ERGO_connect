package com.ergo.connect.ofp.domain;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.ofp.api.OfpApi;

/** Implements {@link OfpApi} for Quality's security review M5 (ARCHITECTURE.md §8): a narrow, purpose-built
 * cross-module read, the same shape as {@code NpdApiService#projectRef}. */
@Service
@Transactional
class OfpApiService implements OfpApi {

	private final OrderRepository orders;

	OfpApiService(OrderRepository orders) {
		this.orders = orders;
	}

	@Override
	public Optional<OrderRef> orderRef(long orderId) {
		return orders.findById(orderId).map(o -> new OrderRef(o.id, o.customerCompanyId, o.supplierCompanyId));
	}

}
