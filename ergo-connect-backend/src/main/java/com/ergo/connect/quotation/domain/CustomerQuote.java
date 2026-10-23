package com.ergo.connect.quotation.domain;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.ergo.connect.quotation.domain.CustomerQuoteDtos.ResponseStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A quotation ERGO generated for the customer (SRS §13.3.9, with the §13.3.10 ERGO quote fields; flow doc §4): a line
 * per part, in {@link CustomerQuoteLine}. Everything but the customer's response is fixed when generated (also
 * enforced by a trigger; an on-hold quotation can still be answered once more). {@code customerView} is exactly what
 * the customer sees; the margin on the lines never leaves ERGO.
 */
@Entity
@Table(name = "customer_quote_submissions")
class CustomerQuote {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "customer_quote_submission_id")
	Long id;

	@Column(updatable = false)
	Long rfqId;

	/** Numbered per RFQ: a correction leads to a new quotation. */
	@Column(updatable = false)
	int versionNo;

	@Column(updatable = false)
	Long customerCompanyId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(updatable = false)
	String customerView;

	@Column(updatable = false)
	Long submittedByUserId;

	@CreationTimestamp
	@Column(updatable = false)
	Instant submittedAt;

	@Enumerated(EnumType.STRING)
	ResponseStatus responseStatus = ResponseStatus.WAITING;

	Long responseByUserId;

	Instant responseAt;

	String responseComment;

}
