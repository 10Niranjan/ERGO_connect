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
 * Order request and response shapes. Per-audience responses (ARCHITECTURE.md §8 2026-09-29, "4b two-way anonymity"
 * and "4c scope"): ERGO gets OrderRow/OrderView, a supplier gets SupplierOrderRow/SupplierOrderView (no field that
 * names or identifies the customer), and a customer gets CustomerOrderRow/CustomerOrderView (no field that names or
 * identifies the supplier, and always an empty {@code actions} set — Customer OFP is read-only). No price/currency
 * field exists here (SRS §13.5.1 has none).
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

	/** SRS §13.5.5, §10.7: ERGO OFP/Admin only (Phase 4c). {@code status} must be one of DISPATCHED/IN_TRANSIT/
	 * DELIVERED (validated in the service, matching the DB CHECK); a null value defaults to DISPATCHED, the same
	 * default the migration gives the column. {@code podDocumentId} is deliberately absent here — it is set only by
	 * {@code OrderService.uploadPod} through the Documents module, never a plain client-supplied id. */
	public record ShipmentForm(Long shipmentModeId, @Size(max = 200) String transporterName,
			@Size(max = 200) String forwarderName, @Size(max = 100) String trackingNumber,
			@Size(max = 100) String awbBlNo, LocalDate eta, LocalDate actualDeliveryDate, OrderStatus status,
			boolean receiptConfirmedByCustomerFlag) {
	}

	/** Buttons the caller may use now, computed by the same rules that enforce them. */
	public enum OrderAction {
		EDIT, ADVANCE, HOLD, RELEASE, UPDATE_MATERIAL_STATUS, UPDATE_PRODUCTION_PROGRESS, UPDATE_PACKAGING_DISPATCH,
		MARK_DISPATCH_READY, CREATE_SHIPMENT, UPDATE_SHIPMENT, DISPATCH, MARK_IN_TRANSIT, MARK_DELIVERED, CLOSE_ORDER
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

	/** ERGO-only (security review H1/L3, ARCHITECTURE.md §8 2026-09-29): carries the POD reference and who last
	 * updated the row. Never used for the supplier's or customer's own view — see {@link ExternalShipmentView}. A
	 * POD's file name routinely names the consignee (e.g. {@code POD_Acme_Motors_GRN_signed.pdf}), so a supplier
	 * reading their own order's shipments could otherwise learn the customer's identity from this field alone, no
	 * ERGO typo required (unlike the already-accepted free-text gap). */
	public record ShipmentView(long id, Long shipmentModeId, String transporterName, String forwarderName,
			String trackingNumber, String awbBlNo, LocalDate eta, LocalDate actualDeliveryDate, Long podDocumentId,
			String podFileName, boolean receiptConfirmedByCustomerFlag, Instant receiptConfirmedAt,
			OrderStatus status, String updatedBy, Instant updatedAt) {
	}

	/** Supplier's and customer's shipment view (H1/L3 fixes): no POD reference and no {@code updatedBy} (always an
	 * ERGO employee's name, same precedent as dropping the order owner from {@link SupplierOrderRow}). Nothing else
	 * on {@code shipments} identifies either party (transporter/forwarder are a logistics company, not either party,
	 * ARCHITECTURE.md §8 2026-09-29). */
	public record ExternalShipmentView(long id, Long shipmentModeId, String transporterName, String forwarderName,
			String trackingNumber, String awbBlNo, LocalDate eta, LocalDate actualDeliveryDate,
			boolean receiptConfirmedByCustomerFlag, Instant receiptConfirmedAt, OrderStatus status,
			Instant updatedAt) {
	}

	/** Per-audience list row (same sealed-interface pattern as NpdDtos.NpdView): the controller returns this type and
	 * Jackson serializes whichever concrete record it is. */
	public sealed interface OrderSummary permits OrderRow, SupplierOrderRow, CustomerOrderRow {
	}

	/** Per-audience detail view, see {@link OrderSummary}. */
	public sealed interface OrderDetail permits OrderView, SupplierOrderView, CustomerOrderView {
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
			List<PackagingDispatchView> packagingDispatch, List<ShipmentView> shipments, Set<OrderAction> actions,
			Instant createdAt, Instant updatedAt) implements OrderDetail {
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
	 * ERGO owner (matching SupplierNpdView), no material-status remarks. Phase 4c: the supplier reads (never writes)
	 * shipment/tracking info for orders it fulfills via {@link ExternalShipmentView} (H1 fix: no POD reference, which
	 * routinely names the customer). */
	public record SupplierOrderView(long id, String orderNumber, String supplierPoNo, String partNumber,
			String revision, BigDecimal orderedQuantity, LocalDate dueDate, LocalDate committedDispatchDate,
			LocalDate actualDispatchDate, OrderStatus currentStatus, List<SupplierMaterialStatusView> materialStatus,
			List<ProductionProgressView> productionProgress, List<PackagingDispatchView> packagingDispatch,
			List<ExternalShipmentView> shipments, Set<OrderAction> actions, Instant createdAt, Instant updatedAt)
			implements OrderDetail {
	}

	/** Phase 4c, two-way anonymity extended to the customer (ARCHITECTURE.md §8 2026-09-29 "4c scope"): no supplier
	 * name/id, no ERGO owner, no linkedRfqId/linkedNpdProjectId (the customer cannot otherwise reach either record,
	 * same reasoning as {@link SupplierOrderRow}), and zero write actions ever appear in {@code actions} — Customer
	 * OFP is pure read-only (§10.7's literal matrix gives it "RV" only). */
	public record CustomerOrderRow(long id, String orderNumber, String customerPoNo, String partNumber,
			String revision, BigDecimal orderedQuantity, LocalDate dueDate, OrderStatus currentStatus)
			implements OrderSummary {
	}

	/** Production-progress milestones as the customer sees them (§12.9.2/§12.9.4): step, quantities and status only.
	 * {@code blockerReason} (free text a supplier writes, which could name itself or a third party) and
	 * {@code updatedBy} (a supplier employee's name) are both dropped — the same H1-style precedent as
	 * {@link SupplierMaterialStatusView} dropping ERGO's free-text {@code remarks}. */
	public record CustomerProductionProgressView(long id, String processStepName, BigDecimal plannedQuantity,
			BigDecimal completedQuantity, BigDecimal acceptedQuantity, BigDecimal rejectedQuantity,
			BigDecimal reworkQuantity, ProductionStatus status, LocalDate revisedDispatchDate, Instant updatedAt) {
	}

	/** The customer's detail view (§12.9.2 OFP Detail Screen, §12.9.4 Shipment Tracking Screen): order/shipment
	 * status, dates, shipment tracking info and production-progress milestones, but no supplier identity, no
	 * ERGO-internal free-text (material-status {@code remarks} reuses {@link SupplierMaterialStatusView}, which
	 * already drops them), no {@code linkedNpdProjectId}/{@code linkedRfqId}, and (H1 fix) no POD reference on
	 * {@link ExternalShipmentView} — a POD's file name routinely names the consignee, which would defeat the
	 * anonymity rule from the other direction if it ever named the supplier instead. {@code actions} is always
	 * empty: Customer OFP has no write right on any OFP record (ARCHITECTURE.md §8 2026-09-29). */
	public record CustomerOrderView(long id, String orderNumber, String customerPoNo, String partNumber,
			String revision, BigDecimal orderedQuantity, LocalDate dueDate, LocalDate committedDispatchDate,
			LocalDate actualDispatchDate, OrderStatus currentStatus, List<SupplierMaterialStatusView> materialStatus,
			List<CustomerProductionProgressView> productionProgress, List<ExternalShipmentView> shipments,
			Set<OrderAction> actions, Instant createdAt, Instant updatedAt) implements OrderDetail {
	}

}
