package com.ergo.connect.quality.domain;

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
 * Quality inspection (SRS §13.6.1, §14.7). Field changes are audited automatically. Company columns are denormalized
 * onto the row (not derived via a join through {@link #linkedModule}/{@link #linkedRecordId}) so record-scope
 * filtering (SRS §8.2) stays a plain indexed WHERE, matching {@code orders.customerCompanyId}/{@code
 * supplierCompanyId} (V16 migration comment). Package-private fields: only this package touches them.
 */
@Entity
@Table(name = "inspections")
class Inspection {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "inspection_id")
	Long id;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	LinkedModule linkedModule;

	@Column(updatable = false)
	Long linkedRecordId;

	/** ERGO-internal by default: only set once ERGO (or the record scope check) is ready to associate this
	 * inspection with a customer. Never supplier-settable (two-way anonymity, same rule as every other module). */
	Long customerCompanyId;

	Long supplierCompanyId;

	String partNumber;

	String batchLotNo;

	BigDecimal quantityChecked;

	BigDecimal quantityRejected;

	@Enumerated(EnumType.STRING)
	ResultStatus resultStatus;

	Long inspectedByUserId;

	LocalDate inspectionDate;

	String remarks;

	/** Marks the record visible to the Customer Quality role (and other customer roles' identical scope), SRS §8.2.
	 * ERGO-only to set: never trusted from a supplier's own write. */
	boolean customerVisible;

	@Column(updatable = false)
	Long createdByUserId;

	/** Security review H2 (ARCHITECTURE.md §8 2026-09-30): which company created this row, so Supplier Quality's
	 * edit/submit gate can be "my own company created this", not just "tagged to my company" - see
	 * {@code InspectionService.allowed}. Set once at creation, never reassignable. */
	@Column(updatable = false)
	Long createdByCompanyId;

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
