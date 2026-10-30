package com.ergo.connect.npd.domain;

import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;

import com.ergo.connect.common.audit.AuditLogService;
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
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;
import com.ergo.connect.npd.domain.NpdDtos.NpdView;
import com.ergo.connect.npd.domain.ProductionPoDtos.Assignees;
import com.ergo.connect.npd.domain.ProductionPoDtos.CreateProjectForm;
import com.ergo.connect.npd.domain.ProductionPoDtos.EligibleProject;
import com.ergo.connect.npd.domain.ProductionPoDtos.ProductionPoAction;
import com.ergo.connect.npd.domain.ProductionPoDtos.ProductionPoPage;
import com.ergo.connect.npd.domain.ProductionPoDtos.ProductionPoEditForm;
import com.ergo.connect.npd.domain.ProductionPoDtos.ProductionPoForm;
import com.ergo.connect.npd.domain.ProductionPoDtos.ProductionPoView;

/**
 * Flow doc step 11. Customer OFP releases a production order for a part whose sample the customer approved and submits
 * it under OFP; the ERGO KAM (or ERGO Management) is notified and either returns it with a comment or accepts it by
 * creating the OFP project, assigning ERGO OFP and ERGO Quality. Layer 1 is the OFP column of the role sheet. Record
 * scope: a customer only its own company's production orders, a KAM only its assigned accounts', the other ERGO roles
 * with OFP access every one; a supplier never reaches a production order.
 */
@Service
@Transactional
public class ProductionPoService {

	/** Customer roles that raise a production order: the customer-side final-approval role of OFP (a PO is a commitment). */
	private static final Set<Role> RAISERS = ModuleAccess.approvers(Module.OFP, CompanyType.CUSTOMER);

	private static final int MAX_PAGE_SIZE = 100;

	private static final int MAX_SEARCH_LENGTH = 100;

	private static final Set<Role> CUSTOMER_VIEWERS = ModuleAccess.viewers(Module.OFP, CompanyType.CUSTOMER);

	/** Every ERGO role with OFP access except the account-scoped KAM. */
	private static final Set<Role> FULL_VIEWERS = ModuleAccess.viewersExcept(Module.OFP, CompanyType.ERGO, ERGO_KAM);

	private final ProductionPoRepository productionPos;

	private final NpdProjectRepository projects;

	private final NpdService npdService;

	private final Directory directory;

	private final AuditLogService audit;

	private final JdbcTemplate jdbc;

	private final Notifier notifier;

	ProductionPoService(ProductionPoRepository productionPos, NpdProjectRepository projects, NpdService npdService,
			Directory directory, AuditLogService audit, JdbcTemplate jdbc, Notifier notifier) {
		this.productionPos = productionPos;
		this.projects = projects;
		this.npdService = npdService;
		this.directory = directory;
		this.audit = audit;
		this.jdbc = jdbc;
		this.notifier = notifier;
	}

	// ---------------------------------------------------------------- reads

	/**
	 * One page of the production PO list. The record scope (own company, every account for ERGO, the KAM's accounts) is
	 * applied in the query, then narrowed by {@code status} and {@code q}: PO number, part number, the customer's PO
	 * number and, for ERGO only, the customer's name.
	 */
	@Transactional(readOnly = true)
	public ProductionPoPage list(int page, int size, String status, String q) {
		CurrentUser me = CurrentUser.get();
		int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		int pageNo = Math.min(Math.max(page, 0), Integer.MAX_VALUE / pageSize - 1); // the offset must fit an int, else a 500
		Specification<ProductionPo> spec = scopeOf(me);
		if (spec == null) {
			return new ProductionPoPage(List.of(), 0, pageNo, pageSize);
		}
		if (status != null && !status.isBlank()) {
			ProductionPoStatus wanted;
			try {
				wanted = ProductionPoStatus.valueOf(status);
			}
			catch (IllegalArgumentException e) {
				throw ApiException.unprocessable("That status is not one you can filter by");
			}
			spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), wanted));
		}
		Specification<ProductionPo> search = searchOf(me, q);
		if (search != null) {
			spec = spec.and(search);
		}
		Page<ProductionPo> found = productionPos.findAll(spec, PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "id")));
		return new ProductionPoPage(views(me, found.getContent()), found.getTotalElements(), pageNo, pageSize);
	}

	/** The query form of {@link #inScope}; {@code null} = none (and no query is made). */
	private Specification<ProductionPo> scopeOf(CurrentUser me) {
		if (!ModuleAccess.canView(me.role(), Module.OFP) || !ModuleSwitch.readable(Module.OFP)) {
			return null;
		}
		if (CUSTOMER_VIEWERS.contains(me.role())) {
			return (root, query, cb) -> cb.equal(root.get("customerCompanyId"), me.companyId());
		}
		if (FULL_VIEWERS.contains(me.role())) {
			return (root, query, cb) -> cb.conjunction();
		}
		if (me.role() == ERGO_KAM) {
			Set<Long> accounts = directory.accountsOf(me.userId());
			return accounts.isEmpty() ? null : (root, query, cb) -> root.get("customerCompanyId").in(accounts);
		}
		return null;
	}

	private Specification<ProductionPo> searchOf(CurrentUser me, String q) {
		String needle = q == null ? "" : q.replaceAll("\\p{Cntrl}", "").strip(); // a NUL makes Postgres reject the query
		if (needle.isEmpty()) {
			return null;
		}
		if (needle.length() > MAX_SEARCH_LENGTH) {
			needle = needle.substring(0, MAX_SEARCH_LENGTH);
		}
		String pattern = "%" + needle.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		// a customer already knows its own name; company names are searched for ERGO only
		Set<Long> companyIds = me.companyType() == CompanyType.ERGO ? directory.companyIdsByName(needle) : Set.of();
		return (root, query, cb) -> {
			List<Predicate> any = new ArrayList<>();
			any.add(cb.like(cb.lower(root.<String>get("productionPoNumber")), pattern, '\\'));
			any.add(cb.like(cb.lower(root.<String>get("partNumber")), pattern, '\\'));
			any.add(cb.like(cb.lower(root.<String>get("customerPoNumber")), pattern, '\\'));
			if (!companyIds.isEmpty()) {
				any.add(root.get("customerCompanyId").in(companyIds));
			}
			return cb.or(any.toArray(new Predicate[0]));
		};
	}

	@Transactional(readOnly = true)
	public ProductionPoView get(long id) {
		CurrentUser me = CurrentUser.get();
		return views(me, List.of(visible(me, id))).getFirst();
	}

	/** Who the KAM can assign a new OFP project to: the active ERGO OFP and ERGO Quality users (just id and name). */
	@Transactional(readOnly = true)
	public Assignees assignees() {
		CurrentUser me = CurrentUser.get();
		if (!isDesk(me)) {
			throw ApiException.forbidden("Only the ERGO Key Account Manager or ERGO Management can assign a project");
		}
		List<UserRef> ergo = directory.activeUsersOfType(CompanyType.ERGO);
		Map<Long, Set<Role>> roles = directory.rolesOf(ergo.stream().map(UserRef::id).toList());
		return new Assignees(withRole(ergo, roles, Role.ERGO_OFP), withRole(ergo, roles, Role.ERGO_QUALITY));
	}

	private static List<UserRef> withRole(List<UserRef> users, Map<Long, Set<Role>> roles, Role role) {
		return users.stream().filter(u -> roles.getOrDefault(u.id(), Set.of()).contains(role)).toList();
	}

	/** The audit trail of a production order (who raised, returned, corrected, accepted it); ERGO only. */
	@Transactional(readOnly = true)
	public List<AuditLogService.AuditEntry> history(long id) {
		CurrentUser me = CurrentUser.get();
		visible(me, id);
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.forbidden("The audit trail is internal to ERGO");
		}
		return audit.history("npd", "ProductionPo", id);
	}

	/** The customer's approved sample projects that have no open production order: where Customer OFP starts. */
	@Transactional(readOnly = true)
	public List<EligibleProject> eligibleProjects() {
		CurrentUser me = CurrentUser.get();
		if (!RAISERS.contains(me.role()) || !ModuleSwitch.readable(Module.NPD)) {
			return List.of();
		}
		List<NpdProject> approved = projects.findByCustomerCompanyIdInOrderByIdDesc(Set.of(me.companyId()))
			.stream()
			.filter(ProductionPoService::sampleApproved)
			.toList();
		if (approved.isEmpty()) {
			return List.of();
		}
		Set<Long> taken = openOrders(approved.stream().map(p -> p.id).toList());
		Set<ProductionPoAction> actions = ModuleSwitch.writable(Module.OFP) ? EnumSet.of(ProductionPoAction.RAISE) : Set.of();
		return approved.stream()
			.filter(p -> !taken.contains(p.id))
			.map(p -> new EligibleProject(p.id, p.npdNumber, p.partNumber, p.revision, actions))
			.toList();
	}

	// ---------------------------------------------------------------- customer: raise and correct

	public ProductionPoView raise(ProductionPoForm form) {
		CurrentUser me = CurrentUser.get();
		if (!RAISERS.contains(me.role())) {
			throw ApiException.forbidden("Only Customer OFP can release a production order");
		}
		ModuleSwitch.requireWritable(Module.OFP);
		if (!ModuleSwitch.readable(Module.NPD)) {
			throw ApiException.conflict("A production order continues an approved sample project, which is in the NPD module, "
					+ "and that is not available right now. Nothing was changed; it can be repeated once it is back on.");
		}
		// the customer's own approved sample projects only; another company's project does not exist for the caller
		NpdProject source = projects.findById(form.sourceProjectId())
			.filter(p -> p.customerCompanyId.equals(me.companyId()) && p.kind == ProjectKind.SAMPLE)
			.orElseThrow(() -> ApiException.notFound("Project"));
		if (!sampleApproved(source)) {
			throw ApiException.unprocessable("The sample of this project has not been approved yet");
		}
		if (!openOrders(List.of(source.id)).isEmpty()) {
			throw ApiException.conflict("A production order for this part is already open.");
		}
		ProductionPo po = new ProductionPo();
		po.productionPoNumber = "PPO-%d-%05d".formatted(Year.now().getValue(),
				jdbc.queryForObject("SELECT nextval('production_po_number_seq')", Long.class));
		po.sourceProjectId = source.id;
		po.customerCompanyId = me.companyId();
		po.partNumber = source.partNumber;
		apply(po, form.customerPoNumber(), form.quantity(), form.requiredDate(), form.remarks());
		po.status = ProductionPoStatus.SUBMITTED;
		po.createdByUserId = me.userId();
		try {
			productionPos.save(po);
		}
		catch (DataIntegrityViolationException e) {
			throw ApiException.conflict("A production order for this part is already open."); // race defense (DB UNIQUE)
		}
		submitted(po);
		return views(me, List.of(po)).getFirst();
	}

	/** Correct and resubmit a production order the KAM sent back. */
	public ProductionPoView resubmit(long id, ProductionPoEditForm form) {
		CurrentUser me = CurrentUser.get();
		ProductionPo po = locked(me, id);
		if (!RAISERS.contains(me.role())) {
			throw ApiException.forbidden("Only Customer OFP can correct a production order");
		}
		ModuleSwitch.requireWritable(Module.OFP);
		if (po.status != ProductionPoStatus.RETURNED) {
			throw ApiException.conflict("Only a returned production order can be corrected");
		}
		apply(po, form.customerPoNumber(), form.quantity(), form.requiredDate(), form.remarks());
		po.status = ProductionPoStatus.SUBMITTED;
		submitted(po);
		return views(me, List.of(po)).getFirst();
	}

	// ---------------------------------------------------------------- KAM: return or accept

	public ProductionPoView returnToCustomer(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		ProductionPo po = locked(me, id);
		requireDesk(me);
		ModuleSwitch.requireWritable(Module.OFP);
		requireSubmitted(po);
		String reason = comment.trim();
		audit.reason(reason);
		po.status = ProductionPoStatus.RETURNED;
		po.returnComment = reason;
		Recipients customerOfp = RAISERS.isEmpty() ? Recipients.none()
				: Recipients.company(po.customerCompanyId, RAISERS.toArray(Role[]::new));
		notifier.send(NotificationKind.PRODUCTION_PO_RETURNED, "production-po", po.id, po.productionPoNumber,
				"Part " + po.partNumber, customerOfp);
		return views(me, List.of(po)).getFirst();
	}

	/** Accepting the production order IS creating the OFP project: one action, so a project never exists without its PO. */
	public NpdView createProject(long id, CreateProjectForm form) {
		CurrentUser me = CurrentUser.get();
		ProductionPo po = locked(me, id);
		requireDesk(me);
		ModuleSwitch.requireWritable(Module.OFP, Module.NPD);
		requireSubmitted(po);
		UserRef ofpUser = assignee(form.ergoOfpUserId(), Role.ERGO_OFP, "ERGO OFP");
		UserRef qualityUser = assignee(form.ergoQualityUserId(), Role.ERGO_QUALITY, "ERGO Quality");
		NpdProject source = projects.findById(po.sourceProjectId).orElse(null);
		if (source == null) {
			throw ApiException.conflict("The sample project behind this production order is no longer available");
		}
		NpdView project = npdService.createFromProductionPo(me, po, source, ofpUser, qualityUser, form.comment());
		po.status = ProductionPoStatus.ACCEPTED;
		return project;
	}

	// ---------------------------------------------------------------- facade support

	/** The production order's customer, if the caller may see it (notification delivery and links); a supplier never. */
	@Transactional(readOnly = true)
	public Optional<Long> customerIfVisible(long id) {
		CurrentUser me = CurrentUser.get();
		return productionPos.findById(id).filter(po -> inScope(me, po)).map(po -> po.customerCompanyId);
	}

	// ---------------------------------------------------------------- rules

	/** A sample project whose sample the customer has finally approved: it reached the handover stage or was closed. */
	private static boolean sampleApproved(NpdProject p) {
		return p.kind == ProjectKind.SAMPLE
				&& (p.stage == NpdStage.APPROVED_FOR_PRODUCTION_HANDOVER || p.stage == NpdStage.CLOSED);
	}

	private Set<Long> openOrders(List<Long> sourceProjectIds) {
		return productionPos
			.findBySourceProjectIdInAndStatusIn(sourceProjectIds,
					EnumSet.of(ProductionPoStatus.SUBMITTED, ProductionPoStatus.RETURNED))
			.stream()
			.map(po -> po.sourceProjectId)
			.collect(Collectors.toSet());
	}

	private ProductionPo visible(CurrentUser me, long id) {
		ProductionPo po = productionPos.findById(id).orElse(null);
		if (po == null || !inScope(me, po)) {
			throw ApiException.notFound("Production PO");
		}
		return po;
	}

	/** Takes the row lock first, so two decisions on one production order (a customer's correction racing the KAM's
	 * return) run one after the other and the second sees the first's status. */
	private ProductionPo locked(CurrentUser me, long id) {
		jdbc.queryForList("SELECT production_po_id FROM production_pos WHERE production_po_id = ? FOR UPDATE", Long.class, id);
		return visible(me, id);
	}

	private boolean inScope(CurrentUser me, ProductionPo po) {
		if (!ModuleAccess.canView(me.role(), Module.OFP) || !ModuleSwitch.readable(Module.OFP)) {
			return false;
		}
		return CUSTOMER_VIEWERS.contains(me.role()) && me.companyId() == po.customerCompanyId
				|| FULL_VIEWERS.contains(me.role())
				|| me.role() == ERGO_KAM && directory.accountsOf(me.userId()).contains(po.customerCompanyId);
	}

	/** The KAM (for their accounts, enforced by the scope check) and ERGO Management decide a production order. */
	private static boolean isDesk(CurrentUser me) {
		return (me.role() == ERGO_KAM || me.role() == ERGO_MANAGEMENT) && ModuleAccess.canAct(me.role(), Module.OFP);
	}

	private static void requireDesk(CurrentUser me) {
		if (!isDesk(me)) {
			throw ApiException.forbidden("Only the ERGO Key Account Manager or ERGO Management can decide a production order");
		}
	}

	private static void requireSubmitted(ProductionPo po) {
		if (po.status != ProductionPoStatus.SUBMITTED) {
			throw ApiException.conflict("This production order is not waiting for a decision");
		}
	}

	/** An assignee must be an active user who holds the named ERGO role. */
	private UserRef assignee(long userId, Role role, String label) {
		UserRef user = directory.activeUsers(Set.of(userId)).get(userId);
		if (user == null || !directory.rolesOf(Set.of(userId)).getOrDefault(userId, Set.of()).contains(role)) {
			throw ApiException.unprocessable("Choose an active " + label + " user");
		}
		return user;
	}

	private static void apply(ProductionPo po, String customerPoNumber, int quantity, LocalDate requiredDate, String remarks) {
		po.customerPoNumber = customerPoNumber.trim();
		po.quantity = quantity;
		po.requiredDate = requiredDate;
		po.remarks = remarks == null || remarks.isBlank() ? null : remarks.trim();
		po.returnComment = null; // the new version answers the last return; the return itself stays in the audit trail
	}

	private void submitted(ProductionPo po) {
		notifier.send(NotificationKind.PRODUCTION_PO_SUBMITTED, "production-po", po.id, po.productionPoNumber,
				"Part " + po.partNumber, Recipients.ergo(ERGO_KAM, ERGO_MANAGEMENT));
	}

	// ---------------------------------------------------------------- view assembly

	private List<ProductionPoView> views(CurrentUser me, List<ProductionPo> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		boolean ergo = me.companyType() == CompanyType.ERGO;
		Map<Long, CompanyRef> customers = directory
			.companies(rows.stream().map(r -> r.customerCompanyId).collect(Collectors.toSet()));
		Map<Long, NpdProject> sources = projects.findByIdIn(rows.stream().map(r -> r.sourceProjectId).toList())
			.stream()
			.collect(Collectors.toMap(p -> p.id, p -> p));
		Map<Long, NpdProject> projectByPo = projects.findByProductionPoIdIn(rows.stream().map(r -> r.id).toList())
			.stream()
			.collect(Collectors.toMap(p -> p.productionPoId, p -> p));
		boolean writable = ModuleSwitch.writable(Module.OFP);
		return rows.stream().map(po -> {
			NpdProject source = sources.get(po.sourceProjectId);
			NpdProject project = projectByPo.get(po.id);
			CompanyRef customer = customers.get(po.customerCompanyId);
			return new ProductionPoView(po.id, po.productionPoNumber, po.sourceProjectId,
					source == null ? null : source.npdNumber, customer == null ? null : customer.name(), po.partNumber,
					ergo && source != null ? source.revision : null, ergo && source != null ? source.description : null,
					po.customerPoNumber, po.quantity, po.requiredDate, po.remarks, po.status, po.returnComment,
					project == null ? null : project.id, project == null ? null : project.npdNumber, po.createdAt,
					po.updatedAt, actions(me, po, writable));
		}).toList();
	}

	private static Set<ProductionPoAction> actions(CurrentUser me, ProductionPo po, boolean writable) {
		Set<ProductionPoAction> actions = EnumSet.noneOf(ProductionPoAction.class);
		if (!writable) {
			return actions;
		}
		if (RAISERS.contains(me.role()) && po.status == ProductionPoStatus.RETURNED) {
			actions.add(ProductionPoAction.EDIT);
		}
		if (isDesk(me) && po.status == ProductionPoStatus.SUBMITTED) {
			actions.add(ProductionPoAction.RETURN);
			actions.add(ProductionPoAction.CREATE_PROJECT);
		}
		return actions;
	}

}
