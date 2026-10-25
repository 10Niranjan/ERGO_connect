package com.ergo.connect.quotation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The supplier's working copy of its quote (SRS §13.3.4). Deliberately not audited field by field: drafts are the
 * supplier's private work, and every submission is kept whole and immutable in supplier_quote_versions, which is a
 * stronger record than a field log. Submissions, re-opens and reasons are audited on the {@link Invitation}.
 */
@Entity
@Table(name = "supplier_quotes")
@AuditExclude
class SupplierQuote {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "supplier_quote_id")
	Long id;

	@Column(updatable = false)
	Long assignmentId;

	String quoteReference;

	LocalDate validityDate;

	Long currencyId;

	Long incotermId;

	Long paymentTermId;

	BigDecimal toolingCostTotal;

	BigDecimal ppapPiecePrice;

	BigDecimal productionPiecePrice;

	BigDecimal rawMaterialCost;

	BigDecimal processingCost;

	BigDecimal overheadCost;

	BigDecimal packagingCost;

	BigDecimal profit;

	Integer moq;

	Integer materialLeadTimeWeeks;

	Integer toolingLeadTimeDays;

	Integer sampleLeadTimeDays;

	Integer productionLeadTimeDays;

	String assumptionsDeviations;

	String remarks;

	/** Number of submissions so far. */
	int currentVersion;

	Instant submittedAt;

	Long submittedByUserId;

	String reopenReason;

	Long reopenedByUserId;

	Instant reopenedAt;

	/** Optimistic lock: two users of one supplier saving or submitting at once cannot both win. */
	@Version
	long rowVersion;

	@ElementCollection
	@CollectionTable(name = "supplier_quote_tooling", joinColumns = @JoinColumn(name = "supplier_quote_id"))
	@OrderColumn(name = "sequence_no")
	List<ToolingLine> tooling = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "supplier_quote_process_steps", joinColumns = @JoinColumn(name = "supplier_quote_id"))
	@OrderColumn(name = "sequence_no")
	List<QuoteStep> processSteps = new ArrayList<>();

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

	@UpdateTimestamp
	Instant updatedAt;

}
