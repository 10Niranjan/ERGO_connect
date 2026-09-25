package com.ergo.connect.quotation.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.ergo.connect.quotation.domain.ComparisonDtos.QuoteType;
import com.ergo.connect.quotation.domain.ComparisonDtos.ResponseStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A quote ERGO sent the customer (SRS §13.3.9, with the §13.3.10 ERGO quote fields): a supplier's quote as-is or
 * ERGO's own quote. Everything but the customer's single response is fixed when sent (also enforced by a trigger).
 * {@code customerView} is exactly what the customer sees; the {@code *Hidden} margin never leaves ERGO.
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

	@Column(updatable = false)
	Long customerCompanyId;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	QuoteType quoteType;

	/** The supplier quote (invitation) it is based on, and which of its versions. */
	@Column(updatable = false)
	Long assignmentId;

	@Column(updatable = false)
	Integer supplierQuoteVersion;

	@Column(updatable = false)
	Long currencyId;

	@Column(updatable = false)
	BigDecimal quotedProductionPiecePrice;

	@Column(updatable = false)
	BigDecimal quotedPpapPiecePrice;

	@Column(updatable = false)
	BigDecimal quotedToolingCost;

	@Column(updatable = false)
	BigDecimal marginAmountHidden;

	@Column(updatable = false)
	BigDecimal marginPercentageHidden;

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
