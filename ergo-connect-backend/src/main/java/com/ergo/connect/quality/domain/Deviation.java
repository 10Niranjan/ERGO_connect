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
 * A deviation request raised against an NPD sample or OFP order (SRS §13.6.4, §14.7), Phase 5c. Same denormalized
 * company-column / polymorphic-link shape as {@link Inspection}/{@link Ncr} and the same record-scope reason (see
 * {@code DeviationService}'s class comment), restricted to {@code linkedModule} NPD/OFP only (V19 migration's CHECK
 * constraint - a deviation is raised against a specific NPD sample or OFP order, not a standalone inspection type).
 * Package-private: only this package touches it.
 */
@Entity
@Table(name = "deviations")
class Deviation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "deviation_id")
	Long id;

	@Column(updatable = false)
	String deviationNumber;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	LinkedModule linkedModule;

	@Column(updatable = false)
	Long linkedRecordId;

	/** Optional traceability link to the inspection this deviation originated from (e.g. a "Released with Deviation"
	 * disposition) - set once at creation, never reassignable. */
	@Column(updatable = false)
	Long inspectionId;

	Long customerCompanyId;

	Long supplierCompanyId;

	String partNumber;

	String deviationDescription;

	@Column(updatable = false)
	Long requestedByUserId;

	String qualityImpact;

	String technicalImpact;

	/** Locked after creation (judgment call, {@code DeviationService}'s class comment): changing it mid-flow would
	 * require retroactively creating/removing the {@code CUSTOMER_QUALITY} approval row underneath an in-progress
	 * review, which the SRS does not describe a rule for. */
	@Column(updatable = false)
	boolean customerApprovalRequiredFlag;

	BigDecimal validityQuantity;

	LocalDate validityEndDate;

	@Enumerated(EnumType.STRING)
	DeviationStatus currentStatus;

	/** Gates the customer's own read access (mirrors {@code Inspection.customerVisible}/{@code Ncr.customerVisible}),
	 * independent of {@link #customerApprovalRequiredFlag} (V19 migration comment) - see {@code DeviationService}'s
	 * class comment for exactly when this is set. */
	boolean customerVisible;

	/** Security review H3 fix: the supplier-side mirror of {@link #customerVisible} - gates the supplier's own read
	 * access so a customer-authored deviation's free text (description/quality impact/technical impact) is not
	 * visible to the supplier until an ERGO editor has reviewed and shared it, the same reasoning that already
	 * applies to {@link #customerVisible} for ERGO-/supplier-authored content. {@code DeviationService}'s class
	 * comment covers exactly when this is set. */
	boolean supplierVisible;

	@Column(updatable = false)
	Long createdByUserId;

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
