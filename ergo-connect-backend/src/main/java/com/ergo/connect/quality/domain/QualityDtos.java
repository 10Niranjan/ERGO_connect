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

	// ================================================================== NCR / CAPA (Phase 5b, SRS §13.6.3/.5, §14.7)

	/** Create (SRS §13.6.3). {@code customerCompanyId}/{@code supplierCompanyId}/{@code ownerUserId} are ERGO-only in
	 * effect for anything other than the supplier's own tag (NcrService), same "never trusted from the client
	 * verbatim" rule as {@link InspectionForm}. */
	public record NcrForm(@NotNull LinkedModule linkedModule, @NotNull @Positive Long linkedRecordId,
			@NotBlank @Size(max = 100) String issueCategory, @NotBlank @Size(max = 4000) String issueDescription,
			@NotNull LocalDate targetClosureDate, Long customerCompanyId, Long supplierCompanyId, Long ownerUserId) {
	}

	/** Full replace, used for both EDIT and every forward transition (mirrors {@link InspectionUpdateForm} carrying
	 * the same shape for EDIT and SUBMIT): the progressive fields ({@code containmentAction}/{@code rootCause}/
	 * {@code correctiveAction}/{@code preventiveAction}) are filled the same way regardless of which action call
	 * carries them. {@code effectivenessStatus}/{@code ownerUserId}/{@code customerVisible}/company reassignment are
	 * ERGO-only in effect (NcrService.applyUpdate) - a supplier's own edit leaves them untouched. {@code
	 * customerVisible} follows the same full-replace convention as every other field here (security review H2,
	 * matching {@code InspectionUpdateForm.customerVisible}): an ERGO editor must resend the value it wants to keep,
	 * or it reverts to {@code false} on the next edit that omits it. */
	public record NcrUpdateForm(@NotBlank @Size(max = 100) String issueCategory,
			@NotBlank @Size(max = 4000) String issueDescription, @NotNull LocalDate targetClosureDate,
			@Size(max = 4000) String containmentAction, @Size(max = 4000) String rootCause,
			@Size(max = 4000) String correctiveAction, @Size(max = 4000) String preventiveAction,
			@Size(max = 40) String effectivenessStatus, Long ownerUserId, Long customerCompanyId,
			Long supplierCompanyId, boolean customerVisible) {
	}

	/** Create and full-replace edit for a CAPA action - one shape for both, same reasoning as {@link NcrUpdateForm}
	 * covering both EDIT and SUBMIT for its parent. */
	public record CapaActionForm(@NotNull CapaActionType actionType, @NotBlank @Size(max = 4000) String actionDescription,
			@NotNull LocalDate targetDate, Long ownerUserId) {
	}

	/** Buttons the caller may use now on the NCR itself (same computed-not-guessed pattern as {@link InspectionAction}).
	 * CAPA actions have no equivalent set (matches {@code InspectionDefect} having none either): their own edit/close
	 * calls are gated inline in {@code NcrService}, simple enough not to need a second enum. */
	public enum NcrAction {
		EDIT, MOVE_TO_CONTAINMENT, MOVE_TO_ROOT_CAUSE, MOVE_TO_CAPA, SUBMIT_FOR_CLOSURE, CLOSE, SEND_BACK, REOPEN
	}

	/** ERGO's CAPA action view: full detail including who owns it. */
	public record CapaActionView(long id, CapaActionType actionType, String actionDescription, Long ownerUserId,
			String owner, LocalDate targetDate, CapaActionStatus status, Long evidenceDocumentId,
			String evidenceFileName, Instant closedAt, Instant createdAt) {
	}

	/** Supplier's CAPA action view: no {@code ownerUserId}/{@code owner} (judgment call, see {@code NcrService}'s
	 * class comment - staff identity stays internal, the same reasoning that already drops {@code Inspection}'s
	 * {@code inspectedBy} from both external views). {@code evidenceDocumentId}/{@code evidenceFileName} ARE shared
	 * with the supplier: proof the action raised against (or by) its own NCR was actually completed - the point of
	 * the NCR, not an ERGO-internal note, and either the supplier uploaded the file itself or it is ERGO's own
	 * evidence shared with it as the responsible party. Not shared with the customer (security review H1) - see
	 * {@link CustomerCapaActionView}. */
	public record SupplierCapaActionView(long id, CapaActionType actionType, String actionDescription,
			LocalDate targetDate, CapaActionStatus status, Long evidenceDocumentId, String evidenceFileName,
			Instant closedAt, Instant createdAt) {
	}

	/** Customer's CAPA action view (security review H1): same shape as {@link SupplierCapaActionView} except
	 * evidence - a customer must never be able to download the other party's evidence file (letterhead, signatures),
	 * the same two-way-anonymity rule that makes POD ERGO-only (ARCHITECTURE.md §8, 2026-09-29). {@code
	 * evidenceAttached} tells the customer whether proof exists without naming or exposing it. */
	public record CustomerCapaActionView(long id, CapaActionType actionType, String actionDescription,
			LocalDate targetDate, CapaActionStatus status, boolean evidenceAttached, Instant closedAt,
			Instant createdAt) {
	}

	/** Per-audience list row, see {@link InspectionSummary}. */
	public sealed interface NcrSummary permits NcrRow, SupplierNcrRow, CustomerNcrRow {
	}

	/** One page of the NCR list; {@code total} counts every match, not just this page. */
	public record NcrPage(List<? extends NcrSummary> items, long total, int page, int size) {
	}

	/** Per-audience detail view, see {@link InspectionDetail}. */
	public sealed interface NcrDetail permits NcrView, SupplierNcrView, CustomerNcrView {
	}

	/** ERGO's list row: both companies. */
	public record NcrRow(long id, String ncrNumber, LinkedModule linkedModule, long linkedRecordId, String customer,
			String supplier, String issueCategory, NcrStatus currentStatus, LocalDate targetClosureDate)
			implements NcrSummary {
	}

	/** ERGO's unrestricted detail view: every field, including the ERGO-internal {@code effectivenessStatus} and
	 * {@code owner}, both companies, and the full {@link CapaActionView} list. */
	public record NcrView(long id, String ncrNumber, LinkedModule linkedModule, long linkedRecordId,
			Long customerCompanyId, String customer, Long supplierCompanyId, String supplier, String issueCategory,
			String issueDescription, String containmentAction, String rootCause, String correctiveAction,
			String preventiveAction, Long ownerUserId, String owner, LocalDate targetClosureDate,
			LocalDate closureDate, String effectivenessStatus, NcrStatus currentStatus, boolean customerVisible,
			List<CapaActionView> capaActions, Set<NcrAction> actions, Instant createdAt, Instant updatedAt)
			implements NcrDetail {
	}

	/** Two-way anonymity: no field names or identifies the other party (matches {@link SupplierInspectionRow}). */
	public record SupplierNcrRow(long id, String ncrNumber, LinkedModule linkedModule, long linkedRecordId,
			String issueCategory, NcrStatus currentStatus, LocalDate targetClosureDate) implements NcrSummary {
	}

	/** Supplier's detail view: the shared content fields (this is the point of an NCR raised against/by a supplier),
	 * never {@code effectivenessStatus}/{@code owner} (ERGO-internal, see the class comment), never the customer. */
	public record SupplierNcrView(long id, String ncrNumber, LinkedModule linkedModule, long linkedRecordId,
			String issueCategory, String issueDescription, String containmentAction, String rootCause,
			String correctiveAction, String preventiveAction, LocalDate targetClosureDate, LocalDate closureDate,
			NcrStatus currentStatus, List<SupplierCapaActionView> capaActions, Set<NcrAction> actions,
			Instant createdAt, Instant updatedAt) implements NcrDetail {
	}

	/** Two-way anonymity: no field names or identifies the other party. */
	public record CustomerNcrRow(long id, String ncrNumber, LinkedModule linkedModule, long linkedRecordId,
			String issueCategory, NcrStatus currentStatus, LocalDate targetClosureDate) implements NcrSummary {
	}

	/** Customer's detail view - same shape as {@link SupplierNcrView} and for the same reasons (see its own javadoc)
	 * except its CAPA actions use {@link CustomerCapaActionView} (security review H1), not
	 * {@link SupplierCapaActionView}; {@code actions} is always empty (Customer Quality is view-only, same judgment
	 * call as Inspection's). */
	public record CustomerNcrView(long id, String ncrNumber, LinkedModule linkedModule, long linkedRecordId,
			String issueCategory, String issueDescription, String containmentAction, String rootCause,
			String correctiveAction, String preventiveAction, LocalDate targetClosureDate, LocalDate closureDate,
			NcrStatus currentStatus, List<CustomerCapaActionView> capaActions, Set<NcrAction> actions,
			Instant createdAt, Instant updatedAt) implements NcrDetail {
	}

}
