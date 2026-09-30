package com.ergo.connect.quality.domain;

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
 * Non-conformance report (SRS §13.6.3, §14.7), Phase 5b. Same polymorphic {@code linkedModule}/{@code
 * linkedRecordId} link and denormalized company columns as {@link Inspection} (V18 migration comment), for the same
 * record-scope reason - see {@code NcrService}'s class comment. Package-private: only this package touches it.
 */
@Entity
@Table(name = "ncrs")
class Ncr {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "ncr_id")
	Long id;

	@Column(updatable = false)
	String ncrNumber;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	LinkedModule linkedModule;

	@Column(updatable = false)
	Long linkedRecordId;

	Long customerCompanyId;

	Long supplierCompanyId;

	String issueCategory;

	String issueDescription;

	/** Filled progressively as the NCR moves through §14.7 - null until that stage is reached, same as {@link
	 * Inspection}'s result fields. Shared free text with the linked company: unlike {@code Inspection.remarks},
	 * these are the point of raising an NCR against a party, not an ERGO-internal note (see {@code NcrService}'s
	 * class comment). */
	String containmentAction;

	String rootCause;

	String correctiveAction;

	String preventiveAction;

	/** Assigned NCR owner (ERGO-only to set, see {@code NcrService}) - also this record's self-approval signal
	 * (judgment call, see the class comment): the owner may not also approve this NCR's own closure. */
	Long ownerUserId;

	LocalDate targetClosureDate;

	LocalDate closureDate;

	/** ERGO-internal judgment (see {@code NcrService}'s class comment): dropped from both external views. */
	String effectivenessStatus;

	/** Security review H2: gates the customer's own view only (mirrors {@code Inspection.customerVisible}) - false
	 * until an ERGO editor turns it on, so content raised against/by a supplier is not shared with a tagged customer
	 * the moment it exists, unreviewed. */
	boolean customerVisible;

	@Enumerated(EnumType.STRING)
	NcrStatus currentStatus;

	@Column(updatable = false)
	Long createdByUserId;

	/** Which company created this row (audit/history only - see security review L4: no longer used to scope
	 * Supplier Quality's edit rights, which are scoped to {@link #supplierCompanyId} instead, matching SRS §10.8's
	 * "linked to that supplier" wording). Set once at creation, never reassignable. */
	@Column(updatable = false)
	Long createdByCompanyId;

	/** Security review H3: who actually moved this NCR into {@code PENDING_CLOSURE_APPROVAL}, set atomically by
	 * {@code NcrService.submitForClosure} - unlike {@link #ownerUserId}, never a separately-editable assignment
	 * field, so it cannot be reassigned away right before closing to dodge the self-approval block. */
	Long closureRequestedByUserId;

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
