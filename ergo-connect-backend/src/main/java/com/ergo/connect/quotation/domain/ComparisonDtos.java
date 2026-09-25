package com.ergo.connect.quotation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.ergo.connect.quotation.domain.QuoteDtos.CostBreakdown;
import com.ergo.connect.quotation.domain.QuoteDtos.Named;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteAction;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteData;
import com.ergo.connect.rfq.api.RfqApi.CustomerResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Phase 2c shapes: ERGO's comparison (ERGO only) and the quotes sent to the customer (one type per audience). */
public final class ComparisonDtos {

	private ComparisonDtos() {
	}

	// ---------------------------------------------------------------- comparison (ERGO only)

	/** 0-10 each, higher is better (risk 10 = lowest risk); any may be left empty. */
	public record Scores(@Min(0) @Max(10) Integer commercial, @Min(0) @Max(10) Integer technical,
			@Min(0) @Max(10) Integer quality, @Min(0) @Max(10) Integer risk) {
	}

	public record EvaluationForm(@NotNull @Valid Scores scores, @Size(max = 4000) String internalRemarks) {
	}

	public record SummaryForm(@Size(max = 4000) String internalSummary,
			@Size(max = 4000) String recommendationSummary) {
	}

	/** A quote's prices in the RFQ currency at today's rate. All null when ERGO has entered no rate for the pair. */
	public record Converted(BigDecimal rate, BigDecimal productionPiecePrice, BigDecimal ppapPiecePrice,
			BigDecimal toolingCost) {
	}

	/** The customer's targets in the RFQ currency. */
	public record TargetsView(BigDecimal toolingCost, BigDecimal ppapPiecePrice, BigDecimal productionPiecePrice,
			Named paymentTerm, Integer moq, Integer materialLeadTimeWeeks, Integer toolingLeadTimeDays,
			Integer sampleLeadTimeDays, Integer productionLeadTimeDays) {
	}

	/** One submitted quote in the matrix: its last submitted version, converted prices and ERGO's scores. */
	public record ComparisonLine(long quoteId, Named supplier, QuoteStatus status, int version, Instant submittedAt,
			QuoteData quote, Converted converted, Scores scores, BigDecimal totalScore, String internalRemarks,
			Set<QuoteAction> actions) {
	}

	/** SRS §12.7.1 / §17.3 quote comparison. */
	public record ComparisonView(long rfqId, String rfqNumber, Named currency, TargetsView targets,
			String internalSummary, String recommendationSummary, String preparedBy, Instant preparedAt,
			boolean canEdit, List<ComparisonLine> lines) {
	}

	// ---------------------------------------------------------------- quotes sent to the customer

	/** Client decision: per RFQ, ERGO forwards one supplier's quote as-is or sends its own quote. */
	public enum QuoteType {
		SUPPLIER_QUOTE, ERGO_QUOTE
	}

	/** SRS §14.4: waiting for the customer's review, then the customer's answer. */
	public enum ResponseStatus {
		WAITING, APPROVED, REVISION_REQUESTED, DECLINED
	}

	public enum CustomerQuoteAction {
		APPROVE, REQUEST_REVISION, DECLINE
	}

	/** ERGO's own quote (SRS §13.3.10), usually prefilled from the selected supplier quote. */
	public record ErgoQuoteForm(@NotNull Long currencyId,
			@NotNull @PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal productionPiecePrice,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal ppapPiecePrice,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal toolingCost, Long paymentTermId,
			Long incotermId, @Positive Integer moq, @PositiveOrZero Integer materialLeadTimeWeeks,
			@PositiveOrZero Integer toolingLeadTimeDays, @PositiveOrZero Integer sampleLeadTimeDays,
			@PositiveOrZero Integer productionLeadTimeDays, @NotNull LocalDate validityDate) {
	}

	/** {@code quoteId}: the supplier quote selected for the customer; {@code ergoQuote} only for ERGO_QUOTE. */
	public record SendForm(@NotNull Long quoteId, @NotNull QuoteType type, @Valid ErgoQuoteForm ergoQuote,
			@Size(max = 4000) String remarks) {
	}

	/** A comment is required to request a revision or decline (SRS §14.4). */
	public record ResponseForm(@NotNull CustomerResponse response, @Size(max = 2000) String comment) {
	}

	/** What the customer sees of an ERGO quote: ERGO's terms only, never the supplier or the margin. */
	public record ErgoQuoteTerms(Named currency, BigDecimal productionPiecePrice, BigDecimal ppapPiecePrice,
			BigDecimal toolingCost, Named paymentTerm, Named incoterm, Integer moq, Integer materialLeadTimeWeeks,
			Integer toolingLeadTimeDays, Integer sampleLeadTimeDays, Integer productionLeadTimeDays,
			LocalDate validityDate) {
	}

	/** One step of the supplier's route as the customer sees it: no sub-supplier names or free-text remarks. */
	public record CustomerProcessStep(String processName, String stepName, BigDecimal cycleTimeSeconds,
			String sourcingType) {
	}

	/**
	 * What the customer sees of a forwarded supplier quote (client decision: the supplier's name, prices and cost
	 * breakdown). Deliberately narrower than {@link QuoteData}: leaves out the supplier's own quote reference,
	 * assumptions, remarks, tooling-line remarks and process-step sub-suppliers/remarks — the supplier wrote those for
	 * ERGO, not for another company to read.
	 */
	public record CustomerSupplierQuote(LocalDate validityDate, Named currency, Named incoterm, Named paymentTerm,
			BigDecimal toolingCostTotal, BigDecimal ppapPiecePrice, BigDecimal productionPiecePrice,
			CostBreakdown costs, Integer moq, Integer materialLeadTimeWeeks, Integer toolingLeadTimeDays,
			Integer sampleLeadTimeDays, Integer productionLeadTimeDays, List<CustomerProcessStep> processSteps) {
	}

	/**
	 * Exactly what the customer receives, frozen when sent. A supplier quote carries the supplier's name and its
	 * client-approved fields (client decision); an ERGO quote carries ERGO's terms only. Absent parts are left out of
	 * the JSON.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CustomerQuoteContent(QuoteType type, String supplierName, CustomerSupplierQuote supplierQuote,
			ErgoQuoteTerms ergoQuote, String remarks) {
	}

	public sealed interface CustomerQuotesView permits ErgoCustomerQuotes, CustomerQuotes {
	}

	/** ERGO's record of a sent quote: what the customer saw plus its basis and ERGO's margin. */
	public record ErgoCustomerQuoteView(long id, QuoteType type, long quoteId, Named supplier, int supplierVersion,
			CustomerQuoteContent content, BigDecimal marginAmount, BigDecimal marginPercentage, String sentBy,
			Instant sentAt, ResponseStatus status, String respondedBy, Instant respondedAt, String responseComment) {
	}

	public record ErgoCustomerQuotes(String audience, List<ErgoCustomerQuoteView> quotes) implements CustomerQuotesView {
	}

	/** The customer's view (SRS §12.7.2): only quotes ERGO sent to this customer. */
	public record CustomerQuoteView(long id, Instant sentAt, CustomerQuoteContent content, ResponseStatus status,
			String respondedBy, Instant respondedAt, String responseComment, Set<CustomerQuoteAction> actions) {
	}

	public record CustomerQuotes(String audience, List<CustomerQuoteView> quotes) implements CustomerQuotesView {
	}

}
