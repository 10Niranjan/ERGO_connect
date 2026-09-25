package com.ergo.connect.quotation.domain;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import com.ergo.connect.common.audit.AuditExclude;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** ERGO's written assessment of one RFQ's quotes (SRS §13.3.7). ERGO-internal; audited field by field. */
@Entity
@Table(name = "quote_comparisons")
class Comparison {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "quote_comparison_id")
	Long id;

	@Column(updatable = false)
	Long rfqId;

	String internalSummary;

	String recommendationSummary;

	/** Last saved by and at: every audit row records these anyway. */
	@AuditExclude
	Long preparedByUserId;

	@AuditExclude
	Instant preparedAt;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

}
