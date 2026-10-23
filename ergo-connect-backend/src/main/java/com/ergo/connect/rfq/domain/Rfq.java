package com.ergo.connect.rfq.domain;

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
 * A request for quotation (SRS §13.3.1): the request-wide data. The parts it asks for are its {@link RfqItem}s (flow doc §1).
 * Field changes are audited automatically; status changes only happen in RfqService's workflow actions.
 * Package-private fields: only this package touches them.
 */
@Entity
@Table(name = "rfqs")
class Rfq {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "rfq_id")
	Long id;

	@Column(name = "rfq_number", updatable = false)
	String number;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	SourceType sourceType;

	@Column(updatable = false)
	Long customerCompanyId;

	@Column(updatable = false)
	Long createdByUserId;

	Long ownerUserId;

	@Enumerated(EnumType.STRING)
	RfqStatus status;

	@Enumerated(EnumType.STRING)
	Priority priority;

	LocalDate targetQuoteDate;

	Long currencyId;

	Long paymentTermId;

	String customerRemarks;

	String internalNotes;

	String clarificationQuestion;

	String clarificationAnswer;

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
