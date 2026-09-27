package com.ergo.connect.ofp.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** Package-private; OrderService applies the record scope for every read. */
interface OrderRepository extends Repository<Order, Long> {

	Order save(Order order);

	Optional<Order> findById(Long id);

	List<Order> findAllByOrderByIdDesc();

	List<Order> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	/** ERGO NPD's scope (SRS §10.7 "RV"): every order that came out of an NPD project, since ERGO NPD is itself an
	 * unrestricted viewer within the NPD module (NpdService.FULL_VIEWERS). */
	List<Order> findByLinkedNpdProjectIdIsNotNullOrderByIdDesc();

	/** Defense in depth alongside NpdApi.handover's own guard: at most one order per NPD project. */
	boolean existsByLinkedNpdProjectId(Long linkedNpdProjectId);

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
