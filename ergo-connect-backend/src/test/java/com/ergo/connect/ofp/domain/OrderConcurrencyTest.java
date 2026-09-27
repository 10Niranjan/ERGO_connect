package com.ergo.connect.ofp.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestUsers;

/**
 * Same package as the entity/repository on purpose (package-private access, like {@code Order.rowVersion} itself):
 * proves the {@code @Version} optimistic lock actually rejects a write based on state that was already stale when
 * it was read, the scenario a client-facing MockMvc test can't deterministically force without real thread
 * interleaving (SRS §15.4-adjacent concurrency guard the RFQ/NPD modules rely on too, ARCHITECTURE.md §8 2026-09-25).
 */
@IntegrationTest
class OrderConcurrencyTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager txManager;

	@Autowired
	OrderRepository orders;

	@Test
	void aWriteBasedOnAnAlreadyStaleReadIsRejected() {
		TestUsers users = new TestUsers(jdbc, null);
		long customerId = users.company("CUSTOMER");
		long supplierId = users.company("SUPPLIER");
		long orderId = jdbc.queryForObject("""
				INSERT INTO orders (order_number, customer_company_id, supplier_company_id, part_number,
				                     ordered_quantity, current_status)
				VALUES (?, ?, ?, ?, ?, 'ORDER_CREATED') RETURNING order_id""", Long.class, "ORD-TEST-CONCURRENCY",
				customerId, supplierId, "PN-CONC", BigDecimal.TEN);

		TransactionTemplate tx = new TransactionTemplate(txManager);

		// this request's own read, still at row_version 0
		Order stale = tx.execute(status -> orders.findById(orderId).orElseThrow());

		// a second, independent request reads and commits its own change first, bumping row_version to 1
		tx.executeWithoutResult(status -> orders.findById(orderId).orElseThrow().currentStatus = OrderStatus.MATERIAL_ORDERED);

		// the first request now tries to write back based on what it read before the second one committed
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			stale.currentStatus = OrderStatus.PRODUCTION_STARTED;
			orders.save(stale);
		})).isInstanceOf(OptimisticLockingFailureException.class);

		// the second request's own change is what stuck, not the stale one
		assertThat(jdbc.queryForObject("SELECT current_status FROM orders WHERE order_id = ?", String.class, orderId))
			.isEqualTo("MATERIAL_ORDERED");
	}

}
