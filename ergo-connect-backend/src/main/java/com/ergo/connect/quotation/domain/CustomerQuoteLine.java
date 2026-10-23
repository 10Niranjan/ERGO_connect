package com.ergo.connect.quotation.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One part of a generated quotation, with the supplier quote version it is based on and ERGO's hidden margin. Written
 * once (a trigger keeps it append-only); ERGO-internal, never shown to the customer.
 */
@Entity
@Table(name = "customer_quote_lines")
class CustomerQuoteLine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "customer_quote_line_id")
	Long id;

	@Column(updatable = false)
	Long submissionId;

	@Column(updatable = false)
	Long assignmentId;

	@Column(updatable = false)
	Long rfqItemId;

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

}
