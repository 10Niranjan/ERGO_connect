package com.ergo.connect.quotation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.quotation.domain.QuoteStep.SourcingType;
import com.ergo.connect.rfq.api.RfqApi.SupplierRfq;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Supplier quote shapes. Suppliers and ERGO each get their own response type. */
public final class QuoteDtos {

	private QuoteDtos() {
	}

	/** SRS §5.4 quote fields. The tooling total is the sum of the tooling lines when lines are given. */
	public record QuoteForm(@Size(max = 50) String quoteReference, LocalDate validityDate, Long currencyId,
			Long incotermId, Long paymentTermId, @PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal toolingCostTotal,
			@Size(max = 50) List<@Valid @NotNull ToolingLine> tooling,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal ppapPiecePrice,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal productionPiecePrice,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal rawMaterialCost,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal processingCost,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal overheadCost,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal packagingCost,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal profit, @Positive Integer moq,
			@PositiveOrZero Integer materialLeadTimeWeeks, @PositiveOrZero Integer toolingLeadTimeDays,
			@PositiveOrZero Integer sampleLeadTimeDays, @PositiveOrZero Integer productionLeadTimeDays,
			@Size(max = 50) List<@Valid @NotNull QuoteStep> processSteps, @Size(max = 4000) String assumptionsDeviations,
			@Size(max = 4000) String remarks) {
	}

	public record InviteForm(@NotEmpty Set<@NotNull Long> supplierIds,
			@NotNull @FutureOrPresent LocalDate responseDueDate) {
	}

	public record CommentForm(@Size(max = 2000) String comment) {
	}

	/** Buttons the caller may use now, computed by the same rules that enforce them. */
	public enum QuoteAction {
		EDIT, SUBMIT, REGRET, REQUEST_CLARIFICATION, ANSWER_CLARIFICATION, REOPEN, UPLOAD, EVALUATE, SHORTLIST,
		SELECT_FOR_CUSTOMER, MARK_NOT_SELECTED, CLEAR_DECISION, SEND_TO_CUSTOMER
	}

	/** ERGO's comparison decisions on a submitted quote (the same names as their actions). */
	public enum Decision {
		SHORTLIST, SELECT_FOR_CUSTOMER, MARK_NOT_SELECTED, CLEAR_DECISION
	}

	/** {@code comment} is required for MARK_NOT_SELECTED (SRS §14.1: a rejection needs a reason) and audited only. */
	public record DecisionForm(@NotNull Decision decision, @Size(max = 2000) String comment) {
	}

	public record Named(long id, String code, String name) {
	}

	public record StepView(Named process, String stepName, BigDecimal cycleTimeSeconds, SourcingType sourcingType,
			String outsourcedSupplierName, String remarks) {
	}

	public record CostBreakdown(BigDecimal rawMaterialCost, BigDecimal processingCost, BigDecimal overheadCost,
			BigDecimal packagingCost, BigDecimal profit) {
	}

	/** The quote's content. Also the snapshot stored with each submitted version. */
	public record QuoteData(String quoteReference, LocalDate validityDate, Named currency, Named incoterm,
			Named paymentTerm, BigDecimal toolingCostTotal, List<ToolingLine> tooling, BigDecimal ppapPiecePrice,
			BigDecimal productionPiecePrice, CostBreakdown costs, Integer moq, Integer materialLeadTimeWeeks,
			Integer toolingLeadTimeDays, Integer sampleLeadTimeDays, Integer productionLeadTimeDays,
			List<StepView> processSteps, String assumptionsDeviations, String remarks) {
	}

	public sealed interface QuoteView permits ErgoQuoteView, SupplierQuoteView, SupplierAssignedRfqView {
	}

	/** The supplier's page: its invitation, the supplier-safe RFQ and its own quote (null until first saved). */
	public record SupplierQuoteView(String audience, long id, QuoteStatus status, LocalDate responseDueDate,
			SupplierRfq rfq, QuoteData quote, int version, Instant submittedAt, String reopenReason,
			String clarificationQuestion, String clarificationAnswer, String regretReason, Set<QuoteAction> actions)
			implements QuoteView {
	}

	/**
	 * What a supplier role with RFQ access but none on Quotation (the role sheet) sees of an assignment: the RFQ and its
	 * due date, never the quote's status, versions or correspondence.
	 */
	public record SupplierAssignedRfqView(String audience, long id, LocalDate responseDueDate, SupplierRfq rfq,
			Set<QuoteAction> actions) implements QuoteView {
	}

	public record ErgoQuoteView(String audience, long id, QuoteStatus status, LocalDate responseDueDate, long rfqId,
			String rfqNumber, Named supplier, String invitedBy, Instant invitedAt, QuoteData quote, int version,
			Instant submittedAt, String reopenReason, String clarificationQuestion, String clarificationAnswer,
			String regretReason, Set<QuoteAction> actions) implements QuoteView {
	}

	/** Supplier's "Assigned RFQs" list. */
	public record SupplierQuoteRow(long id, String rfqNumber, String partNumber, String revision, String description,
			LocalDate responseDueDate, QuoteStatus status, int version, Instant submittedAt, boolean quotingOpen) {
	}

	/** The "Assigned RFQs" row for a supplier role without Quotation access: no quote status, version or submit time. */
	public record SupplierAssignedRfqRow(long id, String rfqNumber, String partNumber, String revision,
			String description, LocalDate responseDueDate, boolean quotingOpen) {
	}

	/** One supplier on an RFQ's suppliers tab (ERGO). */
	public record ErgoQuoteRow(long id, Named supplier, QuoteStatus status, LocalDate responseDueDate, int version,
			Instant submittedAt, String currency, BigDecimal productionPiecePrice, BigDecimal toolingCostTotal,
			Integer moq, Integer productionLeadTimeDays) {
	}

	public record RfqSuppliers(boolean canInvite, List<ErgoQuoteRow> quotes) {
	}

	/** SRS §12.7.3: each submitted version as it was sent (with its files), who sent it and why it was re-opened. */
	public record VersionView(int version, Instant submittedAt, String submittedBy, String reopenReason,
			String reopenedBy, Instant reopenedAt, QuoteData quote, List<String> files) {
	}

	/** Files on the supplier's page: RFQ files ERGO shared with suppliers, and files attached to this quote. */
	public record DocumentView(long id, String source, String fileName, long size, Visibility visibility,
			boolean ownCompany, boolean canDelete, Instant createdAt) {
	}

}
