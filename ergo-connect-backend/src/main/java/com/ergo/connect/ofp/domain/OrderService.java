package com.ergo.connect.ofp.domain;

import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;
import static com.ergo.connect.common.security.Role.ERGO_NPD;
import static com.ergo.connect.common.security.Role.ERGO_OFP;
import static com.ergo.connect.common.security.Role.SUPPLIER_KAM;
import static com.ergo.connect.common.security.Role.SUPPLIER_NPD;
import static com.ergo.connect.common.security.Role.SUPPLIER_OFP;
import static com.ergo.connect.common.security.Role.SUPPLIER_QUALITY;

import java.math.BigDecimal;
import java.time.Year;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.audit.AuditLogService.AuditEntry;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;
import com.ergo.connect.npd.api.NpdApi;
import com.ergo.connect.npd.api.NpdApi.HandoverProject;
import com.ergo.connect.ofp.domain.OfpDtos.MaterialStatusForm;
import com.ergo.connect.ofp.domain.OfpDtos.MaterialStatusView;
import com.ergo.connect.ofp.domain.OfpDtos.OrderAction;
import com.ergo.connect.ofp.domain.OfpDtos.OrderDetail;
import com.ergo.connect.ofp.domain.OfpDtos.OrderSummary;
import com.ergo.connect.ofp.domain.OfpDtos.SupplierMaterialStatusView;
import com.ergo.connect.ofp.domain.OfpDtos.SupplierOrderRow;
import com.ergo.connect.ofp.domain.OfpDtos.SupplierOrderView;
import com.ergo.connect.ofp.domain.OfpDtos.OrderForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderHandoverForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderRow;
import com.ergo.connect.ofp.domain.OfpDtos.OrderUpdateForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderView;
import com.ergo.connect.ofp.domain.OfpDtos.PackagingDispatchForm;
import com.ergo.connect.ofp.domain.OfpDtos.PackagingDispatchView;
import com.ergo.connect.ofp.domain.OfpDtos.ProductionProgressForm;
import com.ergo.connect.ofp.domain.OfpDtos.ProductionProgressView;

/**
 * Phase 4a: ERGO-only order core and production tracking up to Packaging in Progress (SRS §13.5, §14.6). Phase 4b:
 * the assigned supplier's own scoped edits (production progress, packaging/dispatch) and the inspection-gated
 * ERGO-only Dispatch Ready transition (SRS §10.7 "E**", §13.5.4, §14.6, §17.5). Record scope (SRS §10.7, §8.2): ERGO
 * OFP/Admin/Management see every order, ERGO KAMs their assigned customers' orders, ERGO NPD every order that came
 * out of an NPD project (it is itself an unrestricted NPD viewer), a supplier role only orders where it is the
 * assigned supplier (direct {@code order.supplierCompanyId} match, mirroring NpdService's own
 * {@code SUPPLIER_EDITORS}/{@code ownsActivity} precedent — no new assignment table, ARCHITECTURE.md §8 2026-09-29).
 * {@link #allowed} is the single rule book: it enforces every action and lists the caller's buttons. Audit-only for
 * orders (no dedicated status-history table, ARCHITECTURE.md §8 2026-09-28): {@link #release} recovers what a Hold
 * interrupted from the automatic field audit trail rather than a new column.
 */
@Service
@Transactional
public class OrderService {

	/** SRS §10.7 full View/Create/Edit/Submit/Approve. ERGO Management is view-only in 4a (no approval gate yet). */
	private static final Set<Role> EDITORS = EnumSet.of(ERGO_ADMIN, ERGO_OFP);

	/** SRS §10.7 "E**": all four supplier-side roles (confirmed by the user, same grant NPD 3b already gives the
	 * identical role set), edit rights limited to production progress and packaging/dispatch on their own assigned
	 * order only — never material status, order header fields, or the workflow actions (hold/release/advance). */
	private static final Set<Role> SUPPLIER_EDITORS = EnumSet.of(SUPPLIER_NPD, SUPPLIER_OFP, SUPPLIER_QUALITY,
			SUPPLIER_KAM);

	private final OrderRepository orders;

	private final OrderMaterialStatusRepository materialStatuses;

	private final OrderProductionProgressRepository productionProgress;

	private final OrderPackagingDispatchRepository packagingDispatches;

	private final NpdApi npdApi;

	private final Directory directory;

	private final AuditLogService audit;

	private final JdbcTemplate jdbc;

	OrderService(OrderRepository orders, OrderMaterialStatusRepository materialStatuses,
			OrderProductionProgressRepository productionProgress, OrderPackagingDispatchRepository packagingDispatches,
			NpdApi npdApi, Directory directory, AuditLogService audit, JdbcTemplate jdbc) {
		this.orders = orders;
		this.materialStatuses = materialStatuses;
		this.productionProgress = productionProgress;
		this.packagingDispatches = packagingDispatches;
		this.npdApi = npdApi;
		this.directory = directory;
		this.audit = audit;
		this.jdbc = jdbc;
	}

	// ---------------------------------------------------------------- reads

	@Transactional(readOnly = true)
	public List<? extends OrderSummary> list() {
		CurrentUser me = CurrentUser.get();
		if (SUPPLIER_EDITORS.contains(me.role())) {
			// Two-way anonymity: no customer lookup is ever made building the supplier's rows.
			return orders.findBySupplierCompanyIdOrderByIdDesc(me.companyId()).stream()
				.map(o -> new SupplierOrderRow(o.id, o.orderNumber, o.supplierPoNo, o.partNumber, o.revision,
						o.orderedQuantity, o.dueDate, o.currentStatus))
				.toList();
		}
		List<Order> visible = switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_OFP -> orders.findAllByOrderByIdDesc();
			case ERGO_KAM -> {
				Set<Long> accounts = directory.accountsOf(me.userId());
				yield accounts.isEmpty() ? List.of() : orders.findByCustomerCompanyIdInOrderByIdDesc(accounts);
			}
			case ERGO_NPD -> orders.findByLinkedNpdProjectIdIsNotNullOrderByIdDesc();
			default -> List.of(); // SRS §10.7 X: ERGO RFQ/Quality, every customer role (customer OFP is 4c)
		};
		Map<Long, CompanyRef> customers = directory.companies(ids(visible, o -> o.customerCompanyId));
		Map<Long, CompanyRef> suppliers = directory.companies(ids(visible, o -> o.supplierCompanyId));
		Map<Long, UserRef> owners = directory.users(ids(visible, o -> o.ownerUserId));
		return visible.stream()
			.map(o -> new OrderRow(o.id, o.orderNumber, name(customers, o.customerCompanyId),
					name(suppliers, o.supplierCompanyId), o.partNumber, o.revision, o.orderedQuantity, o.dueDate,
					o.currentStatus, userName(owners, o.ownerUserId)))
			.toList();
	}

	@Transactional(readOnly = true)
	public OrderDetail get(long id) {
		CurrentUser me = CurrentUser.get();
		return view(me, visible(me, id));
	}

	// ---------------------------------------------------------------- create

	/** Direct standalone create (SRS §13.5.1): repeat/running orders with no NPD/RFQ link. */
	public OrderDetail create(OrderForm form) {
		CurrentUser me = CurrentUser.get();
		requireEditor(me);
		Order order = new Order();
		order.orderNumber = nextOrderNumber();
		order.customerCompanyId = requireCompany(form.customerCompanyId(), CompanyType.CUSTOMER, "Customer");
		order.supplierCompanyId = requireCompany(form.supplierCompanyId(), CompanyType.SUPPLIER, "Supplier");
		order.customerPoNo = text(form.customerPoNo());
		order.supplierPoNo = text(form.supplierPoNo());
		order.partNumber = form.partNumber().trim();
		order.revision = text(form.revision());
		order.orderedQuantity = form.orderedQuantity();
		order.dueDate = form.dueDate();
		order.committedDispatchDate = form.committedDispatchDate();
		order.currentStatus = OrderStatus.ORDER_CREATED;
		order.ownerUserId = resolveOwner(me, form.ownerUserId());
		orders.save(order);
		return view(me, order);
	}

	/** NPD's "Handover to OFP" action: customer, supplier, part number and revision come from the NPD project
	 * itself; {@link NpdApi#handover} 404s outside the caller's ERGO scope and 409s a project already handed
	 * over, both before this method touches anything else. */
	public OrderDetail handover(long npdProjectId, OrderHandoverForm form) {
		CurrentUser me = CurrentUser.get();
		requireEditor(me);
		HandoverProject project = npdApi.handover(npdProjectId);
		Order order = new Order();
		order.orderNumber = nextOrderNumber();
		order.customerCompanyId = project.customerCompanyId();
		order.supplierCompanyId = project.supplierCompanyId();
		order.linkedNpdProjectId = project.id();
		order.linkedRfqId = project.sourceRfqId();
		order.partNumber = project.partNumber();
		order.revision = project.revision();
		order.customerPoNo = text(form.customerPoNo());
		order.supplierPoNo = text(form.supplierPoNo());
		order.orderedQuantity = form.orderedQuantity();
		order.dueDate = form.dueDate();
		order.currentStatus = OrderStatus.ORDER_CREATED;
		order.ownerUserId = resolveOwner(me, form.ownerUserId());
		orders.save(order);
		return view(me, order);
	}

	public OrderDetail update(long id, OrderUpdateForm form) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		require(OrderAction.EDIT, me, order);
		order.customerPoNo = text(form.customerPoNo());
		order.supplierPoNo = text(form.supplierPoNo());
		order.dueDate = form.dueDate();
		order.committedDispatchDate = form.committedDispatchDate();
		order.ownerUserId = resolveOwner(me, form.ownerUserId());
		return view(me, order);
	}

	// ---------------------------------------------------------------- workflow (SRS §14.6)

	/** One step forward in the fixed 4a sequence; never skips ahead (the next status is always derived from the
	 * current one, never client-supplied). */
	public OrderDetail advance(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		require(OrderAction.ADVANCE, me, order);
		reasonIfGiven(comment);
		order.currentStatus = OrderStatus.SEQUENCE.get(OrderStatus.SEQUENCE.indexOf(order.currentStatus) + 1);
		return view(me, order);
	}

	/** SRS §14.6: reachable from any non-terminal status, reason required. */
	public OrderDetail hold(long id, String reason) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		require(OrderAction.HOLD, me, order);
		audit.reason(required(reason, "A reason is required to put an order on hold"));
		order.currentStatus = OrderStatus.ON_HOLD;
		return view(me, order);
	}

	/** Returns to whatever status Hold interrupted, recovered from the automatic audit trail (no previous-status
	 * column, ARCHITECTURE.md §8 2026-09-28). Security review M2 (ARCHITECTURE.md §8 2026-09-29): if that status was
	 * DISPATCH_READY, its own inspection gate is re-checked before restoring it — material status can still change
	 * while an order is ON_HOLD (M2's other half is deliberately left for 4c's own Dispatch action to re-check), so
	 * the gate that was true when Hold was pressed may no longer hold by the time Release is. */
	public OrderDetail release(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		require(OrderAction.RELEASE, me, order);
		OrderStatus previous = statusBeforeHold(order.id);
		if (previous == OrderStatus.DISPATCH_READY && !dispatchReadyGateMet(order.id)) {
			throw ApiException
				.conflict("Cannot release back to Dispatch Ready - not every material inspection has passed anymore");
		}
		reasonIfGiven(comment);
		order.currentStatus = previous;
		return view(me, order);
	}

	private OrderStatus statusBeforeHold(long orderId) {
		return audit.history("ofp", "Order", orderId).stream()
			.filter(e -> "currentStatus".equals(e.field()) && "ON_HOLD".equals(e.newValue()))
			.reduce((first, last) -> last) // the most recent Hold, in case of more than one over the order's life
			.map(e -> OrderStatus.valueOf(e.oldValue()))
			.orElseThrow(() -> ApiException.conflict("No prior status was recorded to release this order back to"));
	}

	/** SRS §14.6/§17.5: Packaging in Progress -> Dispatch Ready, ERGO OFP/Admin only (never the supplier, even
	 * though a supplier may set its own {@code dispatchReadyFlag} readiness claim on {@code order_packaging_dispatch}
	 * — that flag never moves this status by itself, ARCHITECTURE.md §8 2026-09-29). Kept as its own action rather
	 * than folded into {@link #advance}: {@link OrderStatus#SEQUENCE} deliberately stops at PACKAGING_IN_PROGRESS, so
	 * the generic sequence walk can never reach DISPATCH_READY and skip this gate. Blocked (with a specific,
	 * actionable message) unless every material-status row for the order has passed inspection, and there is at
	 * least one such row (point 4: zero rows is not a pass). */
	public OrderDetail dispatchReady(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		if (!EDITORS.contains(me.role()) || order.currentStatus != OrderStatus.PACKAGING_IN_PROGRESS) {
			throw ApiException.conflict("This action is not available for this order right now");
		}
		if (!dispatchReadyGateMet(order.id)) {
			throw ApiException
				.unprocessable("All material inspections must pass before the order can be marked dispatch ready.");
		}
		reasonIfGiven(comment);
		order.currentStatus = OrderStatus.DISPATCH_READY;
		return view(me, order);
	}

	/** Every {@code order_material_status} row for this order must be PASSED, and there must be at least one row —
	 * an order with no inspection recorded at all has not "passed", it simply hasn't been checked yet. Reuses
	 * {@code inspection_status} as the pre-dispatch quality gate stub until the Quality module exists
	 * (ARCHITECTURE.md §8 2026-09-29). */
	private boolean dispatchReadyGateMet(long orderId) {
		List<OrderMaterialStatus> materials = materialStatuses.findByOrderIdOrderById(orderId);
		return !materials.isEmpty() && materials.stream().allMatch(m -> m.inspectionStatus == InspectionStatus.PASSED);
	}

	// ---------------------------------------------------------------- material status (SRS §13.5.2)

	public OrderDetail addMaterialStatus(long orderId, MaterialStatusForm form) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, orderId);
		require(OrderAction.UPDATE_MATERIAL_STATUS, me, order);
		OrderMaterialStatus status = new OrderMaterialStatus();
		status.orderId = order.id;
		applyMaterialStatus(status, form);
		materialStatuses.save(status);
		return view(me, order);
	}

	public OrderDetail updateMaterialStatus(long statusId, MaterialStatusForm form) {
		CurrentUser me = CurrentUser.get();
		OrderMaterialStatus status = materialStatuses.findById(statusId)
			.orElseThrow(() -> ApiException.notFound("Material status"));
		Order order = visible(me, status.orderId);
		require(OrderAction.UPDATE_MATERIAL_STATUS, me, order);
		applyMaterialStatus(status, form);
		return view(me, order);
	}

	private static void applyMaterialStatus(OrderMaterialStatus status, MaterialStatusForm form) {
		status.materialStatus = form.materialStatus();
		status.materialOrderDate = form.materialOrderDate();
		status.expectedReceiptDate = form.expectedReceiptDate();
		status.actualReceiptDate = form.actualReceiptDate();
		status.inspectionStatus = form.inspectionStatus();
		status.remarks = text(form.remarks());
	}

	// ---------------------------------------------------------------- production progress (SRS §13.5.3)

	public OrderDetail addProductionProgress(long orderId, ProductionProgressForm form) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, orderId);
		require(OrderAction.UPDATE_PRODUCTION_PROGRESS, me, order);
		OrderProductionProgress progress = new OrderProductionProgress();
		progress.orderId = order.id;
		applyProductionProgress(progress, form, me);
		productionProgress.save(progress);
		return view(me, order);
	}

	public OrderDetail updateProductionProgress(long progressId, ProductionProgressForm form) {
		CurrentUser me = CurrentUser.get();
		OrderProductionProgress progress = productionProgress.findById(progressId)
			.orElseThrow(() -> ApiException.notFound("Production progress"));
		Order order = visible(me, progress.orderId);
		require(OrderAction.UPDATE_PRODUCTION_PROGRESS, me, order);
		applyProductionProgress(progress, form, me);
		return view(me, order);
	}

	private static void applyProductionProgress(OrderProductionProgress progress, ProductionProgressForm form,
			CurrentUser me) {
		if (form.status() == ProductionStatus.BLOCKED && text(form.blockerReason()) == null) {
			throw ApiException.unprocessable("A blocker reason is required when status is Blocked");
		}
		progress.processStepName = form.processStepName().trim();
		progress.plannedQuantity = form.plannedQuantity();
		progress.completedQuantity = zeroIfNull(form.completedQuantity());
		progress.acceptedQuantity = zeroIfNull(form.acceptedQuantity());
		progress.rejectedQuantity = zeroIfNull(form.rejectedQuantity());
		progress.reworkQuantity = zeroIfNull(form.reworkQuantity());
		progress.status = form.status();
		progress.blockerReason = form.status() == ProductionStatus.BLOCKED ? text(form.blockerReason()) : null;
		progress.revisedDispatchDate = form.revisedDispatchDate();
		progress.updatedByUserId = me.userId();
	}

	private static BigDecimal zeroIfNull(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	// ---------------------------------------------------------------- packaging & dispatch (SRS §13.5.4)

	public OrderDetail addPackagingDispatch(long orderId, PackagingDispatchForm form) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, orderId);
		require(OrderAction.UPDATE_PACKAGING_DISPATCH, me, order);
		OrderPackagingDispatch dispatch = new OrderPackagingDispatch();
		dispatch.orderId = order.id;
		applyPackagingDispatch(dispatch, form, me);
		packagingDispatches.save(dispatch);
		return view(me, order);
	}

	public OrderDetail updatePackagingDispatch(long dispatchId, PackagingDispatchForm form) {
		CurrentUser me = CurrentUser.get();
		OrderPackagingDispatch dispatch = packagingDispatches.findById(dispatchId)
			.orElseThrow(() -> ApiException.notFound("Packaging/dispatch"));
		Order order = visible(me, dispatch.orderId);
		require(OrderAction.UPDATE_PACKAGING_DISPATCH, me, order);
		applyPackagingDispatch(dispatch, form, me);
		return view(me, order);
	}

	private static void applyPackagingDispatch(OrderPackagingDispatch dispatch, PackagingDispatchForm form,
			CurrentUser me) {
		dispatch.packedQuantity = form.packedQuantity();
		dispatch.packagingStatus = form.packagingStatus();
		// A readiness claim only (ARCHITECTURE.md §8 2026-09-29): never moves orders.currentStatus by itself, see
		// dispatchReady().
		dispatch.dispatchReadyFlag = form.dispatchReadyFlag();
		dispatch.dispatchDate = form.dispatchDate();
		dispatch.dispatchRemarks = text(form.dispatchRemarks());
		dispatch.updatedByUserId = me.userId();
	}

	// ---------------------------------------------------------------- rules

	/** Record scope. Out-of-scope orders do not exist for the caller (404, not 403). */
	Order visible(CurrentUser me, long id) {
		Order order = orders.findById(id).orElse(null);
		boolean inScope = order != null && switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_OFP -> true;
			case ERGO_KAM -> directory.accountsOf(me.userId()).contains(order.customerCompanyId);
			case ERGO_NPD -> order.linkedNpdProjectId != null;
			case SUPPLIER_NPD, SUPPLIER_OFP, SUPPLIER_QUALITY, SUPPLIER_KAM ->
				me.companyId() == order.supplierCompanyId;
			default -> false;
		};
		if (!inScope) {
			throw ApiException.notFound("Order");
		}
		return order;
	}

	private static void requireEditor(CurrentUser me) {
		if (!EDITORS.contains(me.role())) {
			throw ApiException.forbidden("Only ERGO OFP or ERGO Admin may do this");
		}
	}

	/** The rule book: SRS §10.7 rights combined with the §14.6 status. Not static (unlike 4a): {@code
	 * MARK_DISPATCH_READY} needs {@link #dispatchReadyGateMet} to decide whether the button is really available,
	 * not just the status. */
	private boolean allowed(OrderAction action, CurrentUser me, Order order) {
		boolean editor = EDITORS.contains(me.role());
		boolean ownSupplier = SUPPLIER_EDITORS.contains(me.role()) && me.companyId() == order.supplierCompanyId;
		OrderStatus status = order.currentStatus;
		return switch (action) {
			case EDIT -> editor && status != OrderStatus.CLOSED;
			case ADVANCE -> editor && OrderStatus.SEQUENCE.contains(status)
					&& !status.equals(OrderStatus.SEQUENCE.getLast());
			case HOLD -> editor && status != OrderStatus.CLOSED && status != OrderStatus.ON_HOLD;
			case RELEASE -> editor && status == OrderStatus.ON_HOLD;
			case UPDATE_MATERIAL_STATUS -> editor && status != OrderStatus.CLOSED;
			// SRS §10.7 "E**": the assigned supplier, alongside ERGO OFP/Admin, but never Hold/Release/Advance/
			// Edit/material status (point 1/5, ARCHITECTURE.md §8 2026-09-29). Security review L2 (ARCHITECTURE.md §8
			// 2026-09-29): a supplier's own write is additionally blocked while ON_HOLD (an order on hold is meant to
			// be frozen for whatever reason it's held, not quietly progressed by the supplier); ERGO's own write stays
			// allowed while ON_HOLD since it may need to document why it's held.
			case UPDATE_PRODUCTION_PROGRESS, UPDATE_PACKAGING_DISPATCH -> editor && status != OrderStatus.CLOSED
					|| ownSupplier && status != OrderStatus.CLOSED && status != OrderStatus.ON_HOLD;
			case MARK_DISPATCH_READY -> editor && status == OrderStatus.PACKAGING_IN_PROGRESS
					&& dispatchReadyGateMet(order.id);
		};
	}

	private Set<OrderAction> actions(CurrentUser me, Order order) {
		return Arrays.stream(OrderAction.values()).filter(a -> allowed(a, me, order))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(OrderAction.class)));
	}

	private void require(OrderAction action, CurrentUser me, Order order) {
		if (!allowed(action, me, order)) {
			throw ApiException.conflict("This action is not available for this order right now");
		}
	}

	private long requireCompany(long companyId, CompanyType type, String label) {
		return directory.company(companyId).filter(c -> c.type() == type && c.active())
			.orElseThrow(() -> ApiException.unprocessable(label + " not found or not active"))
			.id();
	}

	/** An order's owner is ERGO-internal, same rule as an NPD project's owner: never a customer's or supplier's
	 * employee. The caller is always ERGO here (checked by {@link #requireEditor}/{@link #require} first). */
	private Long resolveOwner(CurrentUser me, Long ownerUserId) {
		if (ownerUserId == null) {
			return null;
		}
		UserRef owner = directory.users(Set.of(ownerUserId)).get(ownerUserId);
		if (owner == null || owner.companyId() != me.companyId()) {
			throw ApiException.unprocessable("The order owner must be an ERGO user");
		}
		return ownerUserId;
	}

	private String nextOrderNumber() {
		return "ORD-%d-%05d".formatted(Year.now().getValue(),
				jdbc.queryForObject("SELECT nextval('order_number_seq')", Long.class));
	}

	// ---------------------------------------------------------------- view assembly

	/** The single per-audience view builder: a supplier caller gets SupplierOrderView, built without ever looking up
	 * the customer; everyone else reaching here is ERGO (visible() already 404s every other role). */
	private OrderDetail view(CurrentUser me, Order o) {
		List<OrderMaterialStatus> materials = materialStatuses.findByOrderIdOrderById(o.id);

		List<OrderProductionProgress> progress = productionProgress.findByOrderIdOrderById(o.id);
		Map<Long, UserRef> progressUsers = directory.users(ids(progress, p -> p.updatedByUserId));
		List<ProductionProgressView> progressViews = progress.stream()
			.map(p -> new ProductionProgressView(p.id, p.processStepName, p.plannedQuantity, p.completedQuantity,
					p.acceptedQuantity, p.rejectedQuantity, p.reworkQuantity, p.status, p.blockerReason,
					p.revisedDispatchDate, userName(progressUsers, p.updatedByUserId), p.updatedAt))
			.toList();

		List<OrderPackagingDispatch> dispatches = packagingDispatches.findByOrderIdOrderById(o.id);
		Map<Long, UserRef> dispatchUsers = directory.users(ids(dispatches, d -> d.updatedByUserId));
		List<PackagingDispatchView> dispatchViews = dispatches.stream()
			.map(d -> new PackagingDispatchView(d.id, d.packedQuantity, d.packagingStatus, d.dispatchReadyFlag,
					d.dispatchDate, d.dispatchRemarks, userName(dispatchUsers, d.updatedByUserId), d.updatedAt))
			.toList();

		if (SUPPLIER_EDITORS.contains(me.role())) {
			List<SupplierMaterialStatusView> supplierMaterialViews = materials.stream()
				.map(m -> new SupplierMaterialStatusView(m.id, m.materialStatus, m.materialOrderDate,
						m.expectedReceiptDate, m.actualReceiptDate, m.inspectionStatus, m.createdAt, m.updatedAt))
				.toList();
			return new SupplierOrderView(o.id, o.orderNumber, o.supplierPoNo, o.partNumber, o.revision,
					o.orderedQuantity, o.dueDate, o.committedDispatchDate, o.actualDispatchDate, o.currentStatus,
					supplierMaterialViews, progressViews, dispatchViews, actions(me, o), o.createdAt, o.updatedAt);
		}

		CompanyRef customer = directory.company(o.customerCompanyId).orElseThrow();
		CompanyRef supplier = directory.company(o.supplierCompanyId).orElseThrow();
		Map<Long, UserRef> owners = directory.users(ids(List.of(o), x -> x.ownerUserId));
		List<MaterialStatusView> materialViews = materials.stream()
			.map(m -> new MaterialStatusView(m.id, m.materialStatus, m.materialOrderDate, m.expectedReceiptDate,
					m.actualReceiptDate, m.inspectionStatus, m.remarks, m.createdAt, m.updatedAt))
			.toList();
		return new OrderView(o.id, o.orderNumber, customer.name(), supplier.name(), o.linkedNpdProjectId,
				o.linkedRfqId, o.customerPoNo, o.supplierPoNo, o.partNumber, o.revision, o.orderedQuantity, o.dueDate,
				o.committedDispatchDate, o.actualDispatchDate, o.currentStatus, o.ownerUserId,
				userName(owners, o.ownerUserId), materialViews, progressViews, dispatchViews, actions(me, o),
				o.createdAt, o.updatedAt);
	}

	// ---------------------------------------------------------------- small helpers

	@SafeVarargs
	private static <T> Set<Long> ids(Collection<T> items, Function<T, Long>... fields) {
		return items.stream().flatMap(i -> Arrays.stream(fields).map(f -> f.apply(i))).filter(Objects::nonNull)
			.collect(Collectors.toSet());
	}

	private static String name(Map<Long, CompanyRef> names, Long id) {
		CompanyRef c = id == null ? null : names.get(id);
		return c == null ? null : c.name();
	}

	private static String userName(Map<Long, UserRef> users, Long id) {
		UserRef user = id == null ? null : users.get(id);
		return user == null ? null : user.name();
	}

	private static String text(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String required(String value, String message) {
		String t = text(value);
		if (t == null) {
			throw ApiException.unprocessable(message);
		}
		return t;
	}

	private void reasonIfGiven(String comment) {
		String t = text(comment);
		if (t != null) {
			audit.reason(t);
		}
	}

}
