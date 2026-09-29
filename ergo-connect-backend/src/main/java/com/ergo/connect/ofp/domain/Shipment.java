package com.ergo.connect.ofp.domain;

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

/**
 * Shipment / tracking record (SRS §13.5.5, §14.6), Phase 4c. ERGO OFP/Admin-only write surface (§10.7's literal
 * matrix gives Supplier OFP "RV" only, and its "E**" footnote never names a shipment field); a supplier and, from
 * 4c, the order's own customer read it through their own per-audience order view only (never write it). 1 order :
 * many shipments (partial/multi-shipment dispatch, ARCHITECTURE.md §8 2026-09-29). {@code status} reuses
 * {@link OrderStatus}'s own DISPATCHED/IN_TRANSIT/DELIVERED names rather than a separate enum (same decision),
 * validated to one of those three at the service layer — the DB CHECK constraint (V15) enforces the same three
 * strings independently. {@code podDocumentId} is set only through {@code OrderService.uploadPod} (the Documents
 * module), never a plain form field. {@code receiptConfirmedByCustomerFlag}/{@code receiptConfirmedAt} are ERGO-set
 * on the customer's behalf; no direct customer write path exists. Field changes are audited automatically.
 */
@Entity
@Table(name = "shipments")
class Shipment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "shipment_id")
	Long id;

	@Column(updatable = false)
	Long orderId;

	Long shipmentModeId;

	String transporterName;

	String forwarderName;

	String trackingNumber;

	String awbBlNo;

	LocalDate eta;

	LocalDate actualDeliveryDate;

	Long podDocumentId;

	boolean receiptConfirmedByCustomerFlag;

	Instant receiptConfirmedAt;

	@Enumerated(EnumType.STRING)
	OrderStatus status;

	Long updatedByUserId;

	@UpdateTimestamp
	Instant updatedAt;

}
