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
 * Order request and response shapes. Single DTO type (CLAUDE.md's per-audience-DTO rule doesn't bite yet: 4a has no
 * customer/supplier audience at all — that arrives in 4b/4c). No price/currency field exists here (SRS §13.5.1 has
 * none).
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

	/** Buttons the caller may use now, computed by the same rules that enforce them. */
	public enum OrderAction {
		EDIT, ADVANCE, HOLD, RELEASE, UPDATE_MATERIAL_STATUS, UPDATE_PRODUCTION_PROGRESS
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

	public record OrderRow(long id, String orderNumber, String customer, String supplier, String partNumber,
			String revision, BigDecimal orderedQuantity, LocalDate dueDate, OrderStatus currentStatus, String owner) {
	}

	public record OrderView(long id, String orderNumber, String customer, String supplier, Long linkedNpdProjectId,
			Long linkedRfqId, String customerPoNo, String supplierPoNo, String partNumber, String revision,
			BigDecimal orderedQuantity, LocalDate dueDate, LocalDate committedDispatchDate,
			LocalDate actualDispatchDate, OrderStatus currentStatus, Long ownerUserId, String owner,
			List<MaterialStatusView> materialStatus, List<ProductionProgressView> productionProgress,
			Set<OrderAction> actions, Instant createdAt, Instant updatedAt) {
	}

}
