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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * RFQ request and response shapes. Each audience gets its own response type (CLAUDE.md non-negotiable): internal
 * notes, owner and the internal status exist only on the ERGO types. An RFQ is a header plus its items (parts).
 */
public final class RfqDtos {

	private RfqDtos() {
	}

	/**
	 * One part of the RFQ. {@code id} is set for a part that already exists (its quotes and history stay with it) and
	 * left out for a new one; a part missing from an edit is removed.
	 */
	public record ItemForm(Long id, @NotBlank @Size(max = 100) String partNumber, @Size(max = 30) String revision,
			@Size(max = 1000) String description, @Positive Integer eau,
			@PositiveOrZero @Digits(integer = 8, fraction = 4) BigDecimal netWeightKg,
			@PositiveOrZero @Digits(integer = 8, fraction = 4) BigDecimal grossWeightKg, Long materialId,
			@Size(max = 500) String materialSpec, Long coatingId, @Size(max = 500) String coatingSpec,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal targetToolingCost,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal targetPpapPiecePrice,
			@PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal targetProductionPiecePrice,
			@PositiveOrZero Integer materialLeadTimeWeeks, @PositiveOrZero Integer toolingLeadTimeDays,
			@PositiveOrZero Integer sampleLeadTimeDays, @PositiveOrZero Integer productionLeadTimeDays,
			@Positive Integer moq, @Size(max = 50) List<@Valid @NotNull ProcessStep> processSteps) {
	}

	/** Create / edit. {@code customerCompanyId} and {@code internalNotes} are used for ERGO callers only. */
	public record RfqForm(Long customerCompanyId, LocalDate targetQuoteDate, Priority priority,
			@NotNull Long currencyId, Long paymentTermId, @Size(max = 4000) String customerRemarks,
			@Size(max = 4000) String internalNotes,
			@NotEmpty @Size(max = 100) List<@Valid @NotNull ItemForm> items) {
	}

	public record CommentForm(@Size(max = 2000) String comment) {
	}

	public record NotesForm(@Size(max = 4000) String internalNotes) {
	}

	/** {@code itemIds}: the parts a file is shared with suppliers for (required for SUPPLIER and ALL). */
	public record VisibilityForm(@NotNull Visibility visibility, Set<Long> itemIds) {
	}

	/** Buttons the caller may use now, computed by the same rules that enforce them. */
	public enum RfqAction {
		EDIT, EDIT_NOTES, SUBMIT, START_REVIEW, REQUEST_CLARIFICATION, CANCEL, CLOSE, CLONE, UPLOAD
	}

	public record Named(long id, String code, String name) {
	}

	public record StepView(Named process, String stepName, BigDecimal cycleTimeSeconds, SourcingType sourcingType,
			String outsourcedSupplierName, String remarks) {
	}

	/** The customer's commercial targets for a part: ERGO and that customer only, never suppliers. */
	public record TargetsView(BigDecimal targetToolingCost, BigDecimal targetPpapPiecePrice,
			BigDecimal targetProductionPiecePrice, Integer materialLeadTimeWeeks, Integer toolingLeadTimeDays,
			Integer sampleLeadTimeDays, Integer productionLeadTimeDays, Integer moq) {
	}

	/** A part and its technical and commercial data: what ERGO and the RFQ's own customer may see. */
	public record ItemView(long id, int lineNo, String partNumber, String revision, String description, Integer eau,
			BigDecimal netWeightKg, BigDecimal grossWeightKg, Named material, String materialSpec, Named coating,
			String coatingSpec, TargetsView targets, List<StepView> processSteps) {
	}

	public sealed interface RfqView permits ErgoRfqView, CustomerRfqView {
	}

	public record ErgoRfqView(String audience, long id, String number, RfqStatus status, SourceType sourceType,
			Named customer, String createdBy, String owner, LocalDate targetQuoteDate, Priority priority,
			Named currency, Named paymentTerm, List<ItemView> items, String customerRemarks, String internalNotes,
			String clarificationQuestion, String clarificationAnswer, Set<RfqAction> actions, Instant createdAt,
			Instant updatedAt) implements RfqView {
	}

	public record CustomerRfqView(String audience, long id, String number, CustomerStatus status,
			LocalDate targetQuoteDate, Priority priority, Named currency, Named paymentTerm, List<ItemView> items,
			String customerRemarks, String clarificationQuestion, String clarificationAnswer, Set<RfqAction> actions,
			Instant createdAt, Instant updatedAt) implements RfqView {
	}

	public sealed interface RfqRow permits ErgoRfqRow, CustomerRfqRow {
	}

	/** {@code parts}: the part numbers of the RFQ's items, in order. */
	public record ErgoRfqRow(long id, String number, RfqStatus status, String customer, List<String> parts,
			LocalDate targetQuoteDate, Priority priority, String owner, Instant createdAt) implements RfqRow {
	}

	public record CustomerRfqRow(long id, String number, CustomerStatus status, List<String> parts,
			LocalDate targetQuoteDate, Priority priority, Instant createdAt) implements RfqRow {
	}

	public record DocumentView(long id, String fileName, long size, Visibility visibility, boolean ownCompany,
			boolean canDelete, Instant createdAt, List<Long> itemIds) {
	}

}
