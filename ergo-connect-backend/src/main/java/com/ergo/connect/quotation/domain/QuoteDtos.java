package com.ergo.connect.quotation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.quotation.domain.QuoteStep.SourcingType;
import com.ergo.connect.rfq.api.RfqApi.SupplierRfqItem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Supplier quote shapes. Suppliers and ERGO each get their own response type. A quote is per RFQ part. */
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

	/** One supplier and the parts of the RFQ that go to it (the whole RFQ, or a sub-RFQ of some parts). */
	public record SubRfqForm(@NotNull Long supplierId, @NotEmpty Set<@NotNull Long> itemIds) {
	}

	/** Distribution (flow doc §2): one sub-RFQ per supplier, no part in two of them. */
	public record DistributeForm(@NotEmpty @Size(max = 50) List<@Valid @NotNull SubRfqForm> subRfqs,
			@NotNull @FutureOrPresent LocalDate responseDueDate) {
	}

	public record CommentForm(@Size(max = 2000) String comment) {
	}

	/** Buttons the caller may use now, computed by the same rules that enforce them. */
	public enum QuoteAction {
		EDIT, SUBMIT, REQUEST_REVISION, ACCEPT, RECALL, UPLOAD
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

	/** The RFQ part a quote is about, as ERGO sees it. */
	public record PartRef(long itemId, int lineNo, String partNumber, String revision, String description) {
	}

	public sealed interface QuoteView permits ErgoQuoteView, SupplierQuoteView, SupplierAssignedRfqView {
	}

	/** The supplier's page: its part, the supplier-safe RFQ data and its own quote (null until first saved). */
	public record SupplierQuoteView(String audience, long id, QuoteStatus status, LocalDate responseDueDate,
			int subRfqNo, SupplierRfqItem part, QuoteData quote, int version, Instant submittedAt,
			String revisionReason, Set<QuoteAction> actions) implements QuoteView {
	}

	/**
	 * What a supplier role with RFQ access but none on Quotation (the role sheet) sees of an assignment: the part and
	 * its due date, never the quote's status, versions or correspondence.
	 */
	public record SupplierAssignedRfqView(String audience, long id, LocalDate responseDueDate, int subRfqNo,
			SupplierRfqItem part, Set<QuoteAction> actions) implements QuoteView {
	}

	public record ErgoQuoteView(String audience, long id, QuoteStatus status, LocalDate responseDueDate, long rfqId,
			String rfqNumber, int subRfqNo, Named supplier, PartRef part, String invitedBy, Instant invitedAt,
			QuoteData quote, int version, Instant submittedAt, String revisionReason, String recallReason,
			Set<QuoteAction> actions) implements QuoteView {
	}

	/** Supplier's "Assigned RFQs" list: one row per part sent to it. */
	public record SupplierQuoteRow(long id, String rfqNumber, int subRfqNo, String partNumber, String revision,
			String description, LocalDate responseDueDate, QuoteStatus status, int version, Instant submittedAt,
			boolean quotingOpen) {
	}

	/** The "Assigned RFQs" row for a supplier role without Quotation access: no quote status, version or submit time. */
	public record SupplierAssignedRfqRow(long id, String rfqNumber, int subRfqNo, String partNumber, String revision,
			String description, LocalDate responseDueDate, boolean quotingOpen) {
	}

	/**
	 * One part of an RFQ on ERGO's quotes tab: the part, and - once it is with a supplier - that supplier's quote and
	 * status. The part's status is what the dashboard shows per part. {@code quoteId} is null while unassigned.
	 */
	public record PartRow(PartRef part, Long quoteId, Integer subRfqNo, Named supplier, QuoteStatus status,
			LocalDate responseDueDate, Integer version, Instant submittedAt, String currency,
			BigDecimal productionPiecePrice, BigDecimal toolingCostTotal, Integer moq, Integer productionLeadTimeDays,
			Set<QuoteAction> actions) {
	}

	public record RfqQuotes(boolean canDistribute, List<PartRow> parts) {
	}

	/** A part's status, per audience (ERGO: the full status; customers: where their quotation stands). */
	public record PartStatusRow(long itemId, int lineNo, String partNumber, String revision, String status) {
	}

	/** SRS §12.7.3: each submitted version as it was sent (with its files), who sent it and why it was sent back. */
	public record VersionView(int version, Instant submittedAt, String submittedBy, String reopenReason,
			String reopenedBy, Instant reopenedAt, QuoteData quote, List<String> files) {
	}

	/** Files on the supplier's page: RFQ files ERGO shared with suppliers, and files attached to this quote. */
	public record DocumentView(long id, String source, String fileName, long size, Visibility visibility,
			boolean ownCompany, boolean canDelete, Instant createdAt) {
	}

}
