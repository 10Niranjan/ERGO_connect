package com.ergo.connect.rfq.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.rfq.domain.ProcessStep.SourcingType;
import com.ergo.connect.rfq.domain.RfqStatus.CustomerStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * RFQ request and response shapes. Each audience gets its own response type (CLAUDE.md non-negotiable): internal
 * notes, owner and the internal status exist only on the ERGO types.
 */
public final class RfqDtos {

	private RfqDtos() {
	}

	/** Create / edit. {@code customerCompanyId} and {@code internalNotes} are used for ERGO callers only. */
	public record RfqForm(Long customerCompanyId, @NotBlank @Size(max = 100) String partNumber,
			@Size(max = 30) String revision, @Size(max = 1000) String description, @Positive Integer eau,
			@PositiveOrZero @Digits(integer = 8, fraction = 4) BigDecimal netWeightKg,
			@PositiveOrZero @Digits(integer = 8, fraction = 4) BigDecimal grossWeightKg, Long materialId,
			@Size(max = 500) String materialSpec, Long coatingId, @Size(max = 500) String coatingSpec,
			LocalDate targetQuoteDate, Priority priority, @NotNull Long currencyId,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal targetToolingCost,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal targetPpapPiecePrice,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal targetProductionPiecePrice,
			Long paymentTermId, @PositiveOrZero Integer materialLeadTimeWeeks, @PositiveOrZero Integer toolingLeadTimeDays,
			@PositiveOrZero Integer sampleLeadTimeDays, @PositiveOrZero Integer productionLeadTimeDays,
			@Positive Integer moq, @Size(max = 50) List<@Valid @NotNull ProcessStep> processSteps,
			@Size(max = 4000) String customerRemarks, @Size(max = 4000) String internalNotes) {
	}

	public record CommentForm(@Size(max = 2000) String comment) {
	}

	public record NotesForm(@Size(max = 4000) String internalNotes) {
	}

	public record VisibilityForm(@NotNull Visibility visibility) {
	}

	/** Buttons the caller may use now, computed by the same rules that enforce them. */
	public enum RfqAction {
		EDIT, EDIT_NOTES, SUBMIT, START_REVIEW, REQUEST_CLARIFICATION, VALIDATE, REOPEN, CANCEL, CLOSE, CLONE, UPLOAD
	}

	public record Named(long id, String code, String name) {
	}

	public record StepView(Named process, String stepName, BigDecimal cycleTimeSeconds, SourcingType sourcingType,
			String outsourcedSupplierName, String remarks) {
	}

	/** Part and technical data: what everyone involved may see. */
	public record PartView(String partNumber, String revision, String description, Integer eau,
			BigDecimal netWeightKg, BigDecimal grossWeightKg, Named material, String materialSpec, Named coating,
			String coatingSpec, LocalDate targetQuoteDate, Priority priority, List<StepView> processSteps) {
	}

	/** The customer's commercial targets: ERGO and that customer only, never suppliers. */
	public record TargetsView(Named currency, BigDecimal targetToolingCost, BigDecimal targetPpapPiecePrice,
			BigDecimal targetProductionPiecePrice, Named paymentTerm, Integer materialLeadTimeWeeks,
			Integer toolingLeadTimeDays, Integer sampleLeadTimeDays, Integer productionLeadTimeDays, Integer moq) {
	}

	public sealed interface RfqView permits ErgoRfqView, CustomerRfqView {
	}

	public record ErgoRfqView(String audience, long id, String number, RfqStatus status, SourceType sourceType,
			Named customer, String createdBy, String owner, PartView part, TargetsView targets, String customerRemarks,
			String internalNotes, String clarificationQuestion, String clarificationAnswer, Set<RfqAction> actions,
			Instant createdAt, Instant updatedAt) implements RfqView {
	}

	public record CustomerRfqView(String audience, long id, String number, CustomerStatus status, PartView part,
			TargetsView targets, String customerRemarks, String clarificationQuestion, String clarificationAnswer,
			Set<RfqAction> actions, Instant createdAt, Instant updatedAt) implements RfqView {
	}

	public sealed interface RfqRow permits ErgoRfqRow, CustomerRfqRow {
	}

	public record ErgoRfqRow(long id, String number, RfqStatus status, String customer, String partNumber,
			String revision, String description, LocalDate targetQuoteDate, Priority priority, String owner,
			Instant createdAt) implements RfqRow {
	}

	public record CustomerRfqRow(long id, String number, CustomerStatus status, String partNumber, String revision,
			String description, LocalDate targetQuoteDate, Priority priority, Instant createdAt) implements RfqRow {
	}

	public record DocumentView(long id, String fileName, long size, Visibility visibility, boolean ownCompany,
			boolean canDelete, Instant createdAt) {
	}

}
