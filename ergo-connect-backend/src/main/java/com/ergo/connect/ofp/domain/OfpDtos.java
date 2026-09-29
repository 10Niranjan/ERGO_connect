package com.ergo.connect.ofp.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Order request and response shapes. Per-audience responses from 4b (ARCHITECTURE.md §8 2026-09-29, "4b two-way
 * anonymity"): ERGO gets OrderRow/OrderView, a supplier gets SupplierOrderRow/SupplierOrderView, which have no field
 * that names or identifies the customer. Customer OFP access is still 4c. No price/currency field exists here (SRS
 * §13.5.1 has none).
 */
public final class OfpDtos {

	private OfpDtos() {
	}

	/** Direct standalone create (repeat/running orders): both companies are given explicitly, no NPD/RFQ link. */
	public record OrderForm(@NotNull Long customerCompanyId, @NotNull Long supplierCompanyId,
			@Size(max = 100) String customerPoNo, @Size(max = 100) String supplierPoNo,
			@NotBlank @Size(max = 100) String partNumber, @Size(max = 30) String revision,
			@NotNull @Positive @Digits(integer = 14, fraction = 2) BigDecimal orderedQuantity, LocalDate dueDate,
			LocalDate committedDispatchDate, Long ownerUserId) {
	}

	/** NPD's "Handover to OFP" action: customer/supplier/part/revision come from the NPD project itself (never
	 * client-supplied here); only what the NPD project has no field for is entered directly. */
	public record OrderHandoverForm(@NotNull @Positive @Digits(integer = 14, fraction = 2) BigDecimal orderedQuantity,
			LocalDate dueDate, @Size(max = 100) String customerPoNo, @Size(max = 100) String supplierPoNo,
			Long ownerUserId) {
	}

	/** Header fields an existing order may still change (full replace, matching NPD's updateProject). Company,
	 * part identity, quantity and any NPD/RFQ link are fixed at creation. */
	public record OrderUpdateForm(@Size(max = 100) String customerPoNo, @Size(max = 100) String supplierPoNo,
			LocalDate dueDate, LocalDate committedDispatchDate, Long ownerUserId) {
	}

	public record CommentForm(@Size(max = 2000) String comment) {
	}

	public record MaterialStatusForm(@NotNull MaterialStatus materialStatus, LocalDate materialOrderDate,
			LocalDate expectedReceiptDate, LocalDate actualReceiptDate, @NotNull InspectionStatus inspectionStatus,
			@Size(max = 2000) String remarks) {
	}

	public record ProductionProgressForm(@NotBlank @Size(max = 200) String processStepName,
			@PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal plannedQuantity,
			@PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal completedQuantity,
			@PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal acceptedQuantity,
			@PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal rejectedQuantity,
			@PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal reworkQuantity,
			@NotNull ProductionStatus status, @Size(max = 2000) String blockerReason,
			LocalDate revisedDispatchDate) {
	}

	/** SRS §13.5.4, §10.7 "E**": the assigned supplier (or ERGO OFP/Admin) fills this in. {@code dispatchReadyFlag}
	 * is a readiness claim only — it never moves {@code orders.currentStatus} by itself (ARCHITECTURE.md §8
	 * 2026-09-29); see {@code OrderService.dispatchReady} for the gated ERGO-only transition. */
	public record PackagingDispatchForm(
			@PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal packedQuantity,
			@NotNull PackagingStatus packagingStatus, boolean dispatchReadyFlag, LocalDate dispatchDate,
			@Size(max = 2000) String dispatchRemarks) {
	}

	/** Buttons the caller may use now, computed by the same rules that enforce them. */
	public enum OrderAction {
		EDIT, ADVANCE, HOLD, RELEASE, UPDATE_MATERIAL_STATUS, UPDATE_PRODUCTION_PROGRESS, UPDATE_PACKAGING_DISPATCH,
		MARK_DISPATCH_READY
	}

	public record MaterialStatusView(long id, MaterialStatus materialStatus, LocalDate materialOrderDate,
			LocalDate expectedReceiptDate, LocalDate actualReceiptDate, InspectionStatus inspectionStatus,
			String remarks, Instant createdAt, Instant updatedAt) {
	}

	public record ProductionProgressView(long id, String processStepName, BigDecimal plannedQuantity,
			BigDecimal completedQuantity, BigDecimal acceptedQuantity, BigDecimal rejectedQuantity,
			BigDecimal reworkQuantity, ProductionStatus status, String blockerReason, LocalDate revisedDispatchDate,
			String updatedBy, Instant updatedAt) {
	}

	public record PackagingDispatchView(long id, BigDecimal packedQuantity, PackagingStatus packagingStatus,
			boolean dispatchReadyFlag, LocalDate dispatchDate, String dispatchRemarks, String updatedBy,
			Instant updatedAt) {
	}

	/** Per-audience list row (same sealed-interface pattern as NpdDtos.NpdView): the controller returns this type and
	 * Jackson serializes whichever concrete record it is. */
	public sealed interface OrderSummary permits OrderRow, SupplierOrderRow {
	}

	/** Per-audience detail view, see {@link OrderSummary}. */
	public sealed interface OrderDetail permits OrderView, SupplierOrderView {
	}

	/** ERGO's list row: both companies. */
	public record OrderRow(long id, String orderNumber, String customer, String supplier, String partNumber,
			String revision, BigDecimal orderedQuantity, LocalDate dueDate, OrderStatus currentStatus, String owner)
			implements OrderSummary {
	}

	/** ERGO's unrestricted detail view. */
	public record OrderView(long id, String orderNumber, String customer, String supplier, Long linkedNpdProjectId,
			Long linkedRfqId, String customerPoNo, String supplierPoNo, String partNumber, String revision,
			BigDecimal orderedQuantity, LocalDate dueDate, LocalDate committedDispatchDate,
			LocalDate actualDispatchDate, OrderStatus currentStatus, Long ownerUserId, String owner,
			List<MaterialStatusView> materialStatus, List<ProductionProgressView> productionProgress,
			List<PackagingDispatchView> packagingDispatch, Set<OrderAction> actions, Instant createdAt,
			Instant updatedAt) implements OrderDetail {
	}

	/** Two-way anonymity (ARCHITECTURE.md §8 2026-09-29, "4b two-way anonymity"): the supplier's row has no field for
	 * the customer at all — no name, no id, no customer PO number — and, matching SupplierNpdView, no ERGO owner. */
	public record SupplierOrderRow(long id, String orderNumber, String supplierPoNo, String partNumber,
			String revision, BigDecimal orderedQuantity, LocalDate dueDate, OrderStatus currentStatus)
			implements OrderSummary {
	}

	/** Material status as the supplier sees it: ERGO's free-text {@code remarks} dropped (could name the customer;
	 * same reasoning as NPD's H1 ExternalStageHistoryView). */
	public record SupplierMaterialStatusView(long id, MaterialStatus materialStatus, LocalDate materialOrderDate,
			LocalDate expectedReceiptDate, LocalDate actualReceiptDate, InspectionStatus inspectionStatus,
			Instant createdAt, Instant updatedAt) {
	}

	/** The supplier's detail view: no customer name/id, no customerPoNo (the customer's own document number), no
	 * linkedRfqId/linkedNpdProjectId (ids of records the supplier doesn't need here and may not otherwise reach), no
	 * ERGO owner (matching SupplierNpdView), no material-status remarks. */
	public record SupplierOrderView(long id, String orderNumber, String supplierPoNo, String partNumber,
			String revision, BigDecimal orderedQuantity, LocalDate dueDate, LocalDate committedDispatchDate,
			LocalDate actualDispatchDate, OrderStatus currentStatus, List<SupplierMaterialStatusView> materialStatus,
			List<ProductionProgressView> productionProgress, List<PackagingDispatchView> packagingDispatch,
			Set<OrderAction> actions, Instant createdAt, Instant updatedAt) implements OrderDetail {
	}

}
