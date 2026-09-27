package com.ergo.connect.ofp.domain;

import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;
import static com.ergo.connect.common.security.Role.ERGO_NPD;
import static com.ergo.connect.common.security.Role.ERGO_OFP;

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
import com.ergo.connect.ofp.domain.OfpDtos.OrderForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderHandoverForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderRow;
import com.ergo.connect.ofp.domain.OfpDtos.OrderUpdateForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderView;
import com.ergo.connect.ofp.domain.OfpDtos.ProductionProgressForm;
import com.ergo.connect.ofp.domain.OfpDtos.ProductionProgressView;

/**
 * Phase 4a: ERGO-only order core and production tracking up to Packaging in Progress (SRS §13.5, §14.6). Record
 * scope (SRS §10.7, §8.2): ERGO OFP/Admin/Management see every order, ERGO KAMs their assigned customers' orders,
 * ERGO NPD every order that came out of an NPD project (it is itself an unrestricted NPD viewer). {@link #allowed}
 * is the single rule book: it enforces every action and lists the caller's buttons. Audit-only for orders (no
 * dedicated status-history table, ARCHITECTURE.md §8 2026-09-28): {@link #release} recovers what a Hold interrupted
 * from the automatic field audit trail rather than a new column.
 */
@Service
@Transactional
public class OrderService {

	/** SRS §10.7 full View/Create/Edit/Submit/Approve. ERGO Management is view-only in 4a (no approval gate yet). */
	private static final Set<Role> EDITORS = EnumSet.of(ERGO_ADMIN, ERGO_OFP);

	private final OrderRepository orders;

	private final OrderMaterialStatusRepository materialStatuses;

	private final OrderProductionProgressRepository productionProgress;

	private final NpdApi npdApi;

	private final Directory directory;

	private final AuditLogService audit;

	private final JdbcTemplate jdbc;

	OrderService(OrderRepository orders, OrderMaterialStatusRepository materialStatuses,
			OrderProductionProgressRepository productionProgress, NpdApi npdApi, Directory directory,
			AuditLogService audit, JdbcTemplate jdbc) {
		this.orders = orders;
		this.materialStatuses = materialStatuses;
		this.productionProgress = productionProgress;
		this.npdApi = npdApi;
		this.directory = directory;
		this.audit = audit;
		this.jdbc = jdbc;
	}

	// ---------------------------------------------------------------- reads

	@Transactional(readOnly = true)
	public List<OrderRow> list() {
		CurrentUser me = CurrentUser.get();
		List<Order> visible = switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_OFP -> orders.findAllByOrderByIdDesc();
			case ERGO_KAM -> {
				Set<Long> accounts = directory.accountsOf(me.userId());
				yield accounts.isEmpty() ? List.of() : orders.findByCustomerCompanyIdInOrderByIdDesc(accounts);
			}
			case ERGO_NPD -> orders.findByLinkedNpdProjectIdIsNotNullOrderByIdDesc();
			default -> List.of(); // SRS §10.7 X: ERGO RFQ/Quality, every customer/supplier role
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
	public OrderView get(long id) {
		CurrentUser me = CurrentUser.get();
		return view(me, visible(me, id));
	}

	// ---------------------------------------------------------------- create

	/** Direct standalone create (SRS §13.5.1): repeat/running orders with no NPD/RFQ link. */
	public OrderView create(OrderForm form) {
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
	public OrderView handover(long npdProjectId, OrderHandoverForm form) {
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

	public OrderView update(long id, OrderUpdateForm form) {
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
	public OrderView advance(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		require(OrderAction.ADVANCE, me, order);
		reasonIfGiven(comment);
		order.currentStatus = OrderStatus.SEQUENCE.get(OrderStatus.SEQUENCE.indexOf(order.currentStatus) + 1);
		return view(me, order);
	}

	/** SRS §14.6: reachable from any non-terminal status, reason required. */
	public OrderView hold(long id, String reason) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		require(OrderAction.HOLD, me, order);
		audit.reason(required(reason, "A reason is required to put an order on hold"));
		order.currentStatus = OrderStatus.ON_HOLD;
		return view(me, order);
	}

	/** Returns to whatever status Hold interrupted, recovered from the automatic audit trail (no previous-status
	 * column, ARCHITECTURE.md §8 2026-09-28). */
	public OrderView release(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		require(OrderAction.RELEASE, me, order);
		OrderStatus previous = statusBeforeHold(order.id);
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

	// ---------------------------------------------------------------- material status (SRS §13.5.2)

	public OrderView addMaterialStatus(long orderId, MaterialStatusForm form) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, orderId);
		require(OrderAction.UPDATE_MATERIAL_STATUS, me, order);
		OrderMaterialStatus status = new OrderMaterialStatus();
		status.orderId = order.id;
		applyMaterialStatus(status, form);
		materialStatuses.save(status);
		return view(me, order);
	}

	public OrderView updateMaterialStatus(long statusId, MaterialStatusForm form) {
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

	public OrderView addProductionProgress(long orderId, ProductionProgressForm form) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, orderId);
		require(OrderAction.UPDATE_PRODUCTION_PROGRESS, me, order);
		OrderProductionProgress progress = new OrderProductionProgress();
		progress.orderId = order.id;
		applyProductionProgress(progress, form, me);
		productionProgress.save(progress);
		return view(me, order);
	}

	public OrderView updateProductionProgress(long progressId, ProductionProgressForm form) {
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

	// ---------------------------------------------------------------- rules

	/** Record scope. Out-of-scope orders do not exist for the caller (404, not 403). */
	Order visible(CurrentUser me, long id) {
		Order order = orders.findById(id).orElse(null);
		boolean inScope = order != null && switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_OFP -> true;
			case ERGO_KAM -> directory.accountsOf(me.userId()).contains(order.customerCompanyId);
			case ERGO_NPD -> order.linkedNpdProjectId != null;
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

	/** The rule book: SRS §10.7 rights combined with the §14.6 status. */
	private static boolean allowed(OrderAction action, CurrentUser me, Order order) {
		boolean editor = EDITORS.contains(me.role());
		OrderStatus status = order.currentStatus;
		return switch (action) {
			case EDIT -> editor && status != OrderStatus.CLOSED;
			case ADVANCE -> editor && OrderStatus.SEQUENCE.contains(status)
					&& !status.equals(OrderStatus.SEQUENCE.getLast());
			case HOLD -> editor && status != OrderStatus.CLOSED && status != OrderStatus.ON_HOLD;
			case RELEASE -> editor && status == OrderStatus.ON_HOLD;
			case UPDATE_MATERIAL_STATUS, UPDATE_PRODUCTION_PROGRESS -> editor && status != OrderStatus.CLOSED;
		};
	}

	private static Set<OrderAction> actions(CurrentUser me, Order order) {
		return Arrays.stream(OrderAction.values()).filter(a -> allowed(a, me, order))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(OrderAction.class)));
	}

	private static void require(OrderAction action, CurrentUser me, Order order) {
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

	private OrderView view(CurrentUser me, Order o) {
		CompanyRef customer = directory.company(o.customerCompanyId).orElseThrow();
		CompanyRef supplier = directory.company(o.supplierCompanyId).orElseThrow();
		Map<Long, UserRef> owners = directory.users(ids(List.of(o), x -> x.ownerUserId));

		List<OrderMaterialStatus> materials = materialStatuses.findByOrderIdOrderById(o.id);
		List<MaterialStatusView> materialViews = materials.stream()
			.map(m -> new MaterialStatusView(m.id, m.materialStatus, m.materialOrderDate, m.expectedReceiptDate,
					m.actualReceiptDate, m.inspectionStatus, m.remarks, m.createdAt, m.updatedAt))
			.toList();

		List<OrderProductionProgress> progress = productionProgress.findByOrderIdOrderById(o.id);
		Map<Long, UserRef> progressUsers = directory.users(ids(progress, p -> p.updatedByUserId));
		List<ProductionProgressView> progressViews = progress.stream()
			.map(p -> new ProductionProgressView(p.id, p.processStepName, p.plannedQuantity, p.completedQuantity,
					p.acceptedQuantity, p.rejectedQuantity, p.reworkQuantity, p.status, p.blockerReason,
					p.revisedDispatchDate, userName(progressUsers, p.updatedByUserId), p.updatedAt))
			.toList();

		return new OrderView(o.id, o.orderNumber, customer.name(), supplier.name(), o.linkedNpdProjectId,
				o.linkedRfqId, o.customerPoNo, o.supplierPoNo, o.partNumber, o.revision, o.orderedQuantity, o.dueDate,
				o.committedDispatchDate, o.actualDispatchDate, o.currentStatus, o.ownerUserId,
				userName(owners, o.ownerUserId), materialViews, progressViews, actions(me, o), o.createdAt,
				o.updatedAt);
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
