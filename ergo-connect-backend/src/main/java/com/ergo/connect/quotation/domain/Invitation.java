package com.ergo.connect.quotation.domain;

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
 * A supplier invited to quote for an RFQ (SRS §13.3.3 rfq_supplier_assignments). Its status is the supplier quote
 * status; every change is audited automatically, reasons included.
 */
@Entity
@Table(name = "rfq_supplier_assignments")
class Invitation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "assignment_id")
	Long id;

	@Column(updatable = false)
	Long rfqId;

	@Column(updatable = false)
	Long supplierCompanyId;

	@Column(updatable = false)
	Long assignedByUserId;

	LocalDate responseDueDate;

	@Enumerated(EnumType.STRING)
	QuoteStatus status;

	@Enumerated(EnumType.STRING)
	QuoteStatus statusBeforeClarification;

	/** SRS quote_edit_open_flag: open until the first submission, then only when ERGO re-opens the quote. */
	boolean quoteEditOpen = true;

	String regretReason;

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
