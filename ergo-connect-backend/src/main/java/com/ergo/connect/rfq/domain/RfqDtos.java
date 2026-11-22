package com.ergo.connect.rfq.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.ergo.connect.documents.api.DocumentStore.Visibility;
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
	 * One part of the RFQ, as the customer enters it (ERGO only for an RFQ it creates itself). {@code id} is set for a
	 * part that already exists (its quotes and history stay with it) and left out for a new one; a part missing from an
	 * edit is removed. There is no process flow: each supplier gives its own route in its quote (2026-11-22).
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
			@Positive Integer moq, @Size(max = 1000) String remarks) {
	}

	/** Create / edit. {@code customerCompanyId} and {@code internalNotes} are used for ERGO callers only. */
	public record RfqForm(Long customerCompanyId, @Size(max = 200) String title,
			@Size(max = 4000) String description, @Size(max = 4000) String specialInstructions,
			LocalDate targetQuoteDate, Priority priority, @NotNull Long currencyId, Long paymentTermId, @Size(max = 4000) String customerRemarks,
			@Size(max = 4000) String internalNotes,
			@NotEmpty @Size(max = 100) List<@Valid @NotNull ItemForm> items) {
	}

	public record CommentForm(@Size(max = 2000) String comment) {
	}

	/** ERGO's own settings on any RFQ in its scope. {@code null} = unchanged for each; an empty notes text clears the notes. */
	public record NotesForm(@Size(max = 4000) String internalNotes, Priority priority, Long paymentTermId) {
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

	/** The customer's commercial targets for a part: ERGO and that customer only, never suppliers. */
	public record TargetsView(BigDecimal targetToolingCost, BigDecimal targetPpapPiecePrice,
			BigDecimal targetProductionPiecePrice, Integer materialLeadTimeWeeks, Integer toolingLeadTimeDays,
			Integer sampleLeadTimeDays, Integer productionLeadTimeDays, Integer moq) {
	}

	/**
	 * A part and its technical and commercial data, for ERGO and the RFQ's own customer. Every field is the customer's
	 * own entry (2026-11-22: ERGO no longer adds data to a customer's part), so both audiences get this one type.
	 * Anything ERGO-only must go into a separate type, never into this one. Suppliers never get it.
	 */
	public record ItemView(long id, int lineNo, String partNumber, String revision, String description, Integer eau,
			BigDecimal netWeightKg, BigDecimal grossWeightKg, Named material, String materialSpec, Named coating,
			String coatingSpec, TargetsView targets, String remarks, List<ItemFile> files) {
	}

	/** What one slot accepts: extensions (lower case, no dot) and the largest file in bytes. */
	public record AttachmentRule(FileKind kind, List<String> extensions, long maxBytes) {
	}

	/** A file in one of a part's four attachment slots (the customer's own drawings, models, specs and extras). */
	public record ItemFile(long id, FileKind kind, String fileName, long size, boolean ownCompany, boolean canDelete,
			Instant createdAt) {
	}

	public sealed interface RfqView permits ErgoRfqView, CustomerRfqView {
	}

	public record ErgoRfqView(String audience, long id, String number, String supplierRef, RfqStatus status, SourceType sourceType,
			Named customer, String createdBy, String owner, String title, String description,
			String specialInstructions, LocalDate targetQuoteDate, Priority priority,
			Named currency, Named paymentTerm, List<ItemView> items, String customerRemarks, String internalNotes,
			String clarificationQuestion, String clarificationAnswer, Set<RfqAction> actions, Instant createdAt,
			Instant updatedAt) implements RfqView {
	}

	public record CustomerRfqView(String audience, long id, String number, CustomerStatus status, String title,
			String description, String specialInstructions, LocalDate targetQuoteDate, Named currency,
			List<ItemView> items, String customerRemarks, String clarificationQuestion, String clarificationAnswer, Set<RfqAction> actions,
			Instant createdAt, Instant updatedAt) implements RfqView {
	}

	public sealed interface RfqRow permits ErgoRfqRow, CustomerRfqRow {
	}

	/** One page of the RFQ list, newest first. {@code total} counts every RFQ the caller can see that matches the filter. */
	public record RfqPage(List<RfqRow> items, long total, int page, int size) {
	}

	/** {@code parts}: the part numbers of the RFQ's items, in order. */
	public record ErgoRfqRow(long id, String number, RfqStatus status, String title, String customer,
			List<String> parts,
			LocalDate targetQuoteDate, Priority priority, String owner, Instant createdAt) implements RfqRow {
	}

	public record CustomerRfqRow(long id, String number, CustomerStatus status, String title, List<String> parts,
			LocalDate targetQuoteDate, Instant createdAt) implements RfqRow {
	}

	public record DocumentView(long id, String fileName, long size, Visibility visibility, boolean ownCompany,
			boolean canDelete, Instant createdAt, List<Long> itemIds) {
	}

}
