package com.ergo.connect.npd.domain;

import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;
import static com.ergo.connect.common.security.Role.ERGO_NPD;
import static com.ergo.connect.common.security.Role.ERGO_RFQ;

import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;
import com.ergo.connect.npd.domain.NpdDtos.ActivityForm;
import com.ergo.connect.npd.domain.NpdDtos.ActivityView;
import com.ergo.connect.npd.domain.NpdDtos.ConversionQueueRow;
import com.ergo.connect.npd.domain.NpdDtos.Named;
import com.ergo.connect.npd.domain.NpdDtos.NpdAction;
import com.ergo.connect.npd.domain.NpdDtos.NpdDetailView;
import com.ergo.connect.npd.domain.NpdDtos.NpdListRow;
import com.ergo.connect.npd.domain.NpdDtos.NpdProjectForm;
import com.ergo.connect.npd.domain.NpdDtos.StageHistoryView;
import com.ergo.connect.quotation.api.QuotationApi;
import com.ergo.connect.quotation.api.QuotationApi.SelectedQuote;
import com.ergo.connect.rfq.api.RfqApi;
import com.ergo.connect.rfq.api.RfqApi.ApprovedRfq;
import com.ergo.connect.rfq.api.RfqApi.RfqRef;

/**
 * Phase 3a: the manual RFQ->NPD conversion queue and the 9-stage NPD workflow (SRS §13.4, §14.5, §17.4). ERGO-only:
 * customer and supplier NPD roles get nothing here (3b). Record scope: ERGO Admin/Management/RFQ/NPD see every
 * project, ERGO KAM only its assigned customers' (SRS §10.6 combined with the client's 2026-09-26 scope decision).
 * {@link #allowed} is the single rule book: it enforces every action and lists the caller's buttons.
 */
@Service
@Transactional
public class NpdService {

	/** SRS §10.6 create / edit / submit / approve for NPD (the client decision restricts all of 3a's actual write
	 * actions to these two roles; ERGO Management's "approve" right has nothing to attach to until 3b). */
	private static final Set<Role> EDITORS = EnumSet.of(ERGO_ADMIN, ERGO_NPD);

	/** The "full, unrestricted view" grouping, mirroring RfqApi.customerApproved()'s own four-role branch. */
	private static final Set<Role> FULL_VIEWERS = EnumSet.of(ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ, ERGO_NPD);

	private final NpdProjectRepository projects;

	private final NpdActivityRepository activities;

	private final NpdStageHistoryRepository stageHistory;

	private final RfqApi rfqApi;

	private final QuotationApi quotationApi;

	private final Directory directory;

	private final AuditLogService audit;

	private final JdbcTemplate jdbc;

	NpdService(NpdProjectRepository projects, NpdActivityRepository activities,
			NpdStageHistoryRepository stageHistory, RfqApi rfqApi, QuotationApi quotationApi, Directory directory,
			AuditLogService audit, JdbcTemplate jdbc) {
		this.projects = projects;
		this.activities = activities;
		this.stageHistory = stageHistory;
		this.rfqApi = rfqApi;
		this.quotationApi = quotationApi;
		this.directory = directory;
		this.audit = audit;
		this.jdbc = jdbc;
	}

	// ---------------------------------------------------------------- conversion queue

	@Transactional(readOnly = true)
	public List<ConversionQueueRow> conversionQueue() {
		CurrentUser me = CurrentUser.get();
		// Already scoped to the caller's RFQ visibility (SRS §10.6/§14.5: empty for anyone with no RFQ visibility).
		List<ApprovedRfq> approved = rfqApi.customerApproved();
		if (approved.isEmpty()) {
			return List.of();
		}
		List<ApprovedRfq> unconverted = approved.stream().filter(r -> !projects.existsBySourceRfqId(r.id())).toList();
		Map<Long, CompanyRef> customers = directory.companies(unconverted.stream().map(ApprovedRfq::customerCompanyId)
			.collect(Collectors.toSet()));
		Set<NpdAction> actions = EDITORS.contains(me.role()) ? EnumSet.of(NpdAction.CONVERT) : Set.of();
		return unconverted.stream()
			.map(r -> new ConversionQueueRow(r.id(), r.number(), name(customers, r.customerCompanyId()),
					r.partNumber(), r.revision(), r.description(), r.approvedAt(), actions))
			.toList();
	}

	/** SRS §14.5: only ERGO Admin / ERGO NPD may convert. One approved RFQ converts to at most one NPD project. */
	public NpdDetailView convert(long rfqId, String comment) {
		CurrentUser me = CurrentUser.get();
		if (!EDITORS.contains(me.role())) {
			throw ApiException.forbidden("Only ERGO Admin or ERGO NPD can convert an RFQ");
		}
		RfqRef rfq = rfqApi.forErgo(rfqId); // 404 outside the caller's RFQ scope, checked before revealing anything else
		if (projects.existsBySourceRfqId(rfqId)) {
			throw ApiException.conflict("This RFQ has already been converted.");
		}
		SelectedQuote quote = quotationApi.selectedQuoteFor(rfqId)
			.orElseThrow(() -> ApiException.unprocessable("This RFQ has no approved customer quote yet"));

		NpdProject project = new NpdProject();
		project.npdNumber = "NPD-%d-%05d".formatted(Year.now().getValue(),
				jdbc.queryForObject("SELECT nextval('npd_number_seq')", Long.class));
		project.sourceRfqId = rfqId;
		project.customerCompanyId = rfq.customerCompanyId();
		project.supplierCompanyId = quote.supplierCompanyId();
		project.partNumber = rfq.partNumber();
		project.revision = rfq.revision();
		project.description = rfq.description();
		project.quoteReference = quote.quoteReference();
		// Conversion is the Pending Conversion -> Planning transition itself; nothing is left sitting unconverted.
		project.stage = NpdStage.PLANNING;
		project.ownerUserId = me.userId();
		project.createdByUserId = me.userId();
		try {
			projects.save(project);
		}
		catch (DataIntegrityViolationException e) {
			throw ApiException.conflict("This RFQ has already been converted."); // race defense (DB UNIQUE)
		}
		reasonIfGiven(comment);
		NpdStageHistoryEntry history = new NpdStageHistoryEntry();
		history.npdProjectId = project.id;
		history.stageName = NpdStage.PLANNING;
		history.previousStageName = null; // the very first row
		history.changedByUserId = me.userId();
		history.remarks = text(comment);
		stageHistory.save(history);
		return view(me, project);
	}

	// ---------------------------------------------------------------- reads

	@Transactional(readOnly = true)
	public List<NpdListRow> list() {
		CurrentUser me = CurrentUser.get();
		List<NpdProject> visible;
		if (FULL_VIEWERS.contains(me.role())) {
			visible = projects.findAllByOrderByIdDesc();
		}
		else if (me.role() == ERGO_KAM) {
			Set<Long> accounts = directory.accountsOf(me.userId());
			visible = accounts.isEmpty() ? List.of() : projects.findByCustomerCompanyIdInOrderByIdDesc(accounts);
		}
		else {
			visible = List.of(); // no NPD visibility for this role (SRS §10.6, ERGO OFP/Quality omitted for 3a)
		}
		Map<Long, CompanyRef> customers = directory.companies(ids(visible, p -> p.customerCompanyId));
		Map<Long, CompanyRef> suppliers = directory.companies(ids(visible, p -> p.supplierCompanyId));
		Map<Long, UserRef> owners = directory.users(ids(visible, p -> p.ownerUserId));
		return visible.stream()
			.map(p -> new NpdListRow(p.id, p.npdNumber, rfqNumber(p.sourceRfqId), name(customers, p.customerCompanyId),
					name(suppliers, p.supplierCompanyId), p.partNumber, p.stage, p.targetSampleDate, delayDays(p),
					userName(owners, p.ownerUserId), p.riskLevel))
			.toList();
	}

	@Transactional(readOnly = true)
	public NpdDetailView get(long id) {
		CurrentUser me = CurrentUser.get();
		return view(me, visible(me, id));
	}

	/** SRS §12.8.1/§12.8.3 project-header fields (owner, risk level, sample dates), ERGO Admin/NPD only. Same shape as
	 * every other form in this module: a full replace of these fields, not a patch (send the current value back for
	 * anything you don't want to change). */
	public NpdDetailView updateProject(long id, NpdProjectForm form) {
		CurrentUser me = CurrentUser.get();
		NpdProject project = visible(me, id);
		require(NpdAction.UPDATE_PROJECT, me, project);
		if (form.ownerUserId() != null && !directory.users(Set.of(form.ownerUserId())).containsKey(form.ownerUserId())) {
			throw ApiException.unprocessable("Owner not found");
		}
		project.ownerUserId = form.ownerUserId();
		project.riskLevel = form.riskLevel();
		project.targetSampleDate = form.targetSampleDate();
		project.actualSampleSubmissionDate = form.actualSampleSubmissionDate();
		return view(me, project);
	}

	// ---------------------------------------------------------------- workflow (SRS §14.5)

	/** Forward-only "Close Stage": blocked unless every mandatory activity of the current stage is DONE. */
	public NpdDetailView closeStage(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		NpdProject project = visible(me, id);
		require(NpdAction.CLOSE_STAGE, me, project);
		NpdStage current = project.stage;
		List<String> open = activities.findByNpdProjectIdAndPhaseNameAndMandatoryFlagTrue(id, current).stream()
			.filter(a -> a.status != ActivityStatus.DONE)
			.map(a -> a.activityName)
			.toList();
		if (!open.isEmpty()) {
			throw ApiException
				.conflict("Complete these mandatory activities before closing this stage: " + String.join(", ", open));
		}
		NpdStage next = current.next();
		reasonIfGiven(comment);
		project.stage = next;
		NpdStageHistoryEntry history = new NpdStageHistoryEntry();
		history.npdProjectId = project.id;
		history.stageName = next;
		history.previousStageName = current;
		history.changedByUserId = me.userId();
		history.remarks = text(comment);
		stageHistory.save(history);
		return view(me, project);
	}

	// ---------------------------------------------------------------- activities (SRS §13.4.3, §17.4)

	public NpdDetailView addActivity(long projectId, ActivityForm form) {
		CurrentUser me = CurrentUser.get();
		NpdProject project = visible(me, projectId);
		require(NpdAction.ADD_ACTIVITY, me, project);
		NpdActivity activity = new NpdActivity();
		activity.npdProjectId = project.id;
		activity.phaseName = project.stage;
		activity.ownerOrganizationType = CompanyType.ERGO; // 3a is ERGO-only; server-derived, never client-supplied
		activity.ownerCompanyId = me.companyId();
		apply(activity, form, project.id);
		activities.save(activity);
		return view(me, project);
	}

	public NpdDetailView updateActivity(long activityId, ActivityForm form) {
		CurrentUser me = CurrentUser.get();
		NpdActivity activity = activities.findById(activityId).orElseThrow(() -> ApiException.notFound("Activity"));
		NpdProject project = visible(me, activity.npdProjectId);
		require(NpdAction.EDIT_ACTIVITY, me, project, activity);
		apply(activity, form, project.id);
		return view(me, project);
	}

	/** SRS §12.8.3/§17.4 "Mark Blocked": toggles blocked_flag, with a required reason (audited). */
	public NpdDetailView markBlocked(long activityId, String reason) {
		CurrentUser me = CurrentUser.get();
		NpdActivity activity = activities.findById(activityId).orElseThrow(() -> ApiException.notFound("Activity"));
		NpdProject project = visible(me, activity.npdProjectId);
		require(NpdAction.MARK_BLOCKED, me, project, activity);
		audit.reason(required(reason, "A reason is required to mark an activity blocked"));
		activity.blockedFlag = true;
		return view(me, project);
	}

	/** Clears blocked_flag once resolved. Not named by the SRS, but built symmetrically: no permanent dead end. */
	public NpdDetailView unblock(long activityId, String comment) {
		CurrentUser me = CurrentUser.get();
		NpdActivity activity = activities.findById(activityId).orElseThrow(() -> ApiException.notFound("Activity"));
		NpdProject project = visible(me, activity.npdProjectId);
		require(NpdAction.UNBLOCK, me, project, activity);
		reasonIfGiven(comment);
		activity.blockedFlag = false;
		return view(me, project);
	}

	// ---------------------------------------------------------------- rules

	/** Record scope. Out-of-scope NPD projects do not exist for the caller (404, not 403). */
	private NpdProject visible(CurrentUser me, long id) {
		NpdProject project = projects.findById(id).orElse(null);
		boolean inScope = project != null && (FULL_VIEWERS.contains(me.role())
				|| me.role() == ERGO_KAM && directory.accountsOf(me.userId()).contains(project.customerCompanyId));
		if (!inScope) {
			throw ApiException.notFound("NPD project");
		}
		return project;
	}

	/** Project-level actions: Close Stage, Add Activity. */
	private static boolean allowed(NpdAction action, CurrentUser me, NpdProject project) {
		boolean editor = EDITORS.contains(me.role());
		return switch (action) {
			case UPDATE_PROJECT -> editor;
			case CLOSE_STAGE, ADD_ACTIVITY -> editor && project.stage != NpdStage.CLOSED;
			default -> false; // CONVERT acts on an RFQ; EDIT_ACTIVITY/MARK_BLOCKED/UNBLOCK need a specific activity
		};
	}

	/** Activity-level actions: Edit, Mark Blocked, Unblock. */
	private static boolean allowed(NpdAction action, CurrentUser me, NpdProject project, NpdActivity activity) {
		if (!EDITORS.contains(me.role()) || project.stage == NpdStage.CLOSED) {
			return false;
		}
		return switch (action) {
			case EDIT_ACTIVITY -> true;
			case MARK_BLOCKED -> !activity.blockedFlag;
			case UNBLOCK -> activity.blockedFlag;
			default -> false;
		};
	}

	private static Set<NpdAction> projectActions(CurrentUser me, NpdProject project) {
		return Arrays.stream(NpdAction.values()).filter(a -> allowed(a, me, project))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(NpdAction.class)));
	}

	private static Set<NpdAction> activityActions(CurrentUser me, NpdProject project, NpdActivity activity) {
		return Arrays.stream(NpdAction.values()).filter(a -> allowed(a, me, project, activity))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(NpdAction.class)));
	}

	private static void require(NpdAction action, CurrentUser me, NpdProject project) {
		if (!allowed(action, me, project)) {
			throw ApiException.conflict("This action is not available for this NPD project right now");
		}
	}

	private static void require(NpdAction action, CurrentUser me, NpdProject project, NpdActivity activity) {
		if (!allowed(action, me, project, activity)) {
			throw ApiException.conflict("This action is not available for this activity right now");
		}
	}

	/** Shared by add and edit. Dates/status/mandatory flow straight from the form: the caller already validated the
	 * shape; only cross-references (dependency, responsible user) and the BLOCKED guard need checking here. */
	private void apply(NpdActivity activity, ActivityForm form, long projectId) {
		if (form.status() == ActivityStatus.BLOCKED) {
			throw ApiException.unprocessable("Use \"Mark Blocked\" to block an activity, with a reason");
		}
		if (form.dependencyActivityId() != null) {
			NpdActivity dependency = activities.findById(form.dependencyActivityId())
				.filter(d -> d.npdProjectId.equals(projectId))
				.orElseThrow(
						() -> ApiException.unprocessable("The dependency must be another activity of this NPD project"));
			if (dependency.id.equals(activity.id)) {
				throw ApiException.unprocessable("An activity cannot depend on itself");
			}
		}
		if (form.responsibleUserId() != null
				&& !directory.users(Set.of(form.responsibleUserId())).containsKey(form.responsibleUserId())) {
			throw ApiException.unprocessable("Responsible user not found");
		}
		activity.activityName = form.activityName().trim();
		activity.activityDescription = text(form.activityDescription());
		activity.responsibleUserId = form.responsibleUserId();
		activity.plannedDate = form.plannedDate();
		activity.baselineDate = form.baselineDate();
		activity.actualDate = form.actualDate();
		activity.dependencyActivityId = form.dependencyActivityId();
		activity.status = form.status();
		activity.mandatoryFlag = form.mandatory();
		activity.riskFlag = form.riskFlag();
		activity.issueFlag = form.issueFlag();
	}

	private NpdDetailView view(CurrentUser me, NpdProject p) {
		RfqRef rfq = rfqApi.forErgo(p.sourceRfqId);
		CompanyRef customer = directory.company(p.customerCompanyId).orElseThrow();
		CompanyRef supplier = directory.company(p.supplierCompanyId).orElseThrow();
		Map<Long, UserRef> owner = directory.users(ids(List.of(p), x -> x.ownerUserId));

		List<NpdActivity> acts = activities.findByNpdProjectIdOrderById(p.id);
		Map<Long, UserRef> activityUsers = directory.users(ids(acts, a -> a.responsibleUserId));
		Map<Long, CompanyRef> activityCompanies = directory.companies(ids(acts, a -> a.ownerCompanyId));
		List<ActivityView> activityViews = acts.stream()
			.map(a -> new ActivityView(a.id, a.phaseName, a.activityName, a.activityDescription,
					a.ownerOrganizationType, name(activityCompanies, a.ownerCompanyId), a.responsibleUserId,
					userName(activityUsers, a.responsibleUserId), a.plannedDate, a.baselineDate, a.actualDate,
					a.dependencyActivityId, a.status, a.blockedFlag, a.riskFlag, a.issueFlag, a.mandatoryFlag,
					activityActions(me, p, a)))
			.toList();

		List<NpdStageHistoryEntry> history = stageHistory.findByNpdProjectIdOrderById(p.id);
		Map<Long, UserRef> historyUsers = directory.users(ids(history, h -> h.changedByUserId));
		List<StageHistoryView> historyViews = history.stream()
			.map(h -> new StageHistoryView(h.stageName, h.previousStageName, userName(historyUsers, h.changedByUserId),
					h.changedAt, h.remarks))
			.toList();

		return new NpdDetailView(p.id, p.npdNumber, rfq.id(), rfq.number(),
				new Named(customer.id(), customer.code(), customer.name()),
				new Named(supplier.id(), supplier.code(), supplier.name()), p.partNumber, p.revision, p.description,
				p.quoteReference, p.stage, p.targetSampleDate, p.actualSampleSubmissionDate, delayDays(p), p.ownerUserId,
				userName(owner, p.ownerUserId), p.riskLevel, activityViews, historyViews, projectActions(me, p),
				p.createdAt, p.updatedAt);
	}

	// ---------------------------------------------------------------- small helpers

	/** ponytail: one RfqApi call per row (no batch facade exists for ERGO RFQ numbers); fine at 3a's expected volume. */
	private String rfqNumber(long rfqId) {
		return rfqApi.forErgo(rfqId).number();
	}

	private static Long delayDays(NpdProject p) {
		if (p.targetSampleDate == null) {
			return null;
		}
		LocalDate reference = p.actualSampleSubmissionDate != null ? p.actualSampleSubmissionDate : LocalDate.now();
		long days = ChronoUnit.DAYS.between(p.targetSampleDate, reference);
		return days > 0 ? days : 0L;
	}

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
