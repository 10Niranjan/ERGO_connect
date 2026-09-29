package com.ergo.connect.ofp.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Packaging and dispatch-readiness tracking (SRS §13.5.4). ERGO OFP/Admin plus, from 4b, the order's assigned
 * supplier (SRS §10.7 "E**") may write here; {@code dispatchReadyFlag} is that supplier's own readiness claim only
 * and never moves {@code Order.currentStatus} by itself (ARCHITECTURE.md §8 2026-09-29) — see
 * {@code OrderService.dispatchReady} for the gated ERGO-only transition. Field changes are audited automatically. */
@Entity
@Table(name = "order_packaging_dispatch")
class OrderPackagingDispatch {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "packaging_dispatch_id")
	Long id;

	@Column(updatable = false)
	Long orderId;

	BigDecimal packedQuantity;

	@Enumerated(EnumType.STRING)
	PackagingStatus packagingStatus;

	boolean dispatchReadyFlag;

	LocalDate dispatchDate;

	String dispatchRemarks;

	Long updatedByUserId;

	@UpdateTimestamp
	Instant updatedAt;

}
