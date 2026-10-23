package com.ergo.connect.quotation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.ergo.connect.quotation.domain.QuoteDtos.Named;
import com.ergo.connect.rfq.api.RfqApi.CustomerResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The quotation ERGO generates for the customer (flow doc §4) and the customer's answer (§5), one type per audience.
 * A quotation has a line per part; the customer never sees a supplier, a supplier quote or ERGO's margin.
 */
public final class CustomerQuoteDtos {

	private CustomerQuoteDtos() {
	}

	/** SRS §14.4: waiting for the customer's review, then the customer's answer (on hold is answered again). */
	public enum ResponseStatus {
		WAITING, ON_HOLD, APPROVED, REVISION_REQUESTED, DECLINED;

		/** Still waiting for the customer's final answer (an on-hold quotation is not answered yet). */
		public static final Set<ResponseStatus> OPEN = EnumSet.of(WAITING, ON_HOLD);

		/** The status each part of the quotation takes on. */
		QuoteStatus partStatus() {
			return switch (this) {
				case WAITING -> QuoteStatus.WITH_CUSTOMER;
				case ON_HOLD -> QuoteStatus.CUSTOMER_ON_HOLD;
				case APPROVED -> QuoteStatus.CUSTOMER_APPROVED;
				case REVISION_REQUESTED -> QuoteStatus.CUSTOMER_CORRECTION;
				case DECLINED -> QuoteStatus.CUSTOMER_REJECTED;
			};
		}
	}

	public enum CustomerQuoteAction {
		APPROVE, REQUEST_REVISION, DECLINE, PUT_ON_HOLD
	}

	/** ERGO's quotation terms for one part (SRS §13.3.10), usually prefilled from the part's accepted supplier quote. */
	public record ErgoQuoteForm(@NotNull Long currencyId,
			@NotNull @PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal productionPiecePrice,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal ppapPiecePrice,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal toolingCost, Long paymentTermId,
			Long incotermId, @Positive Integer moq, @PositiveOrZero Integer materialLeadTimeWeeks,
			@PositiveOrZero Integer toolingLeadTimeDays, @PositiveOrZero Integer sampleLeadTimeDays,
			@PositiveOrZero Integer productionLeadTimeDays, @NotNull LocalDate validityDate) {
	}

	/** One part of the quotation: {@code quoteId} is the part's accepted supplier quote it is based on. */
	public record GenerateLine(@NotNull Long quoteId, @NotNull @Valid ErgoQuoteForm ergoQuote) {
	}

	/** "Generate quotation for customer": the parts to quote, each with ERGO's own terms. */
	public record GenerateForm(@NotEmpty @Size(max = 100) List<@Valid @NotNull GenerateLine> lines,
			@Size(max = 4000) String remarks) {
	}

	/** Every decision carries a comment (flow doc §5, key rules). */
	public record ResponseForm(@NotNull CustomerResponse response, @Size(max = 2000) String comment) {
	}

	/** What the customer sees of ERGO's terms for a part: ERGO's terms only, never the supplier or the margin. */
	public record ErgoQuoteTerms(Named currency, BigDecimal productionPiecePrice, BigDecimal ppapPiecePrice,
			BigDecimal toolingCost, Named paymentTerm, Named incoterm, Integer moq, Integer materialLeadTimeWeeks,
			Integer toolingLeadTimeDays, Integer sampleLeadTimeDays, Integer productionLeadTimeDays,
			LocalDate validityDate) {
	}

	/** A part of the quotation as the customer received it. */
	public record CustomerLine(long rfqItemId, int lineNo, String partNumber, String revision, String description,
			ErgoQuoteTerms ergoQuote) {
	}

	/** Exactly what the customer receives, frozen when generated: ERGO's lines, never a supplier's quote. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CustomerQuoteContent(String remarks, List<CustomerLine> lines) {
	}

	public sealed interface CustomerQuotesView permits ErgoCustomerQuotes, CustomerQuotes {
	}

	/** ERGO's record of one part of a quotation: the supplier quote behind it and ERGO's margin. */
	public record ErgoLineBasis(long quoteId, long rfqItemId, String partNumber, Named supplier, int supplierVersion,
			BigDecimal marginAmount, BigDecimal marginPercentage) {
	}

	/** ERGO's record of a generated quotation: what the customer saw plus its basis and ERGO's margin. */
	public record ErgoCustomerQuoteView(long id, int version, CustomerQuoteContent content, List<ErgoLineBasis> basis,
			String sentBy, Instant sentAt, ResponseStatus status, String respondedBy, Instant respondedAt,
			String responseComment) {
	}

	public record ErgoCustomerQuotes(String audience, List<ErgoCustomerQuoteView> quotations)
			implements CustomerQuotesView {
	}

	/** The customer's view (SRS §12.7.2): only quotations ERGO generated for this customer. */
	public record CustomerQuoteView(long id, int version, Instant sentAt, CustomerQuoteContent content,
			ResponseStatus status, String respondedBy, Instant respondedAt, String responseComment,
			Set<CustomerQuoteAction> actions) {
	}

	public record CustomerQuotes(String audience, List<CustomerQuoteView> quotations) implements CustomerQuotesView {
	}

}
