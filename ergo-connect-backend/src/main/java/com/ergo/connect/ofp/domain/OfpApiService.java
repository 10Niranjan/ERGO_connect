package com.ergo.connect.ofp.domain;

import static com.ergo.connect.common.security.Role.CUSTOMER_OFP;
import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;
import static com.ergo.connect.common.security.Role.ERGO_NPD;
import static com.ergo.connect.common.security.Role.ERGO_OFP;
import static com.ergo.connect.common.security.Role.SUPPLIER_KAM;
import static com.ergo.connect.common.security.Role.SUPPLIER_NPD;
import static com.ergo.connect.common.security.Role.SUPPLIER_OFP;
import static com.ergo.connect.common.security.Role.SUPPLIER_QUALITY;
import static com.ergo.connect.ofp.domain.OrderStatus.CLOSED;
import static com.ergo.connect.ofp.domain.OrderStatus.DELIVERED;
import static com.ergo.connect.ofp.domain.OrderStatus.DISPATCHED;
import static com.ergo.connect.ofp.domain.OrderStatus.IN_TRANSIT;
import static com.ergo.connect.ofp.domain.OrderStatus.PACKAGING_IN_PROGRESS;
import static com.ergo.connect.ofp.domain.OrderStatus.PRODUCTION_STARTED;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.record.Awaiting;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.ofp.api.OfpApi;

/** Implements {@link OfpApi} for Quality's security review M5 (ARCHITECTURE.md §8): a narrow, purpose-built
 * cross-module read, the same shape as {@code NpdApiService#projectRef}. */
@Service
@Transactional
class OfpApiService implements OfpApi {

	private static final Set<OrderStatus> NOT_YET_DISPATCHED = EnumSet.complementOf(
			EnumSet.of(DISPATCHED, IN_TRANSIT, DELIVERED, CLOSED));

	private static final Set<Role> SUPPLIER_EDITORS = EnumSet.of(SUPPLIER_NPD, SUPPLIER_OFP, SUPPLIER_QUALITY,
			SUPPLIER_KAM);

	private final OrderRepository orders;

	private final ShipmentRepository shipments;

	private final OrderService orderService;

	private final Directory directory;

	OfpApiService(OrderRepository orders, ShipmentRepository shipments, OrderService orderService,
			Directory directory) {
		this.orders = orders;
		this.shipments = shipments;
		this.orderService = orderService;
		this.directory = directory;
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
	@Transactional(readOnly = true)
	public Optional<com.ergo.connect.common.record.RecordParties> orderPartiesVisibleToCaller(long orderId) {
		if (!orderService.inScope(CurrentUser.get(), orderId)) {
			return Optional.empty();
		}
		return orders.findById(orderId).map(o -> new com.ergo.connect.common.record.RecordParties(o.customerCompanyId, o.supplierCompanyId));
	}

	@Override
	public void podDocumentReplaced(long shipmentId, long newDocumentId) {
		Shipment shipment = shipments.findById(shipmentId).orElse(null);
		if (shipment != null) {
			shipment.podDocumentId = newDocumentId;
		}
	}

	@Override
	@Transactional(readOnly = true)
	public List<OrderDashboardItem> dashboardItems() {
		CurrentUser me = CurrentUser.get();
		List<Order> visible;
		if (SUPPLIER_EDITORS.contains(me.role())) {
			visible = orders.findBySupplierCompanyIdOrderByIdDesc(me.companyId());
		}
		else if (me.role() == CUSTOMER_OFP) {
			visible = orders.findByCustomerCompanyIdInOrderByIdDesc(Set.of(me.companyId()));
		}
		else {
			visible = switch (me.role()) {
				case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_OFP -> orders.findAllByOrderByIdDesc();
				case ERGO_KAM -> {
					Set<Long> accounts = directory.accountsOf(me.userId());
					yield accounts.isEmpty() ? List.of() : orders.findByCustomerCompanyIdInOrderByIdDesc(accounts);
				}
				case ERGO_NPD -> orders.findByLinkedNpdProjectIdIsNotNullOrderByIdDesc();
				default -> List.of();
			};
		}
		boolean ergo = me.companyType() == CompanyType.ERGO;
		List<Order> open = visible.stream().filter(o -> o.currentStatus != CLOSED).toList();
		Map<Long, CompanyRef> customers = ergo ? directory.companies(ids(open, o -> o.customerCompanyId)) : Map.of();
		Map<Long, CompanyRef> suppliers = ergo ? directory.companies(ids(open, o -> o.supplierCompanyId)) : Map.of();
		LocalDate today = LocalDate.now();
		return open.stream()
			.map(o -> new OrderDashboardItem(o.id, o.orderNumber, ergo ? name(customers, o.customerCompanyId) : null,
					ergo ? name(suppliers, o.supplierCompanyId) : null, o.currentStatus.name(),
					ergo ? awaiting(o.currentStatus) : null, o.committedDispatchDate, atRisk(o, today)))
			.toList();
	}

	/** Client-chosen formula (ARCHITECTURE.md §8, 2026-10-05 Phase 7c): the SRS gives none. */
	private static boolean atRisk(Order o, LocalDate today) {
		return o.committedDispatchDate != null && o.committedDispatchDate.isBefore(today)
				&& NOT_YET_DISPATCHED.contains(o.currentStatus);
	}

	/** This module's own judgment call on whose turn it is to act (ARCHITECTURE.md §8, 2026-10-05 Phase 7c) - the
	 * SRS gives no such field. OFP is largely ERGO-driven (SRS §10.7); only production and packaging/dispatch are
	 * the supplier's own write actions (Phase 4b). */
	private static Awaiting awaiting(OrderStatus status) {
		return (status == PRODUCTION_STARTED || status == PACKAGING_IN_PROGRESS) ? Awaiting.SUPPLIER : Awaiting.ERGO;
	}

	@Override
	@Transactional(readOnly = true)
	public List<SupplierPerformance> supplierPerformance() {
		CurrentUser me = CurrentUser.get();
		boolean unrestricted = switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_OFP -> true;
			default -> false;
		};
		if (!unrestricted) {
			return List.of(); // never one supplier's numbers to another, or to any customer/supplier caller
		}
		List<Order> all = orders.findAllByOrderByIdDesc();
		Map<Long, CompanyRef> suppliers = directory.companies(ids(all, o -> o.supplierCompanyId));
		Map<Long, List<Order>> bySupplier = all.stream()
			.filter(o -> o.supplierCompanyId != null)
			.collect(Collectors.groupingBy(o -> o.supplierCompanyId));
		return bySupplier.entrySet().stream()
			.map(e -> {
				long supplierId = e.getKey();
				List<Order> supplierOrders = e.getValue();
				int open = (int) supplierOrders.stream().filter(o -> o.currentStatus != CLOSED).count();
				List<Order> dispatched = supplierOrders.stream().filter(o -> o.actualDispatchDate != null).toList();
				int onTime = (int) dispatched.stream()
					.filter(o -> o.committedDispatchDate != null && !o.actualDispatchDate.isAfter(o.committedDispatchDate))
					.count();
				return new SupplierPerformance(supplierId, name(suppliers, supplierId), open, dispatched.size(), onTime);
			})
			.toList();
	}

	private static Set<Long> ids(List<Order> items, Function<Order, Long> field) {
		return items.stream().map(field).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
	}

	private static String name(Map<Long, CompanyRef> names, Long id) {
		CompanyRef ref = id == null ? null : names.get(id);
		return ref == null ? null : ref.name();
	}

}
