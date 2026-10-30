package com.ergo.connect.ofp.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** Package-private; OrderService applies the record scope for every read. */
interface OrderRepository extends Repository<Order, Long> {

	Order save(Order order);

	Optional<Order> findById(Long id);

	/** The list page. The only Specification method on purpose (JpaSpecificationExecutor would bring bulk update/delete that skip @Version and audit). */
	org.springframework.data.domain.Page<Order> findAll(org.springframework.data.jpa.domain.Specification<Order> spec,
			org.springframework.data.domain.Pageable pageable);

	List<Order> findAllByOrderByIdDesc();

	List<Order> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	/** ERGO NPD's scope (SRS §10.7 "RV"): every order that came out of an NPD project, since ERGO NPD is itself an
	 * unrestricted viewer within the NPD module (NpdService.FULL_VIEWERS). */
	List<Order> findByLinkedNpdProjectIdIsNotNullOrderByIdDesc();

	/** A supplier's own scope (SRS §10.7 "RV", 4b): direct assignment match, no assignment table (unlike ERGO KAM's
	 * many customer accounts, a supplier has exactly one company). */
	List<Order> findBySupplierCompanyIdOrderByIdDesc(long supplierCompanyId);

}

interface OrderMaterialStatusRepository extends Repository<OrderMaterialStatus, Long> {

	OrderMaterialStatus save(OrderMaterialStatus status);

	Optional<OrderMaterialStatus> findById(Long id);

	List<OrderMaterialStatus> findByOrderIdOrderById(Long orderId);

}

interface OrderProductionProgressRepository extends Repository<OrderProductionProgress, Long> {

	OrderProductionProgress save(OrderProductionProgress progress);

	Optional<OrderProductionProgress> findById(Long id);

	List<OrderProductionProgress> findByOrderIdOrderById(Long orderId);

}

interface OrderPackagingDispatchRepository extends Repository<OrderPackagingDispatch, Long> {

	OrderPackagingDispatch save(OrderPackagingDispatch dispatch);

	Optional<OrderPackagingDispatch> findById(Long id);

	List<OrderPackagingDispatch> findByOrderIdOrderById(Long orderId);

}

interface ShipmentRepository extends Repository<Shipment, Long> {

	Shipment save(Shipment shipment);

	Optional<Shipment> findById(Long id);

	List<Shipment> findByOrderIdOrderById(Long orderId);

}
