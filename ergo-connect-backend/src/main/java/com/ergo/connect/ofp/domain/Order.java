package com.ergo.connect.ofp.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * ERGO-only order core (SRS §13.5.1, §14.6). Field changes are audited automatically (including
 * {@code currentStatus}, which is how {@code OrderService.release} recovers what a Hold interrupted, without a
 * dedicated previous-status column). Package-private fields: only this package touches them.
 */
@Entity
@Table(name = "orders")
class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "order_id")
	Long id;

	@Column(updatable = false)
	String orderNumber;

	@Column(updatable = false)
	Long customerCompanyId;

	/** ERGO-internal only: the customer never learns which supplier fulfills the order (§8.1, extended to OFP). */
	@Column(updatable = false)
	Long supplierCompanyId;

	@Column(updatable = false)
	Long linkedNpdProjectId;

	@Column(updatable = false)
	Long linkedRfqId;

	String customerPoNo;

	String supplierPoNo;

	/** Fixed at creation, like the company/link fields above: an order's own identity, not something edited later
	 * (OrderService.update only ever touches the administrative fields below it). */
	@Column(updatable = false)
	String partNumber;

	@Column(updatable = false)
	String revision;

	@Column(updatable = false)
	BigDecimal orderedQuantity;

	LocalDate dueDate;

	LocalDate committedDispatchDate;

	LocalDate actualDispatchDate;

	@Enumerated(EnumType.STRING)
	OrderStatus currentStatus;

	Long ownerUserId;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

	@UpdateTimestamp
	@AuditExclude
	Instant updatedAt;

	/** Optimistic lock: of two concurrent changes the later one fails (409) instead of overwriting the other. */
	@Version
	@AuditExclude
	long rowVersion;

}
