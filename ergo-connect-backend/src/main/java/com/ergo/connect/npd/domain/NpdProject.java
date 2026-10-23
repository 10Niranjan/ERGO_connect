package com.ergo.connect.npd.domain;

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
 * The manual RFQ->NPD conversion result and its 9-stage workflow (SRS §13.4.1, §14.5). Field changes are audited
 * automatically; stage changes only ever happen in NpdService's workflow actions, which also write an explicit
 * npd_stage_history row. Package-private fields: only this package touches them.
 */
@Entity
@Table(name = "npd_projects")
class NpdProject {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "npd_project_id")
	Long id;

	@Column(updatable = false)
	String npdNumber;

	@Column(updatable = false)
	Long sourceRfqId;

	/** The RFQ part this project develops: each approved part proceeds on its own (flow doc §5). */
	@Column(updatable = false)
	Long sourceRfqItemId;

	@Column(updatable = false)
	Long customerCompanyId;

	/** ERGO-internal only: the customer never learns which supplier develops their part (§8.1, extended to NPD). */
	@Column(updatable = false)
	Long supplierCompanyId;

	String partNumber;

	String revision;

	String description;

	/** Copied from the RFQ's approved supplier quote at conversion time. */
	String quoteReference;

	@Enumerated(EnumType.STRING)
	NpdStage stage;

	LocalDate targetSampleDate;

	LocalDate actualSampleSubmissionDate;

	/** Unused by 3a (no "Handover to OFP" action yet); a later phase sets it for real. */
	boolean handoverReadyFlag;

	Long ownerUserId;

	@Enumerated(EnumType.STRING)
	RiskLevel riskLevel;

	@Column(updatable = false)
	Long createdByUserId;

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
