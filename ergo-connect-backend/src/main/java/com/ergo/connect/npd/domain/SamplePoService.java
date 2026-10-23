package com.ergo.connect.npd.domain;

import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;

import java.time.Year;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.ergo.connect.npd.domain.SamplePoDtos.Assignees;
import com.ergo.connect.npd.domain.SamplePoDtos.CreateProjectForm;
import com.ergo.connect.npd.domain.SamplePoDtos.EligiblePart;
import com.ergo.connect.npd.domain.SamplePoDtos.SamplePoAction;
import com.ergo.connect.npd.domain.SamplePoDtos.SamplePoEditForm;
import com.ergo.connect.npd.domain.SamplePoDtos.SamplePoForm;
import com.ergo.connect.npd.domain.SamplePoDtos.SamplePoView;
import com.ergo.connect.quotation.api.QuotationApi;
import com.ergo.connect.quotation.api.QuotationApi.ApprovedPart;
import com.ergo.connect.quotation.api.QuotationApi.CustomerApprovedPart;
import com.ergo.connect.rfq.api.RfqApi;
import com.ergo.connect.rfq.api.RfqApi.ItemRef;

/**
 * Flow doc steps 6-7. Customer NPD raises a Sample PO for a part whose quotation its company approved; the ERGO KAM
 * (or ERGO Management) either returns it with a comment or accepts it by creating the sample development project and
 * assigning ERGO NPD and ERGO Quality. Record scope: a customer only its own company's Sample POs, a KAM only its
 * assigned accounts', the other ERGO roles with NPD access every one; a supplier never reaches a Sample PO.
 */
@Service
@Transactional
public class SamplePoService {

	/** Customer roles that raise a Sample PO: the customer-side final-approval role of NPD (a PO is a commitment). */
	private static final Set<Role> RAISERS = ModuleAccess.approvers(Module.NPD, CompanyType.CUSTOMER);

	private static final Set<Role> CUSTOMER_VIEWERS = ModuleAccess.viewers(Module.NPD, CompanyType.CUSTOMER);

	/** Every ERGO role with NPD access except the account-scoped KAM. */
	private static final Set<Role> FULL_VIEWERS = ModuleAccess.viewersExcept(Module.NPD, CompanyType.ERGO, ERGO_KAM);

	private final SamplePoRepository samplePos;

	private final NpdProjectRepository projects;

	private final NpdService npdService;

	private final RfqApi rfqApi;

	private final QuotationApi quotationApi;

	private final Directory directory;

	private final AuditLogService audit;

	private final JdbcTemplate jdbc;

	private final Notifier notifier;

	SamplePoService(SamplePoRepository samplePos, NpdProjectRepository projects, NpdService npdService, RfqApi rfqApi,
			QuotationApi quotationApi, Directory directory, AuditLogService audit, JdbcTemplate jdbc,
			Notifier notifier) {
		this.samplePos = samplePos;
		this.projects = projects;
		this.npdService = npdService;
		this.rfqApi = rfqApi;
		this.quotationApi = quotationApi;
		this.directory = directory;
		this.audit = audit;
		this.jdbc = jdbc;
		this.notifier = notifier;
	}

	// ---------------------------------------------------------------- reads

	@Transactional(readOnly = true)
	public List<SamplePoView> list() {
		CurrentUser me = CurrentUser.get();
		List<SamplePo> visible;
		if (CUSTOMER_VIEWERS.contains(me.role())) {
			visible = samplePos.findByCustomerCompanyIdInOrderByIdDesc(Set.of(me.companyId()));
		}
		else if (FULL_VIEWERS.contains(me.role())) {
			visible = samplePos.findAllByOrderByIdDesc();
		}
		else if (me.role() == ERGO_KAM && ModuleAccess.canView(me.role(), Module.NPD)) {
			Set<Long> accounts = directory.accountsOf(me.userId());
			visible = accounts.isEmpty() ? List.of() : samplePos.findByCustomerCompanyIdInOrderByIdDesc(accounts);
		}
		else {
			visible = List.of();
		}
		return views(me, visible);
	}

	@Transactional(readOnly = true)
	public SamplePoView get(long id) {
		CurrentUser me = CurrentUser.get();
		return views(me, List.of(visible(me, id))).getFirst();
	}

	/** Who the KAM can assign a new project to: the active ERGO NPD and ERGO Quality users (just id and name). */
	@Transactional(readOnly = true)
	public Assignees assignees() {
		CurrentUser me = CurrentUser.get();
		if (!isDesk(me)) {
			throw ApiException.forbidden("Only the ERGO Key Account Manager or ERGO Management can assign a project");
		}
		List<UserRef> ergo = directory.activeUsersOfType(CompanyType.ERGO);
		Map<Long, Set<Role>> roles = directory.rolesOf(ergo.stream().map(UserRef::id).toList());
		return new Assignees(withRole(ergo, roles, Role.ERGO_NPD), withRole(ergo, roles, Role.ERGO_QUALITY));
	}

	private static List<UserRef> withRole(List<UserRef> users, Map<Long, Set<Role>> roles, Role role) {
		return users.stream().filter(u -> roles.getOrDefault(u.id(), Set.of()).contains(role)).toList();
	}

	/** The audit trail of a Sample PO (who raised, returned, corrected, accepted it); ERGO only, like the NPD project's. */
	@Transactional(readOnly = true)
	public List<AuditLogService.AuditEntry> history(long id) {
		CurrentUser me = CurrentUser.get();
		visible(me, id);
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.forbidden("The audit trail is internal to ERGO");
		}
		return audit.history("npd", "SamplePo", id);
	}

	/** The customer's approved parts that have no Sample PO yet: where Customer NPD starts. */
	@Transactional(readOnly = true)
	public List<EligiblePart> eligibleParts() {
		CurrentUser me = CurrentUser.get();
		if (!RAISERS.contains(me.role())) {
			return List.of();
		}
		List<CustomerApprovedPart> approved = quotationApi.customerApprovedParts();
		if (approved.isEmpty()) {
			return List.of();
		}
		List<Long> itemIds = approved.stream().map(CustomerApprovedPart::rfqItemId).toList();
		Set<Long> taken = samplePos.findByRfqItemIdIn(itemIds).stream().map(s -> s.rfqItemId)
			.collect(Collectors.toCollection(java.util.HashSet::new));
		// a part converted before Sample POs existed already has its project
		projects.findBySourceRfqItemIdIn(itemIds).forEach(p -> taken.add(p.sourceRfqItemId));
		Set<SamplePoAction> actions = ModuleSwitch.writable(Module.NPD) ? EnumSet.of(SamplePoAction.RAISE) : Set.of();
		return approved.stream()
			.filter(p -> !taken.contains(p.rfqItemId()))
			.map(p -> new EligiblePart(p.rfqId(), p.rfqNumber(), p.rfqItemId(), p.partNumber(), p.approvedAt(), actions))
			.toList();
	}

	// ---------------------------------------------------------------- customer: raise and correct

	public SamplePoView raise(SamplePoForm form) {
		CurrentUser me = CurrentUser.get();
		if (!RAISERS.contains(me.role())) {
			throw ApiException.forbidden("Only Customer NPD can raise a Sample PO");
		}
		ModuleSwitch.requireWritable(Module.NPD);
		if (!ModuleSwitch.readable(Module.RFQ) || !ModuleSwitch.readable(Module.QUOTATION)) {
			throw ApiException.conflict("A Sample PO needs the RFQ and Quotation modules, and one of them is not "
					+ "available right now. Nothing was changed; it can be repeated once they are back on.");
		}
		// the customer's own approved parts only; a part of another company does not exist for the caller
		CustomerApprovedPart part = quotationApi.customerApprovedParts()
			.stream()
			.filter(p -> p.rfqItemId() == form.rfqItemId())
			.findFirst()
			.orElseThrow(() -> rfqApi.customerParts().stream().anyMatch(p -> p.itemId() == form.rfqItemId())
					? ApiException.unprocessable("This part has no approved quotation yet")
					: ApiException.notFound("Part"));
		if (!samplePos.findByRfqItemIdIn(Set.of(part.rfqItemId())).isEmpty()) {
			throw ApiException.conflict("A Sample PO already exists for this part.");
		}
		if (!projects.findBySourceRfqItemIdIn(Set.of(part.rfqItemId())).isEmpty()) {
			throw ApiException.conflict("This part already has an NPD project.");
		}
		SamplePo po = new SamplePo();
		po.samplePoNumber = "SPO-%d-%05d".formatted(Year.now().getValue(),
				jdbc.queryForObject("SELECT nextval('sample_po_number_seq')", Long.class));
		po.rfqId = part.rfqId();
		po.rfqItemId = part.rfqItemId();
		po.customerCompanyId = me.companyId();
		po.partNumber = part.partNumber();
		apply(po, form.customerPoNumber(), form.quantity(), form.requiredDate(), form.remarks());
		po.status = SamplePoStatus.SUBMITTED;
		po.createdByUserId = me.userId();
		try {
			samplePos.save(po);
		}
		catch (DataIntegrityViolationException e) {
			throw ApiException.conflict("A Sample PO already exists for this part."); // race defense (DB UNIQUE)
		}
		submitted(po);
		return views(me, List.of(po)).getFirst();
	}

	/** Correct and resubmit a Sample PO the KAM sent back. */
	public SamplePoView resubmit(long id, SamplePoEditForm form) {
		CurrentUser me = CurrentUser.get();
		SamplePo po = locked(me, id);
		if (!RAISERS.contains(me.role())) {
			throw ApiException.forbidden("Only Customer NPD can correct a Sample PO");
		}
		ModuleSwitch.requireWritable(Module.NPD);
		if (po.status != SamplePoStatus.RETURNED) {
			throw ApiException.conflict("Only a returned Sample PO can be corrected");
		}
		apply(po, form.customerPoNumber(), form.quantity(), form.requiredDate(), form.remarks());
		po.status = SamplePoStatus.SUBMITTED;
		submitted(po);
		return views(me, List.of(po)).getFirst();
	}

	// ---------------------------------------------------------------- KAM: return or accept

	public SamplePoView returnToCustomer(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		SamplePo po = locked(me, id);
		requireDesk(me);
		ModuleSwitch.requireWritable(Module.NPD);
		requireSubmitted(po);
		String reason = comment.trim();
		audit.reason(reason);
		po.status = SamplePoStatus.RETURNED;
		po.returnComment = reason;
		Recipients customerNpd = RAISERS.isEmpty() ? Recipients.none()
				: Recipients.company(po.customerCompanyId, RAISERS.toArray(Role[]::new));
		notifier.send(NotificationKind.SAMPLE_PO_RETURNED, "sample-po", po.id, po.samplePoNumber, "Part " + po.partNumber,
				customerNpd);
		return views(me, List.of(po)).getFirst();
	}

	/** Accepting the Sample PO IS creating the project: one action, so a project never exists without its PO. */
	public NpdView createProject(long id, CreateProjectForm form) {
		CurrentUser me = CurrentUser.get();
		SamplePo po = locked(me, id);
		requireDesk(me);
		ModuleSwitch.requireWritable(Module.NPD);
		if (!ModuleSwitch.readable(Module.RFQ) || !ModuleSwitch.readable(Module.QUOTATION)) {
			throw ApiException.conflict("Creating the project needs the RFQ and Quotation modules, and one of them is not "
					+ "available right now. Nothing was changed; it can be repeated once they are back on.");
		}
		requireSubmitted(po);
		UserRef npdUser = assignee(form.ergoNpdUserId(), Role.ERGO_NPD, "ERGO NPD");
		UserRef qualityUser = assignee(form.ergoQualityUserId(), Role.ERGO_QUALITY, "ERGO Quality");
		Optional<ApprovedPart> quote = quotationApi.approvedPart(po.rfqItemId);
		ItemRef part = rfqApi.itemForErgo(po.rfqItemId).orElse(null);
		if (quote.isEmpty() || part == null) {
			throw ApiException.conflict("The approved quotation behind this Sample PO is no longer available");
		}
		NpdView project = npdService.createFromSamplePo(me, po, quote.get(), part, npdUser, qualityUser, form.comment());
		po.status = SamplePoStatus.ACCEPTED;
		return project;
	}

	// ---------------------------------------------------------------- facade support

	/** NpdApi: how many Sample POs wait for the caller's decision (Management and KAM dashboards). */
	@Transactional(readOnly = true)
	public int awaitingCount() {
		CurrentUser me = CurrentUser.get();
		if (!isDesk(me)) {
			return 0;
		}
		if (me.role() == ERGO_KAM) {
			Set<Long> accounts = directory.accountsOf(me.userId());
			return accounts.isEmpty() ? 0
					: (int) samplePos.countByStatusAndCustomerCompanyIdIn(SamplePoStatus.SUBMITTED, accounts);
		}
		return (int) samplePos.countByStatus(SamplePoStatus.SUBMITTED);
	}

	/** The Sample PO's customer, if the caller may see it (notification delivery and links); a supplier never. */
	@Transactional(readOnly = true)
	public Optional<Long> customerIfVisible(long id) {
		CurrentUser me = CurrentUser.get();
		return samplePos.findById(id).filter(po -> inScope(me, po)).map(po -> po.customerCompanyId);
	}

	// ---------------------------------------------------------------- rules

	private SamplePo visible(CurrentUser me, long id) {
		SamplePo po = samplePos.findById(id).orElse(null);
		if (po == null || !inScope(me, po)) {
			throw ApiException.notFound("Sample PO");
		}
		return po;
	}

	/** Takes the row lock first, so two decisions on one Sample PO (a customer's correction racing the KAM's return) run
	 * one after the other and the second sees the first's status. */
	private SamplePo locked(CurrentUser me, long id) {
		jdbc.queryForList("SELECT sample_po_id FROM sample_pos WHERE sample_po_id = ? FOR UPDATE", Long.class, id);
		return visible(me, id);
	}

	private boolean inScope(CurrentUser me, SamplePo po) {
		if (!ModuleAccess.canView(me.role(), Module.NPD) || !ModuleSwitch.readable(Module.NPD)) {
			return false;
		}
		return CUSTOMER_VIEWERS.contains(me.role()) && me.companyId() == po.customerCompanyId
				|| FULL_VIEWERS.contains(me.role())
				|| me.role() == ERGO_KAM && directory.accountsOf(me.userId()).contains(po.customerCompanyId);
	}

	/** The KAM (for their accounts, enforced by the scope check) and ERGO Management decide a Sample PO. */
	private static boolean isDesk(CurrentUser me) {
		return (me.role() == ERGO_KAM || me.role() == ERGO_MANAGEMENT) && ModuleAccess.canAct(me.role(), Module.NPD);
	}

	private static void requireDesk(CurrentUser me) {
		if (!isDesk(me)) {
			throw ApiException.forbidden("Only the ERGO Key Account Manager or ERGO Management can decide a Sample PO");
		}
	}

	private static void requireSubmitted(SamplePo po) {
		if (po.status != SamplePoStatus.SUBMITTED) {
			throw ApiException.conflict("This Sample PO is not waiting for a decision");
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

	private static void apply(SamplePo po, String customerPoNumber, int quantity, java.time.LocalDate requiredDate,
			String remarks) {
		po.customerPoNumber = customerPoNumber.trim();
		po.quantity = quantity;
		po.requiredDate = requiredDate;
		po.remarks = remarks == null || remarks.isBlank() ? null : remarks.trim();
		po.returnComment = null; // the new version answers the last return; the return itself stays in the audit trail
	}

	private void submitted(SamplePo po) {
		notifier.send(NotificationKind.SAMPLE_PO_SUBMITTED, "sample-po", po.id, po.samplePoNumber, "Part " + po.partNumber,
				Recipients.ergo(ERGO_KAM, ERGO_MANAGEMENT));
	}

	// ---------------------------------------------------------------- view assembly

	private List<SamplePoView> views(CurrentUser me, List<SamplePo> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		boolean ergo = me.companyType() == CompanyType.ERGO;
		Map<Long, CompanyRef> customers = directory
			.companies(rows.stream().map(r -> r.customerCompanyId).collect(Collectors.toSet()));
		Map<Long, NpdProject> projectByPo = projects.findBySamplePoIdIn(rows.stream().map(r -> r.id).toList())
			.stream()
			.collect(Collectors.toMap(p -> p.samplePoId, Function.identity()));
		boolean rfqVisible = ModuleSwitch.readable(Module.RFQ) && (ergo || ModuleAccess.canView(me.role(), Module.RFQ));
		boolean writable = ModuleSwitch.writable(Module.NPD);
		return rows.stream().map(po -> {
			ItemRef item = ergo && rfqVisible ? rfqApi.itemForErgo(po.rfqItemId).orElse(null) : null;
			String rfqNumber = !rfqVisible ? null : ergo ? rfqApi.numberOf(po.rfqId) : rfqApi.forCustomer(po.rfqId).number();
			NpdProject project = projectByPo.get(po.id);
			CompanyRef customer = customers.get(po.customerCompanyId);
			return new SamplePoView(po.id, po.samplePoNumber, po.rfqId, rfqNumber, po.rfqItemId,
					customer == null ? null : customer.name(), po.partNumber, item == null ? null : item.revision(),
					item == null ? null : item.description(), po.customerPoNumber, po.quantity, po.requiredDate,
					po.remarks, po.status, po.returnComment, project == null ? null : project.id,
					project == null ? null : project.npdNumber, po.createdAt, po.updatedAt, actions(me, po, writable));
		}).toList();
	}

	private static Set<SamplePoAction> actions(CurrentUser me, SamplePo po, boolean writable) {
		Set<SamplePoAction> actions = EnumSet.noneOf(SamplePoAction.class);
		if (!writable) {
			return actions;
		}
		if (RAISERS.contains(me.role()) && po.status == SamplePoStatus.RETURNED) {
			actions.add(SamplePoAction.EDIT);
		}
		if (isDesk(me) && po.status == SamplePoStatus.SUBMITTED) {
			actions.add(SamplePoAction.RETURN);
			actions.add(SamplePoAction.CREATE_PROJECT);
		}
		return actions;
	}

}
