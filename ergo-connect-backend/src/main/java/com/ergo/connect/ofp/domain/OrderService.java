package com.ergo.connect.ofp.domain;

import static com.ergo.connect.common.security.Role.CUSTOMER_OFP;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.audit.AuditLogService.AuditEntry;
import com.ergo.connect.common.event.NotificationKind;
import com.ergo.connect.common.event.Notifier;
import com.ergo.connect.common.event.Recipients;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.ModuleAccess;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.ModuleSwitch;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.documents.api.DocumentStore;
import com.ergo.connect.documents.api.DocumentStore.DocumentInfo;
import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;
import com.ergo.connect.masterdata.api.MasterDataApi;
import com.ergo.connect.masterdata.api.MasterDataApi.Ref;
import com.ergo.connect.npd.api.NpdApi;
import com.ergo.connect.npd.api.NpdApi.HandoverProject;
import com.ergo.connect.quality.api.QualityApi;
import com.ergo.connect.ofp.domain.OfpDtos.CustomerOrderRow;
import com.ergo.connect.ofp.domain.OfpDtos.CustomerOrderView;
import com.ergo.connect.ofp.domain.OfpDtos.CustomerProductionProgressView;
import com.ergo.connect.ofp.domain.OfpDtos.ExternalShipmentView;
import com.ergo.connect.ofp.domain.OfpDtos.MaterialStatusForm;
import com.ergo.connect.ofp.domain.OfpDtos.MaterialStatusView;
import com.ergo.connect.ofp.domain.OfpDtos.OrderAction;
import com.ergo.connect.ofp.domain.OfpDtos.OrderDetail;
import com.ergo.connect.ofp.domain.OfpDtos.OrderPage;
import com.ergo.connect.ofp.domain.OfpDtos.OrderSummary;
import com.ergo.connect.ofp.domain.OfpDtos.ShipmentForm;
import com.ergo.connect.ofp.domain.OfpDtos.ShipmentView;
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Phase 4a: ERGO-only order core and production tracking up to Packaging in Progress (SRS §13.5, §14.6). Phase 4b:
 * the assigned supplier's own scoped edits (production progress, packaging/dispatch) and the inspection-gated
 * ERGO-only Dispatch Ready transition (SRS §10.7 "E**", §13.5.4, §14.6, §17.5). Phase 4c: shipments/tracking
 * ({@link Shipment}, §13.5.5), Dispatch Ready -> Dispatched -> In Transit -> Delivered -> Closed (each ERGO-only and
 * explicit, the last two gated on the inspection re-check and a POD on file respectively), and Customer OFP's
 * pure-read-only, two-way-anonymous view. Record scope (SRS §10.7, §8.2): ERGO OFP/Admin/Management see every order,
 * ERGO KAMs their assigned customers' orders, ERGO NPD every order that came out of an NPD project (it is itself an
 * unrestricted NPD viewer), a supplier role only orders where it is the assigned supplier (direct
 * {@code order.supplierCompanyId} match, mirroring NpdService's own {@code SUPPLIER_EDITORS}/{@code ownsActivity}
 * precedent — no new assignment table, ARCHITECTURE.md §8 2026-09-29), and Customer OFP only its own company's
 * orders ({@code order.customerCompanyId} match, same shape, 4c). {@link #allowed} is the single rule book: it
 * enforces every action and lists the caller's buttons — Customer OFP is never in {@code EDITORS}/
 * {@code SUPPLIER_EDITORS}, so its {@code actions} set is always empty. Audit-only for orders (no dedicated
 * status-history table, ARCHITECTURE.md §8 2026-09-28): {@link #release} recovers what a Hold interrupted from the
 * automatic field audit trail rather than a new column. Phase 5d: {@link #dispatchReadyGateMet} now reads the real
 * Quality module through {@code quality.api.QualityApi} instead of the 4b stub, a deliberate exception to this
 * codebase's usual "later module reaches into the earlier one" build-order direction (ARCHITECTURE.md §8
 * 2026-10-01) - Quality did not exist yet when this gate was first built.
 */
@Service
@Transactional
public class OrderService {

	private static final int MAX_PAGE_SIZE = 100;
	private static final int MAX_SEARCH_LENGTH = 100;

	/** The role sheet (ModuleAccess), OFP column. ERGO's owning roles ("O"): full create / edit / workflow rights. */
	private static final Set<Role> EDITORS = ModuleAccess.approvers(Module.OFP, CompanyType.ERGO);

	/** ERGO roles that act ("A" and up). Beyond EDITORS this adds ERGO Quality's part in OFP: hold, release and
	 * material (inspection) status (SRS §10.7 "E*"), nothing else. */
	private static final Set<Role> ERGO_ACTORS = ModuleAccess.actorsExcept(Module.OFP, CompanyType.ERGO, ERGO_KAM);

	/** Supplier roles with OFP access ("A"): edit rights limited to production progress and packaging/dispatch on
	 * their own assigned order only — never material status, order header fields, or the workflow actions. */
	private static final Set<Role> SUPPLIER_EDITORS = ModuleAccess.actors(Module.OFP, CompanyType.SUPPLIER);

	/** Supplier roles that can see their own orders (view level of the sheet); writes use {@link #SUPPLIER_EDITORS}. */
	private static final Set<Role> SUPPLIER_VIEWERS = ModuleAccess.viewers(Module.OFP, CompanyType.SUPPLIER);

	/** Customer roles with OFP access (CO / A / V): their own company's orders, read-only. The sheet's customer
	 * approval rights have no OFP action to attach to (no customer-side OFP workflow step exists). */
	private static final Set<Role> CUSTOMER_VIEWERS = ModuleAccess.viewers(Module.OFP, CompanyType.CUSTOMER);

	private final OrderRepository orders;

	private final OrderMaterialStatusRepository materialStatuses;

	private final OrderProductionProgressRepository productionProgress;

	private final OrderPackagingDispatchRepository packagingDispatches;

	private final ShipmentRepository shipments;

	private final NpdApi npdApi;

	private final QualityApi qualityApi;

	private final Directory directory;

	private final MasterDataApi masterData;

	private final DocumentStore documents;

	private final AuditLogService audit;

	private final JdbcTemplate jdbc;

	/** Field-injected (after construction) rather than a constructor parameter, since {@code @PersistenceContext}
	 * only supports field/setter injection. Used solely to flush the Order entity's pending status change before
	 * {@link #statusBeforeHold} reads it back via a raw JDBC query against {@code audit_logs} in the same
	 * transaction (e.g. {@link #hold} setting ON_HOLD and then immediately needing to resolve it for {@link
	 * #effectiveStatus} while building its own response) — without a flush, the automatic audit listener (which
	 * fires on Hibernate flush, not on field assignment) hasn't written that row yet. */
	@PersistenceContext
	private EntityManager entityManager;

	private final Notifier notifier;

	OrderService(OrderRepository orders, OrderMaterialStatusRepository materialStatuses,
			OrderProductionProgressRepository productionProgress, OrderPackagingDispatchRepository packagingDispatches,
			ShipmentRepository shipments, NpdApi npdApi, QualityApi qualityApi, Directory directory,
			MasterDataApi masterData, DocumentStore documents, AuditLogService audit, JdbcTemplate jdbc,
			Notifier notifier) {
		this.notifier = notifier;
		this.orders = orders;
		this.materialStatuses = materialStatuses;
		this.productionProgress = productionProgress;
		this.packagingDispatches = packagingDispatches;
		this.shipments = shipments;
		this.npdApi = npdApi;
		this.qualityApi = qualityApi;
		this.directory = directory;
		this.masterData = masterData;
		this.documents = documents;
		this.audit = audit;
		this.jdbc = jdbc;
	}

	// ---------------------------------------------------------------- reads

	/**
	 * One page of the caller's orders, newest first. The record scope is the same as ever (the supplier's own, the
	 * customer's own, ERGO KAM's accounts, ERGO NPD's handed-over orders, every order for the other ERGO roles) but
	 * applied in the query, then narrowed by {@code status} and {@code q}: order number, part number and the PO number
	 * the caller may see; for ERGO also the customer's or supplier's name. A customer never matches on the supplier
	 * (nor a supplier on the customer), the two-way anonymity rule.
	 */
	@Transactional(readOnly = true)
	public OrderPage list(int page, int size, String status, String q, boolean delayed) {
		CurrentUser me = CurrentUser.get();
		int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		int pageNo = Math.min(Math.max(page, 0), Integer.MAX_VALUE / pageSize - 1); // the offset must fit an int, else a 500
		boolean supplier = SUPPLIER_VIEWERS.contains(me.role());
		boolean customer = !supplier && CUSTOMER_VIEWERS.contains(me.role());
		Specification<Order> spec = scopeOf(me, supplier, customer);
		if (spec == null) {
			return new OrderPage(List.of(), 0, pageNo, pageSize);
		}
		if (status != null && !status.isBlank()) {
			OrderStatus wanted;
			try {
				wanted = OrderStatus.valueOf(status);
			}
			catch (IllegalArgumentException e) {
				throw ApiException.unprocessable("That status is not one you can filter by");
			}
			spec = spec.and((root, query, cb) -> cb.equal(root.get("currentStatus"), wanted));
		}
		if (delayed) { // same rule as the UI's "delayed" flag: not yet shipped and past its due date
			LocalDate today = LocalDate.now();
			spec = spec.and((root, query, cb) -> cb.and(cb.lessThan(root.<LocalDate>get("dueDate"), today),
					root.get("currentStatus").in(OrderStatus.NOT_YET_DISPATCHED)));
		}
		Specification<Order> search = searchOf(supplier, customer, q);
		if (search != null) {
			spec = spec.and(search);
		}
		Page<Order> found = orders.findAll(spec, PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "id")));
		List<Order> visible = found.getContent();
		List<OrderSummary> rows;
		if (supplier) {
			// Two-way anonymity: no customer lookup is ever made building the supplier's rows.
			rows = visible.stream().<OrderSummary>map(o -> new SupplierOrderRow(o.id, o.orderNumber, o.supplierPoNo,
					o.partNumber, o.revision, o.orderedQuantity, o.dueDate, o.currentStatus)).toList();
		}
		else if (customer) {
			// Phase 4c, same rule: no supplier lookup is ever made building the customer's rows.
			rows = visible.stream().<OrderSummary>map(o -> new CustomerOrderRow(o.id, o.orderNumber, o.customerPoNo,
					o.partNumber, o.revision, o.orderedQuantity, o.dueDate, o.currentStatus)).toList();
		}
		else {
			Map<Long, CompanyRef> customers = directory.companies(ids(visible, o -> o.customerCompanyId));
			Map<Long, CompanyRef> suppliers = directory.companies(ids(visible, o -> o.supplierCompanyId));
			Map<Long, UserRef> owners = directory.users(ids(visible, o -> o.ownerUserId));
			rows = visible.stream().<OrderSummary>map(o -> new OrderRow(o.id, o.orderNumber,
					name(customers, o.customerCompanyId), name(suppliers, o.supplierCompanyId), o.partNumber, o.revision,
					o.orderedQuantity, o.dueDate, o.currentStatus, userName(owners, o.ownerUserId))).toList();
		}
		return new OrderPage(rows, found.getTotalElements(), pageNo, pageSize);
	}

	/** Which orders the caller may see at all; {@code null} = none (and no query is made). */
	private Specification<Order> scopeOf(CurrentUser me, boolean supplier, boolean customer) {
		if (supplier) {
			return (root, query, cb) -> cb.equal(root.get("supplierCompanyId"), me.companyId());
		}
		if (customer) {
			return (root, query, cb) -> cb.equal(root.get("customerCompanyId"), me.companyId());
		}
		if (me.companyType() != CompanyType.ERGO || !ModuleAccess.canView(me.role(), Module.OFP)) {
			return null; // only ERGO roles reach the unrestricted branches below
		}
		return switch (me.role()) {
			case ERGO_KAM -> {
				Set<Long> accounts = directory.accountsOf(me.userId());
				yield accounts.isEmpty() ? null : (root, query, cb) -> root.get("customerCompanyId").in(accounts);
			}
			case ERGO_NPD -> (root, query, cb) -> cb.isNotNull(root.get("linkedNpdProjectId"));
			default -> (root, query, cb) -> cb.conjunction(); // the other ERGO roles with OFP access see every order
		};
	}

	private Specification<Order> searchOf(boolean supplier, boolean customer, String q) {
		String needle = q == null ? "" : q.replaceAll("\\p{Cntrl}", "").strip(); // a NUL makes Postgres reject the query
		if (needle.isEmpty()) {
			return null;
		}
		if (needle.length() > MAX_SEARCH_LENGTH) {
			needle = needle.substring(0, MAX_SEARCH_LENGTH);
		}
		String pattern = "%" + needle.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		// Company names are for ERGO's search only.
		Set<Long> companyIds = supplier || customer ? Set.of() : directory.companyIdsByName(needle);
		return (root, query, cb) -> {
			List<Predicate> any = new ArrayList<>();
			any.add(cb.like(cb.lower(root.<String>get("orderNumber")), pattern, '\\'));
			any.add(cb.like(cb.lower(root.<String>get("partNumber")), pattern, '\\'));
			if (!supplier) {
				any.add(cb.like(cb.lower(root.<String>get("customerPoNo")), pattern, '\\'));
			}
			if (!customer) {
				any.add(cb.like(cb.lower(root.<String>get("supplierPoNo")), pattern, '\\'));
			}
			if (!companyIds.isEmpty()) {
				any.add(root.get("customerCompanyId").in(companyIds));
				any.add(root.get("supplierCompanyId").in(companyIds));
			}
			return cb.or(any.toArray(new Predicate[0]));
		};
	}

	@Transactional(readOnly = true)
	public OrderDetail get(long id) {
		CurrentUser me = CurrentUser.get();
		return view(me, visible(me, id));
	}

	/** SRS §12.9.2/§15.5 audit trail tab. ERGO only, same rule as RFQ's history() (ARCHITECTURE.md §8 2026-10-05). */
	@Transactional(readOnly = true)
	public List<AuditEntry> history(long id) {
		CurrentUser me = CurrentUser.get();
		visible(me, id);
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.forbidden("The audit trail is internal to ERGO");
		}
		return audit.history("ofp", "Order", id);
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
		// what the customer's production order says is the default for what the form leaves open
		order.customerPoNo = text(form.customerPoNo()) != null ? text(form.customerPoNo()) : project.customerPoNumber();
		order.supplierPoNo = text(form.supplierPoNo());
		order.orderedQuantity = form.orderedQuantity();
		order.dueDate = form.dueDate() != null ? form.dueDate() : project.requiredDate();
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
		statusChanged(order, "Now " + pretty(order.currentStatus));
		return view(me, order);
	}

	/** SRS §14.6: reachable from any non-terminal status, reason required. */
	public OrderDetail hold(long id, String reason) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		require(OrderAction.HOLD, me, order);
		audit.reason(required(reason, "A reason is required to put an order on hold"));
		order.currentStatus = OrderStatus.ON_HOLD;
		statusChanged(order, "On hold");
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
		if (previous == OrderStatus.DISPATCH_READY) {
			requireQualityGateReadable();
		}
		if (previous == OrderStatus.DISPATCH_READY && !dispatchReadyGateMet(order.id)) {
			throw ApiException
				.conflict("Cannot release back to Dispatch Ready - not every material inspection has passed anymore");
		}
		reasonIfGiven(comment);
		order.currentStatus = previous;
		statusChanged(order, "Released from hold: " + pretty(previous));
		return view(me, order);
	}

	private OrderStatus statusBeforeHold(long orderId) {
		// Flush first: the automatic audit listener fires on Hibernate flush, not on field assignment, and
		// audit_logs is read here via a raw JDBC query that a plain Hibernate auto-flush wouldn't trigger — without
		// this, a same-transaction caller (e.g. hold() resolving its own new ON_HOLD state while building its
		// response) would not yet see the very audit row it just caused.
		entityManager.flush();
		return audit.history("ofp", "Order", orderId).stream()
			.filter(e -> "currentStatus".equals(e.field()) && "ON_HOLD".equals(e.newValue()))
			.reduce((first, last) -> last) // the most recent Hold, in case of more than one over the order's life
			.map(e -> OrderStatus.valueOf(e.oldValue()))
			.orElseThrow(() -> ApiException.conflict("No prior status was recorded to release this order back to"));
	}

	/** M2/M4 fix (ARCHITECTURE.md §8 2026-09-29, "Security review of OFP 4c"): the order's underlying progress
	 * status, resolved through ON_HOLD to whatever status was interrupted (same recovery {@link #release} uses) so a
	 * Hold can't be used to dodge the "frozen once dispatched" gates below. */
	private OrderStatus effectiveStatus(Order order) {
		return order.currentStatus == OrderStatus.ON_HOLD ? statusBeforeHold(order.id) : order.currentStatus;
	}

	/** True while the order has not yet reached Dispatched — the cutoff for material-status/production-progress/
	 * packaging-dispatch edits (M4/L1 fix, ARCHITECTURE.md §8 2026-09-29): once dispatched, nothing upstream of
	 * dispatch should still be silently editable, since the customer can now see production-progress figures. */
	private static boolean beforeDispatch(OrderStatus status) {
		return OrderStatus.SEQUENCE.contains(status) || status == OrderStatus.DISPATCH_READY;
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
		requireQualityGateReadable();
		if (!dispatchReadyGateMet(order.id)) {
			throw ApiException
				.unprocessable("All material inspections must pass before the order can be marked dispatch ready.");
		}
		reasonIfGiven(comment);
		order.currentStatus = OrderStatus.DISPATCH_READY;
		return view(me, order);
	}

	/** Phase 5d: real read from the Quality module through {@link QualityApi}, replacing the 4b stub that reused
	 * {@code order_material_status.inspection_status} (ARCHITECTURE.md §8 2026-09-29) - that field tracks incoming
	 * material receipt, not a dispatch sign-off, and still does so for display (see {@link OrderMaterialStatus}'s own
	 * doc comment); it is untouched by this gate. True once every inspection Quality has raised against this order
	 * (SRS's "Final" inspection, {@code linkedModule = OFP}) has resolved to PASSED or RELEASED_WITH_DEVIATION, and
	 * at least one such inspection exists - an order with none recorded has not "passed", it simply hasn't been
	 * checked yet (same "zero rows is not a pass" rule the stub already used). */
	private boolean dispatchReadyGateMet(long orderId) {
		return qualityApi.ofpDispatchGateMet(orderId);
	}

	/** The gate is Quality's. While that module is hidden it cannot be evaluated, so it fails closed - with this
	 * reason rather than a misleading "inspections have not passed". Nothing has been changed when this is thrown. */
	private static void requireQualityGateReadable() {
		if (!ModuleSwitch.readable(Module.QUALITY)) {
			throw ApiException.conflict("The dispatch gate is checked by the Quality module, which is not available "
					+ "right now. Nothing was changed; repeat this once it is back on.");
		}
	}

	/** SRS §14.6/§17.5, Phase 4c: Dispatch Ready -> Dispatched, ERGO OFP/Admin only. Closes the deferred half of
	 * security-review finding M2 (ARCHITECTURE.md §8 2026-09-29): re-runs the same inspection gate {@link
	 * #dispatchReady} checked, since material status can still change after DISPATCH_READY is reached (an order can
	 * sit at that status for a while before this action is actually taken). */
	public OrderDetail dispatch(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		if (!EDITORS.contains(me.role()) || order.currentStatus != OrderStatus.DISPATCH_READY) {
			throw ApiException.conflict("This action is not available for this order right now");
		}
		requireQualityGateReadable();
		if (!dispatchReadyGateMet(order.id)) {
			throw ApiException.unprocessable(
					"All material inspections must still pass before the order can be dispatched.");
		}
		reasonIfGiven(comment);
		order.currentStatus = OrderStatus.DISPATCHED;
		// L5 fix (ARCHITECTURE.md §8 2026-09-29): actualDispatchDate was never set anywhere before this.
		order.actualDispatchDate = LocalDate.now();
		notifier.send(NotificationKind.DISPATCH_RECORDED, "ofp", order.id, order.orderNumber, null, ergoAndCustomer(order));
		return view(me, order);
	}

	/** SRS §14.6, Phase 4c: Dispatched -> In Transit, ERGO OFP/Admin only. Deliberately independent of any
	 * shipment's own {@code status} field (ARCHITECTURE.md §8 2026-09-29): updating a shipment row never moves
	 * {@code orders.currentStatus} by itself, the same "child rows never drive the order's own status" shape every
	 * other transition in this module already follows. */
	public OrderDetail markInTransit(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		require(OrderAction.MARK_IN_TRANSIT, me, order);
		reasonIfGiven(comment);
		order.currentStatus = OrderStatus.IN_TRANSIT;
		statusChanged(order, "In transit");
		return view(me, order);
	}

	/** SRS §14.6, Phase 4c: In Transit -> Delivered, ERGO OFP/Admin only. Confirmed decision (ARCHITECTURE.md §8
	 * 2026-09-29): refused unless at least one shipment on this order already has a POD document on file. */
	public OrderDetail markDelivered(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		if (!EDITORS.contains(me.role()) || order.currentStatus != OrderStatus.IN_TRANSIT) {
			throw ApiException.conflict("This action is not available for this order right now");
		}
		if (!podOnFile(order.id)) {
			throw ApiException.unprocessable(
					"A proof-of-delivery document must be attached to a shipment before the order can be marked delivered.");
		}
		reasonIfGiven(comment);
		order.currentStatus = OrderStatus.DELIVERED;
		notifier.send(NotificationKind.DELIVERY_CONFIRMED, "ofp", order.id, order.orderNumber, null, ergoAndCustomer(order));
		return view(me, order);
	}

	/** At least one {@code shipments} row for this order has a POD document attached. */
	private boolean podOnFile(long orderId) {
		return shipments.findByOrderIdOrderById(orderId).stream().anyMatch(s -> s.podDocumentId != null);
	}

	/** SRS §14.6, Phase 4c: Delivered -> Closed, ERGO OFP/Admin only, no automatic transition (confirmed decision,
	 * ARCHITECTURE.md §8 2026-09-29) — every other terminal-state transition in this system (RFQ Closed, NPD Closed)
	 * is likewise explicit. */
	public OrderDetail closeOrder(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, id);
		require(OrderAction.CLOSE_ORDER, me, order);
		reasonIfGiven(comment);
		order.currentStatus = OrderStatus.CLOSED;
		statusChanged(order, "Closed");
		return view(me, order);
	}

	// ---------------------------------------------------------------- shipments (SRS §13.5.5), Phase 4c

	private static final Set<OrderStatus> SHIPMENT_STATUSES = EnumSet.of(OrderStatus.DISPATCHED,
			OrderStatus.IN_TRANSIT, OrderStatus.DELIVERED);

	/** How far along a shipment's own declared {@code status} may be relative to the order it belongs to (security
	 * review M2, ARCHITECTURE.md §8 2026-09-29): a shipment can never claim to be further along than the order
	 * itself has actually reached. */
	private static final Map<OrderStatus, Integer> SHIPMENT_RANK = Map.of(OrderStatus.DISPATCHED, 0,
			OrderStatus.IN_TRANSIT, 1, OrderStatus.DELIVERED, 2);

	/** The Documents-module key PODs are stored under (M1 fix, ARCHITECTURE.md §8 2026-09-29): distinct from
	 * {@code "OFP"} (keyed by order id, used by the still-unbuilt 4b "attachments" grant) so the two families of
	 * {@code linked_record_id} values — order ids and shipment ids — can never collide. */
	private static final String POD_MODULE = "OFP_SHIPMENT";

	/** ERGO OFP/Admin only (§10.7's literal matrix gives Supplier OFP "RV" only, and its "E**" footnote never names a
	 * shipment field). M2 fix: only once the order itself has reached Dispatched (see {@link #allowed}). */
	public OrderDetail createShipment(long orderId, ShipmentForm form) {
		CurrentUser me = CurrentUser.get();
		Order order = visible(me, orderId);
		require(OrderAction.CREATE_SHIPMENT, me, order);
		masterData.requireActive(Ref.SHIPMENT_MODE, form.shipmentModeId(), "Shipment mode");
		OrderStatus status = validatedShipmentStatus(form.status(), order);
		Shipment shipment = new Shipment();
		shipment.orderId = order.id;
		applyShipment(shipment, form, status, me);
		shipments.save(shipment);
		return view(me, order);
	}

	public OrderDetail updateShipment(long shipmentId, ShipmentForm form) {
		CurrentUser me = CurrentUser.get();
		Shipment shipment = shipments.findById(shipmentId).orElseThrow(() -> ApiException.notFound("Shipment"));
		Order order = visible(me, shipment.orderId);
		require(OrderAction.UPDATE_SHIPMENT, me, order);
		masterData.requireActive(Ref.SHIPMENT_MODE, form.shipmentModeId(), "Shipment mode");
		OrderStatus status = validatedShipmentStatus(form.status(), order);
		applyShipment(shipment, form, status, me);
		return view(me, order);
	}

	/** M2 fix: the requested status must be one of DISPATCHED/IN_TRANSIT/DELIVERED (matching the DB CHECK) and never
	 * ahead of what the order itself has reached (resolved through ON_HOLD via {@link #effectiveStatus}, the same
	 * recovery {@link #release} uses, so a Hold can't be used to claim a shipment is further along than it is). */
	private OrderStatus validatedShipmentStatus(OrderStatus requested, Order order) {
		OrderStatus status = requested == null ? OrderStatus.DISPATCHED : requested;
		if (!SHIPMENT_STATUSES.contains(status)) {
			throw ApiException.unprocessable("Shipment status must be Dispatched, In Transit or Delivered");
		}
		OrderStatus reached = effectiveStatus(order);
		if (SHIPMENT_RANK.get(status) > SHIPMENT_RANK.get(reached)) {
			throw ApiException.unprocessable(
					"A shipment cannot be marked further along than the order itself (order is currently " + reached
							+ ")");
		}
		return status;
	}

	private static void applyShipment(Shipment shipment, ShipmentForm form, OrderStatus status, CurrentUser me) {
		shipment.shipmentModeId = form.shipmentModeId();
		shipment.transporterName = text(form.transporterName());
		shipment.forwarderName = text(form.forwarderName());
		shipment.trackingNumber = text(form.trackingNumber());
		shipment.awbBlNo = text(form.awbBlNo());
		shipment.eta = form.eta();
		shipment.actualDeliveryDate = form.actualDeliveryDate();
		shipment.status = status;
		// L2 fix (ARCHITECTURE.md §8 2026-09-29): receipt confirmation only makes sense once the shipment itself
		// has actually reached Delivered. ERGO-set on the customer's behalf: no direct customer write path exists.
		if (form.receiptConfirmedByCustomerFlag() && status != OrderStatus.DELIVERED) {
			throw ApiException.unprocessable("Receipt can only be confirmed once the shipment is Delivered");
		}
		if (form.receiptConfirmedByCustomerFlag() && !shipment.receiptConfirmedByCustomerFlag) {
			shipment.receiptConfirmedAt = Instant.now();
		}
		else if (!form.receiptConfirmedByCustomerFlag()) {
			shipment.receiptConfirmedAt = null;
		}
		shipment.receiptConfirmedByCustomerFlag = form.receiptConfirmedByCustomerFlag();
		shipment.updatedByUserId = me.userId();
	}

	/** POD upload (SRS §13.5.5, confirmed decision ARCHITECTURE.md §8 2026-09-29): reuses the Documents module
	 * rather than a raw byte column, ERGO OFP/Admin only, never a plain client-supplied document id. M1 fix: stored
	 * under {@link #POD_MODULE}, not {@code "OFP"}, to avoid colliding with the order-keyed "attachments" grant. */
	public OrderDetail uploadPod(long shipmentId, String fileName, byte[] content) {
		CurrentUser me = CurrentUser.get();
		Shipment shipment = shipments.findById(shipmentId).orElseThrow(() -> ApiException.notFound("Shipment"));
		Order order = visible(me, shipment.orderId);
		require(OrderAction.UPDATE_SHIPMENT, me, order);
		DocumentInfo info = documents.store(POD_MODULE, shipment.id, fileName, content, Visibility.INTERNAL);
		shipment.podDocumentId = info.id();
		// The POD file is ERGO-only (client decision, ARCHITECTURE.md §8 2026-09-29): only ERGO colleagues are told.
		notifier.send(NotificationKind.DOCUMENT_UPLOADED, "document", info.id(), order.orderNumber, "Proof of delivery",
				Recipients.ergo(Role.ERGO_ADMIN, Role.ERGO_OFP));
		return view(me, order);
	}

	/** L5 fix (ARCHITECTURE.md §8 2026-09-29): the POD download the (then) "CUSTOMER" visibility was granting but
	 * never had an endpoint for. Stored INTERNAL so the Documents module never shows it outside ERGO either; record scope only (ERGO's own scope); the customer and supplier are
	 * excluded entirely at the controller's role layer, consistent with H1 removing their POD visibility. */
	@Transactional(readOnly = true)
	public PodFile downloadPod(long shipmentId) {
		CurrentUser me = CurrentUser.get();
		Shipment shipment = shipments.findById(shipmentId).orElseThrow(() -> ApiException.notFound("Shipment"));
		visible(me, shipment.orderId);
		if (shipment.podDocumentId == null) {
			throw ApiException.notFound("Proof of delivery document");
		}
		DocumentInfo info = documents.find(POD_MODULE, shipment.id, shipment.podDocumentId)
			.orElseThrow(() -> ApiException.notFound("Proof of delivery document"));
		return new PodFile(info, documents.content(info));
	}

	public record PodFile(DocumentInfo info, byte[] content) {
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

	// ---------------------------------------------------------------- notifications (SRS §14.9)

	private static Recipients ergoAndCustomer(Order order) {
		return Recipients.ergo(Role.ERGO_ADMIN, Role.ERGO_OFP)
			.and(Recipients.company(order.customerCompanyId, CUSTOMER_VIEWERS.toArray(Role[]::new)));
	}

	/** Everyone on the order: ERGO OFP, the customer's OFP users and the supplier's staff. The detail is the new status
	 * only - never the reason an order was put on hold. */
	private void statusChanged(Order order, String detail) {
		notifier.send(NotificationKind.ORDER_STATUS_CHANGED, "ofp", order.id, order.orderNumber, detail,
				ergoAndCustomer(order).and(Recipients.company(order.supplierCompanyId, SUPPLIER_VIEWERS.toArray(Role[]::new))));
	}

	private static String pretty(OrderStatus status) {
		String text = status.name().toLowerCase().replace('_', ' ');
		return Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	// ---------------------------------------------------------------- rules

	/** Record scope. Out-of-scope orders do not exist for the caller (404, not 403). */
	Order visible(CurrentUser me, long id) {
		Order order = orders.findById(id).orElse(null);
		if (!inScope(me, order)) {
			throw ApiException.notFound("Order");
		}
		return order;
	}

	/** The boolean half of {@link #visible}, for a caller that must not throw on "out of scope" (a thrown {@code
	 * ApiException} from a {@code @Transactional} method marks the physical transaction rollback-only the moment it
	 * is thrown, even if the caller catches it immediately afterward). Used by {@code
	 * OfpApiService#shipmentVisibleToCaller}, the documents module's non-throwing scope probe (ARCHITECTURE.md §8,
	 * 2026-10-02 Phase 6a). */
	boolean inScope(CurrentUser me, long id) {
		return inScope(me, orders.findById(id).orElse(null));
	}

	/** The POD-specific restriction (ARCHITECTURE.md §8, Phase 6a security review C1 fix): mirrors {@code
	 * OrderController#POD_READERS} — ERGO roles only, each still confined to its own record scope. Suppliers and
	 * customers never see the POD file itself (only receipt fields), unlike generic order {@link #inScope}, so the
	 * documents module's {@code OfpApiService#shipmentVisibleToCaller} probe must use this, not {@link #inScope}. */
	boolean podReaderInScope(CurrentUser me, long orderId) {
		Order order = orders.findById(orderId).orElse(null);
		if (order == null) {
			return false;
		}
		if (me.companyType() != CompanyType.ERGO || !ModuleAccess.canView(me.role(), Module.OFP)) {
			return false;
		}
		return switch (me.role()) {
			case ERGO_KAM -> directory.accountsOf(me.userId()).contains(order.customerCompanyId);
			case ERGO_NPD -> order.linkedNpdProjectId != null;
			default -> true;
		};
	}

	/** N1 (second security review): whether this module's own POD upload rule ({@code UPDATE_SHIPMENT}) currently
	 * allows the caller to put a new POD on this shipment's order - used by the documents module's {@code replace}. */
	boolean shipmentUploadOpen(CurrentUser me, long orderId) {
		Order order = orders.findById(orderId).orElse(null);
		return order != null && allowed(OrderAction.UPDATE_SHIPMENT, me, order);
	}

	private boolean inScope(CurrentUser me, Order order) {
		if (order == null || !ModuleAccess.canView(me.role(), Module.OFP)) {
			return false;
		}
		return switch (me.companyType()) {
			case ERGO -> switch (me.role()) {
				case ERGO_KAM -> directory.accountsOf(me.userId()).contains(order.customerCompanyId);
				case ERGO_NPD -> order.linkedNpdProjectId != null;
				default -> true;
			};
			case SUPPLIER -> me.companyId() == order.supplierCompanyId;
			// Phase 4c: pure read-only, own company's orders only (SRS §10.7 "RV"); write actions still 409/403
			// below since no customer role appears in EDITORS/SUPPLIER_EDITORS.
			case CUSTOMER -> me.companyId() == order.customerCompanyId;
		};
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
		boolean ergoActor = ERGO_ACTORS.contains(me.role());
		boolean ownSupplier = SUPPLIER_EDITORS.contains(me.role()) && me.companyId() == order.supplierCompanyId;
		OrderStatus status = order.currentStatus;
		return switch (action) {
			case EDIT -> editor && status != OrderStatus.CLOSED;
			case ADVANCE -> editor && OrderStatus.SEQUENCE.contains(status)
					&& !status.equals(OrderStatus.SEQUENCE.getLast());
			// Hold / release are inspection-clearance decisions too (SRS §10.7 "E*"): ERGO Quality acts here as well.
			case HOLD -> ergoActor && status != OrderStatus.CLOSED && status != OrderStatus.ON_HOLD;
			case RELEASE -> ergoActor && status == OrderStatus.ON_HOLD;
			// M4/L1 fix (ARCHITECTURE.md §8 2026-09-29): frozen once the order has (effectively) reached Dispatched —
			// closes the gap the 4b security review deferred twice already, now load-bearing since the customer can
			// see production-progress figures (4c). Resolved through ON_HOLD so a Hold can't dodge the freeze.
			case UPDATE_MATERIAL_STATUS -> ergoActor && beforeDispatch(effectiveStatus(order));
			// SRS §10.7 "E**": the assigned supplier, alongside ERGO OFP/Admin, but never Hold/Release/Advance/
			// Edit/material status (point 1/5, ARCHITECTURE.md §8 2026-09-29). Security review L2 (4b, ARCHITECTURE.md
			// §8 2026-09-29): a supplier's own write is additionally blocked while ON_HOLD (an order on hold is meant
			// to be frozen for whatever reason it's held, not quietly progressed by the supplier); ERGO's own write
			// stays allowed while ON_HOLD (it may need to document why it's held) but, per M4/L1 above, only while the
			// order hasn't effectively reached Dispatched yet either way.
			case UPDATE_PRODUCTION_PROGRESS, UPDATE_PACKAGING_DISPATCH -> editor && beforeDispatch(effectiveStatus(order))
					|| ownSupplier && status != OrderStatus.ON_HOLD && beforeDispatch(status);
			case MARK_DISPATCH_READY -> editor && status == OrderStatus.PACKAGING_IN_PROGRESS
					&& dispatchReadyGateMet(order.id);
			// Phase 4c. CUSTOMER_OFP is never in `editor` or `ownSupplier`, so every case below (and every case
			// above) evaluates false for it: Customer OFP's `actions` set is always empty (confirmed decision,
			// ARCHITECTURE.md §8 2026-09-29).
			// M2 fix (ARCHITECTURE.md §8 2026-09-29): shipment writes only once the order has (effectively) reached
			// Dispatched — before that, marking a shipment Delivered (with a POD) could satisfy the Delivered gate on
			// an order that was never actually dispatched.
			case CREATE_SHIPMENT, UPDATE_SHIPMENT -> editor && SHIPMENT_STATUSES.contains(effectiveStatus(order));
			case DISPATCH -> editor && status == OrderStatus.DISPATCH_READY && dispatchReadyGateMet(order.id);
			case MARK_IN_TRANSIT -> editor && status == OrderStatus.DISPATCHED;
			case MARK_DELIVERED -> editor && status == OrderStatus.IN_TRANSIT && podOnFile(order.id);
			case CLOSE_ORDER -> editor && status == OrderStatus.DELIVERED;
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

	/** The single per-audience view builder: a supplier caller gets SupplierOrderView, a CUSTOMER_OFP caller gets
	 * CustomerOrderView (built without ever looking up the supplier), everyone else reaching here is ERGO
	 * (visible() already 404s every other role). */
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

		if (me.companyType() == CompanyType.SUPPLIER) {
			List<SupplierMaterialStatusView> supplierMaterialViews = materials.stream()
				.map(m -> new SupplierMaterialStatusView(m.id, m.materialStatus, m.materialOrderDate,
						m.expectedReceiptDate, m.actualReceiptDate, m.inspectionStatus, m.createdAt, m.updatedAt))
				.toList();
			return new SupplierOrderView(o.id, o.orderNumber, o.supplierPoNo, o.partNumber, o.revision,
					o.orderedQuantity, o.dueDate, o.committedDispatchDate, o.actualDispatchDate, o.currentStatus,
					supplierMaterialViews, progressViews, dispatchViews, externalShipmentViews(o.id), actions(me, o),
					o.createdAt, o.updatedAt);
		}

		if (me.companyType() == CompanyType.CUSTOMER) {
			List<SupplierMaterialStatusView> customerMaterialViews = materials.stream()
				.map(m -> new SupplierMaterialStatusView(m.id, m.materialStatus, m.materialOrderDate,
						m.expectedReceiptDate, m.actualReceiptDate, m.inspectionStatus, m.createdAt, m.updatedAt))
				.toList();
			List<CustomerProductionProgressView> customerProgressViews = progress.stream()
				.map(p -> new CustomerProductionProgressView(p.id, p.processStepName, p.plannedQuantity,
						p.completedQuantity, p.acceptedQuantity, p.rejectedQuantity, p.reworkQuantity, p.status,
						p.revisedDispatchDate, p.updatedAt))
				.toList();
			return new CustomerOrderView(o.id, o.orderNumber, o.customerPoNo, o.partNumber, o.revision,
					o.orderedQuantity, o.dueDate, o.committedDispatchDate, o.actualDispatchDate, o.currentStatus,
					customerMaterialViews, customerProgressViews, externalShipmentViews(o.id), actions(me, o),
					o.createdAt, o.updatedAt);
		}

		// M3 fix (ARCHITECTURE.md §8 2026-09-29): this branch is only ever reached for ERGO roles today because
		// visible() already 404s every other role — but that reliance was implicit. Assert it explicitly, so a
		// future widening of visible() to another customer/supplier role can't silently fall through to the
		// unrestricted ERGO view if this method is forgotten.
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.forbidden("Access denied");
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
				userName(owners, o.ownerUserId), materialViews, progressViews, dispatchViews, shipmentViews(o.id),
				actions(me, o), o.createdAt, o.updatedAt);
	}

	/** ERGO-only (H1/L3 fixes, ARCHITECTURE.md §8 2026-09-29): carries the POD reference and who last updated the
	 * row. {@code podFileName} is resolved from the Documents module purely for display. Never used for the
	 * supplier's or customer's own view — see {@link #externalShipmentViews}. */
	private List<ShipmentView> shipmentViews(long orderId) {
		List<Shipment> rows = shipments.findByOrderIdOrderById(orderId);
		Map<Long, UserRef> updaters = directory.users(ids(rows, s -> s.updatedByUserId));
		return rows.stream()
			.map(s -> new ShipmentView(s.id, s.shipmentModeId, s.transporterName, s.forwarderName, s.trackingNumber,
					s.awbBlNo, s.eta, s.actualDeliveryDate, s.podDocumentId, podFileName(s), s.receiptConfirmedByCustomerFlag,
					s.receiptConfirmedAt, s.status, userName(updaters, s.updatedByUserId), s.updatedAt))
			.toList();
	}

	/** Supplier's and customer's shipment view (H1/L3 fixes, ARCHITECTURE.md §8 2026-09-29): no POD reference (its
	 * file name routinely names the consignee) and no {@code updatedBy} (always an ERGO employee's name) — never
	 * even looks the POD document up, unlike {@link #shipmentViews}. */
	private List<ExternalShipmentView> externalShipmentViews(long orderId) {
		return shipments.findByOrderIdOrderById(orderId).stream()
			.map(s -> new ExternalShipmentView(s.id, s.shipmentModeId, s.transporterName, s.forwarderName,
					s.trackingNumber, s.awbBlNo, s.eta, s.actualDeliveryDate, s.receiptConfirmedByCustomerFlag,
					s.receiptConfirmedAt, s.status, s.updatedAt))
			.toList();
	}

	private String podFileName(Shipment s) {
		return s.podDocumentId == null ? null
				: documents.find(POD_MODULE, s.id, s.podDocumentId).map(DocumentInfo::fileName).orElse(null);
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
		return com.ergo.connect.common.validation.Blank.is(value) ? null : value.trim();
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
