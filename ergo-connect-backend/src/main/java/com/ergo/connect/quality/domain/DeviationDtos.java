package com.ergo.connect.quality.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.ergo.connect.common.security.Role;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Deviation request and response shapes (SRS §13.6.4, §14.7), Phase 5c - a separate file from {@link QualityDtos}
 * (whose own javadoc already covers Inspection/NCR) purely for size; same per-audience split and reasoning: ERGO
 * gets {@link DeviationRow}/{@link DeviationView} (both companies, both approval rows with approver identity), a
 * supplier gets {@link SupplierDeviationRow}/{@link SupplierDeviationView} (no field names or identifies the
 * customer, no approver identity), a customer gets {@link CustomerDeviationRow}/{@link CustomerDeviationView} (no
 * field names or identifies the supplier, no approver identity). {@link QualityDtos.ReasonForm} is reused as-is for
 * every decide call (same package, no reason to duplicate it).
 */
public final class DeviationDtos {

	private DeviationDtos() {
	}

	/** Create (SRS §13.6.4). {@code linkedModule} must be NPD or OFP (service-validated against the V19 CHECK
	 * constraint before it ever reaches the database). {@code customerCompanyId}/{@code supplierCompanyId} are never
	 * client-supplied at all - both companies are always derived from the linked NPD/OFP record server-side (same
	 * security review M5 precedent {@code NcrService.applyLinkedCompanies} established). */
	public record DeviationForm(@NotNull LinkedModule linkedModule, @NotNull @Positive Long linkedRecordId,
			Long inspectionId, @NotBlank @Size(max = 100) String partNumber,
			@NotBlank @Size(max = 4000) String deviationDescription, @Size(max = 4000) String qualityImpact,
			@Size(max = 4000) String technicalImpact, boolean customerApprovalRequiredFlag,
			@NotNull @Positive @Digits(integer = 14, fraction = 2) BigDecimal validityQuantity,
			// Security review L1: a deviation's validity cannot already be expired the moment it is raised.
			@NotNull @FutureOrPresent LocalDate validityEndDate) {
	}

	/** Full replace (every other form in this codebase), used for EDIT only. {@code customerApprovalRequiredFlag}/
	 * {@code linkedModule}/{@code linkedRecordId}/{@code inspectionId} are absent - locked after creation ({@link
	 * Deviation}'s own javadoc explains why). {@code customerVisible}/{@code supplierVisible} are ERGO-only in effect
	 * ({@code DeviationService.edit}, matching {@code NcrUpdateForm.customerVisible}'s own H2 precedent): a non-ERGO
	 * editor's values are ignored. */
	public record DeviationUpdateForm(@NotBlank @Size(max = 100) String partNumber,
			@NotBlank @Size(max = 4000) String deviationDescription, @Size(max = 4000) String qualityImpact,
			@Size(max = 4000) String technicalImpact,
			@NotNull @Positive @Digits(integer = 14, fraction = 2) BigDecimal validityQuantity,
			@NotNull LocalDate validityEndDate, boolean customerVisible, boolean supplierVisible) {
	}

	/** Buttons the caller may use now, computed by the same rules that enforce them ({@code DeviationService.allowed}). */
	public enum DeviationAction {
		EDIT, START_REVIEW, APPROVE, REJECT, SEND_BACK, CLOSE
	}

	/** ERGO's own approval-row view: full detail including who decided it. */
	public record ErgoDeviationApprovalView(Role approverRole, DeviationDecision decision, Long approverUserId,
			String approverName, String comment, Instant decidedAt, Instant createdAt) {
	}

	/** Shared by both external audiences (judgment call, class comment): {@code approverRole}/{@code decision} say
	 * nothing about which company or person is on the other side - only generic role labels (ERGO_QUALITY/
	 * CUSTOMER_QUALITY) already known to exist on every deviation with {@code customerApprovalRequiredFlag} set - so
	 * sharing them with both the supplier and the customer does not break two-way anonymity the way an approver's
	 * name would. No {@code approverUserId}/{@code approverName} (ERGO/customer staff identity stays internal, same
	 * reasoning {@code Ncr}'s {@code owner} is dropped externally).
	 * <p>
	 * Security review H3 fix: {@code comment} is free text and, unlike the role label, can name the other party (a
	 * customer's send-back reason naming their own plant, ERGO's internal review note naming the supplier) - so it is
	 * never the full comment for every row. {@code DeviationService.view} nulls out the one row whose comment belongs
	 * to the audience's own counterpart: a supplier never sees the {@code CUSTOMER_QUALITY} row's comment, a customer
	 * never sees the {@code ERGO_QUALITY} row's comment. Each audience still sees the comment on "its own" row (the
	 * one an ERGO reviewer writes about/to the supplier, the one the customer writes itself). */
	public record ExternalDeviationApprovalView(Role approverRole, DeviationDecision decision, String comment,
			Instant decidedAt, Instant createdAt) {
	}

	/** Per-audience list row (same sealed-interface pattern as {@code QualityDtos.NcrSummary}). */
	public sealed interface DeviationSummary permits DeviationRow, SupplierDeviationRow, CustomerDeviationRow {
	}

	/** Per-audience detail view, see {@link DeviationSummary}. */
	public sealed interface DeviationDetail permits DeviationView, SupplierDeviationView, CustomerDeviationView {
	}

	/** ERGO's list row: both companies. */
	public record DeviationRow(long id, String deviationNumber, LinkedModule linkedModule, long linkedRecordId,
			String customer, String supplier, String partNumber, DeviationStatus currentStatus,
			boolean customerApprovalRequiredFlag, LocalDate validityEndDate) implements DeviationSummary {
	}

	/** ERGO's unrestricted detail view: every field, both companies, both approval rows with approver identity. */
	public record DeviationView(long id, String deviationNumber, LinkedModule linkedModule, long linkedRecordId,
			Long inspectionId, Long customerCompanyId, String customer, Long supplierCompanyId, String supplier,
			String partNumber, String deviationDescription, Long requestedByUserId, String requestedBy,
			String qualityImpact, String technicalImpact, boolean customerApprovalRequiredFlag,
			BigDecimal validityQuantity, LocalDate validityEndDate, DeviationStatus currentStatus,
			boolean customerVisible, boolean supplierVisible, List<ErgoDeviationApprovalView> approvals,
			Set<DeviationAction> actions, Instant createdAt, Instant updatedAt) implements DeviationDetail {
	}

	/** Two-way anonymity: no field names or identifies the customer. */
	public record SupplierDeviationRow(long id, String deviationNumber, LinkedModule linkedModule,
			long linkedRecordId, String partNumber, DeviationStatus currentStatus,
			boolean customerApprovalRequiredFlag, LocalDate validityEndDate) implements DeviationSummary {
	}

	public record SupplierDeviationView(long id, String deviationNumber, LinkedModule linkedModule,
			long linkedRecordId, String partNumber, String deviationDescription, String qualityImpact,
			String technicalImpact, boolean customerApprovalRequiredFlag, BigDecimal validityQuantity,
			LocalDate validityEndDate, DeviationStatus currentStatus, List<ExternalDeviationApprovalView> approvals,
			Set<DeviationAction> actions, Instant createdAt, Instant updatedAt) implements DeviationDetail {
	}

	/** Two-way anonymity: no field names or identifies the supplier. */
	public record CustomerDeviationRow(long id, String deviationNumber, LinkedModule linkedModule,
			long linkedRecordId, String partNumber, DeviationStatus currentStatus,
			boolean customerApprovalRequiredFlag, LocalDate validityEndDate) implements DeviationSummary {
	}

	public record CustomerDeviationView(long id, String deviationNumber, LinkedModule linkedModule,
			long linkedRecordId, String partNumber, String deviationDescription, String qualityImpact,
			String technicalImpact, boolean customerApprovalRequiredFlag, BigDecimal validityQuantity,
			LocalDate validityEndDate, DeviationStatus currentStatus, List<ExternalDeviationApprovalView> approvals,
			Set<DeviationAction> actions, Instant createdAt, Instant updatedAt) implements DeviationDetail {
	}

}
