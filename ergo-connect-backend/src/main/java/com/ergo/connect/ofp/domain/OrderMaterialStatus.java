package com.ergo.connect.ofp.domain;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Incoming material tracking (SRS §13.5.2), ERGO OFP/Admin only in 4a. */
@Entity
@Table(name = "order_material_status")
class OrderMaterialStatus {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "order_material_status_id")
	Long id;

	@Column(updatable = false)
	Long orderId;

	@Enumerated(EnumType.STRING)
	MaterialStatus materialStatus;

	LocalDate materialOrderDate;

	LocalDate expectedReceiptDate;

	LocalDate actualReceiptDate;

	@Enumerated(EnumType.STRING)
	InspectionStatus inspectionStatus;

	String remarks;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

	@UpdateTimestamp
	Instant updatedAt;

}
