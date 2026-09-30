package com.ergo.connect.quality.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Inspection request and response shapes. Per-audience responses (extending the OFP/NPD anonymity precedent to
 * Quality, ARCHITECTURE.md §8 "two-way anonymity" decisions of 2026-09-26/29 - the SRS is silent for this module,
 * but {@code inspections} carries both {@code customerCompanyId}/{@code supplierCompanyId} on the same row exactly
 * like {@code orders}, so the same rule applies): ERGO gets {@link InspectionRow}/{@link InspectionView} (both
 * companies), a supplier gets {@link SupplierInspectionRow}/{@link SupplierInspectionView} (no field naming or
 * identifying the customer), and a customer gets {@link CustomerInspectionRow}/{@link CustomerInspectionView} (no
 * field naming or identifying the supplier, and always an empty {@code actions} set - Customer Quality is read-only
 * in 5a, see {@code InspectionService}'s class comment for why).
 */
public final class QualityDtos {

	private QualityDtos() {
	}

	/** Create (SRS §13.6.1). {@code customerCompanyId}/{@code customerVisible} are ERGO-only in effect: a supplier
	 * caller's values are ignored server-side (InspectionService), never trusted from the client. */
	public record InspectionForm(@NotNull LinkedModule linkedModule, @NotNull @Positive Long linkedRecordId,
			@NotBlank @Size(max = 100) String partNumber, @Size(max = 100) String batchLotNo, Long customerCompanyId,
			Long supplierCompanyId, boolean customerVisible) {
	}

	/** Header/result fields an inspection may still change while open (EDIT) or the same fields recorded for the
	 * first time on OPEN -> IN_INSPECTION (SUBMIT) - one shape for both, since "record raw results" (SRS matrix's
	 * Submit right) and "edit" touch the same data, only the status move differs. Full replace, matching every
	 * other form in this codebase (e.g. NPD's updateProject). */
	public record InspectionUpdateForm(@NotBlank @Size(max = 100) String partNumber,
			@Size(max = 100) String batchLotNo, @PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal quantityChecked,
			@PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal quantityRejected, LocalDate inspectionDate,
			@Size(max = 4000) String remarks, boolean customerVisible, Long customerCompanyId, Long supplierCompanyId,
			@Valid List<DefectForm> defects) {
	}

	public record DefectForm(@NotBlank @Size(max = 100) String defectCategory,
			@Size(max = 2000) String defectDescription,
			@NotNull @Positive @Digits(integer = 14, fraction = 2) BigDecimal defectQuantity,
			@NotNull DefectSeverity severity) {
	}

	/** {@code reason} is required for REJECT/RELEASE_WITH_DEVIATION/REOPEN (checked in the service, not by Bean
	 * Validation, since the same shape is also used - optionally - for PASS/CLOSE/SUBMIT's own audit comment). */
	public record ReasonForm(@Size(max = 2000) String reason) {
	}

	/** Buttons the caller may use now, computed by the same rules that enforce them ({@code InspectionService.allowed}). */
	public enum InspectionAction {
		EDIT, SUBMIT, PASS, REJECT, RELEASE_WITH_DEVIATION, CLOSE, REOPEN
	}

	/** Shared by ERGO and the supplier: a defect line names no company either way, and both audiences author/need the
	 * category and description text (security review H1/M4 only drops it for the customer - see
	 * {@link CustomerInspectionDefectView}). */
	public record InspectionDefectView(long id, String defectCategory, String defectDescription,
			BigDecimal defectQuantity, DefectSeverity severity, Instant createdAt) {
	}

	/** Customer's defect view (security review M4): no {@code defectCategory}/{@code defectDescription} - both are
	 * free text ERGO or the supplier typed and could name the other party, the same class of leak as H1's
	 * {@code remarks} fix below and dropped the same way (field removed entirely, not nulled). */
	public record CustomerInspectionDefectView(long id, BigDecimal defectQuantity, DefectSeverity severity,
			Instant createdAt) {
	}

	/** Per-audience list row (same sealed-interface pattern as NpdDtos.NpdView/OfpDtos.OrderSummary). */
	public sealed interface InspectionSummary permits InspectionRow, SupplierInspectionRow, CustomerInspectionRow {
	}

	/** Per-audience detail view, see {@link InspectionSummary}. */
	public sealed interface InspectionDetail permits InspectionView, SupplierInspectionView, CustomerInspectionView {
	}

	/** ERGO's list row: both companies. */
	public record InspectionRow(long id, LinkedModule linkedModule, long linkedRecordId, String customer,
			String supplier, String partNumber, String batchLotNo, ResultStatus resultStatus, LocalDate inspectionDate,
			boolean customerVisible) implements InspectionSummary {
	}

	/** ERGO's unrestricted detail view. */
	public record InspectionView(long id, LinkedModule linkedModule, long linkedRecordId, Long customerCompanyId,
			String customer, Long supplierCompanyId, String supplier, String partNumber, String batchLotNo,
			BigDecimal quantityChecked, BigDecimal quantityRejected, ResultStatus resultStatus, Long inspectedByUserId,
			String inspectedBy, LocalDate inspectionDate, String remarks, boolean customerVisible,
			List<InspectionDefectView> defects, Set<InspectionAction> actions, Instant createdAt, Instant updatedAt)
			implements InspectionDetail {
	}

	/** Two-way anonymity: no field names or identifies the customer, and no ERGO/other-company inspector name
	 * (matches OFP's {@code SupplierOrderView} dropping the ERGO owner). No {@code remarks} (security review H1):
	 * the field is shared free text either side can write, and ERGO's own internal findings must never reach the
	 * supplier - dropped entirely rather than split into a second column, the same "drop it" choice M4 makes for the
	 * customer's defect text below, for consistency between the two H1-class findings. */
	public record SupplierInspectionRow(long id, LinkedModule linkedModule, long linkedRecordId, String partNumber,
			String batchLotNo, ResultStatus resultStatus, LocalDate inspectionDate) implements InspectionSummary {
	}

	public record SupplierInspectionView(long id, LinkedModule linkedModule, long linkedRecordId, String partNumber,
			String batchLotNo, BigDecimal quantityChecked, BigDecimal quantityRejected, ResultStatus resultStatus,
			LocalDate inspectionDate, List<InspectionDefectView> defects, Set<InspectionAction> actions,
			Instant createdAt, Instant updatedAt) implements InspectionDetail {
	}

	/** Two-way anonymity: no field names or identifies the supplier. No {@code remarks} (free text ERGO/the supplier
	 * writes and could name the other party - same H1 precedent as NPD's {@code ExternalStageHistoryView}). Defects
	 * use {@link CustomerInspectionDefectView} (security review M4), not the shared {@link InspectionDefectView}.
	 * {@code actions} is always empty: Customer Quality is read-only in 5a (see {@code InspectionService}'s class
	 * comment). */
	public record CustomerInspectionRow(long id, LinkedModule linkedModule, long linkedRecordId, String partNumber,
			String batchLotNo, ResultStatus resultStatus, LocalDate inspectionDate) implements InspectionSummary {
	}

	public record CustomerInspectionView(long id, LinkedModule linkedModule, long linkedRecordId, String partNumber,
			String batchLotNo, BigDecimal quantityChecked, BigDecimal quantityRejected, ResultStatus resultStatus,
			LocalDate inspectionDate, List<CustomerInspectionDefectView> defects, Set<InspectionAction> actions,
			Instant createdAt, Instant updatedAt) implements InspectionDetail {
	}

}
