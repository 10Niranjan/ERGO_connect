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

/** Per-process-step production tracking (SRS §13.5.3), ERGO OFP/Admin only in 4a. */
@Entity
@Table(name = "order_production_progress")
class OrderProductionProgress {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "production_progress_id")
	Long id;

	@Column(updatable = false)
	Long orderId;

	String processStepName;

	BigDecimal plannedQuantity;

	BigDecimal completedQuantity;

	BigDecimal acceptedQuantity;

	BigDecimal rejectedQuantity;

	BigDecimal reworkQuantity;

	@Enumerated(EnumType.STRING)
	ProductionStatus status;

	/** Mandatory at the service layer when {@code status == BLOCKED} (SRS §14.6). */
	String blockerReason;

	LocalDate revisedDispatchDate;

	Long updatedByUserId;

	@UpdateTimestamp
	Instant updatedAt;

}
