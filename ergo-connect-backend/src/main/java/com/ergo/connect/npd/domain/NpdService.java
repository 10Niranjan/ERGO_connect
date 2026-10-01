package com.ergo.connect.npd.domain;

import static com.ergo.connect.common.security.Role.CUSTOMER_NPD;
import static com.ergo.connect.common.security.Role.CUSTOMER_QUALITY;
import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;
import static com.ergo.connect.common.security.Role.ERGO_NPD;
import static com.ergo.connect.common.security.Role.ERGO_RFQ;
import static com.ergo.connect.common.security.Role.SUPPLIER_KAM;
import static com.ergo.connect.common.security.Role.SUPPLIER_NPD;
import static com.ergo.connect.common.security.Role.SUPPLIER_OFP;
import static com.ergo.connect.common.security.Role.SUPPLIER_QUALITY;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
import com.ergo.connect.npd.domain.NpdDtos.ActivityProgressForm;
import com.ergo.connect.npd.domain.NpdDtos.ActivityView;
import com.ergo.connect.npd.domain.NpdDtos.ApprovalDecisionForm;
import com.ergo.connect.npd.domain.NpdDtos.ApprovalForm;
import com.ergo.connect.npd.domain.NpdDtos.ApprovalView;
import com.ergo.connect.npd.domain.NpdDtos.ConversionQueueRow;
import com.ergo.connect.npd.domain.NpdDtos.CustomerNpdView;
import com.ergo.connect.npd.domain.NpdDtos.ErgoNpdView;
import com.ergo.connect.npd.domain.NpdDtos.ExternalStageHistoryView;
import com.ergo.connect.npd.domain.NpdDtos.IssueRiskForm;
import com.ergo.connect.npd.domain.NpdDtos.IssueRiskView;
import com.ergo.connect.npd.domain.NpdDtos.IssueStatusForm;
import com.ergo.connect.npd.domain.NpdDtos.Named;
import com.ergo.connect.npd.domain.NpdDtos.NpdAction;
import com.ergo.connect.npd.domain.NpdDtos.NpdListRow;
import com.ergo.connect.npd.domain.NpdDtos.NpdOwnProjectRow;
import com.ergo.connect.npd.domain.NpdDtos.NpdProjectForm;
import com.ergo.connect.npd.domain.NpdDtos.NpdView;
import com.ergo.connect.npd.domain.NpdDtos.PromptChecklistForm;
import com.ergo.connect.npd.domain.NpdDtos.PromptChecklistView;
import com.ergo.connect.npd.domain.NpdDtos.PromptResponseForm;
import com.ergo.connect.npd.domain.NpdDtos.StageHistoryView;
import com.ergo.connect.npd.domain.NpdDtos.SupplierNpdView;
import com.ergo.connect.quality.api.QualityApi;
import com.ergo.connect.quotation.api.QuotationApi;
import com.ergo.connect.quotation.api.QuotationApi.SelectedQuote;
import com.ergo.connect.rfq.api.RfqApi;
import com.ergo.connect.rfq.api.RfqApi.ApprovedRfq;
import com.ergo.connect.rfq.api.RfqApi.RfqRef;

/**
 * Phase 3a: the manual RFQ->NPD conversion queue and the 9-stage NPD workflow (SRS §13.4, §14.5, §17.4), ERGO-only.
 * Phase 3b: supplier/customer participation in their own activities, prompt checklists, issues/risks and approvals
 * (SRS §13.4.4-13.4.7). Record scope: ERGO Admin/Management/RFQ/NPD see every project, ERGO KAM only its assigned
 * customers', a customer only its own, a supplier only its own (SRS §10.6 combined with the client's 2026-09-26
 * scope decisions). {@link #allowed} is the single rule book: it enforces every action and lists the caller's
 * buttons. {@link #view} is the single per-audience DTO builder: no field for the other side's company ever exists
 * on the customer/supplier types, and no query for it is ever made building them.
 */
@Service
@Transactional
public class NpdService {

	/** SRS §10.6 create / edit / submit / approve for NPD (the client decision restricts all of 3a's actual write
	 * actions to these two roles; ERGO Management's "approve" right has nothing to attach to until 3b). */
	private static final Set<Role> EDITORS = EnumSet.of(ERGO_ADMIN, ERGO_NPD);

	/** SRS §10.6 "E**" / "A**": confirmed by the user against the SRS's literal matrix. */
	private static final Set<Role> CUSTOMER_EDITORS = EnumSet.of(CUSTOMER_NPD, CUSTOMER_QUALITY);

	/** SRS §10.6 "E***" / "S": all four supplier-side roles, confirmed by the user (matches the identical grant
	 * already given in the OFP module, SRS §10.7), not just Supplier NPD. */
	private static final Set<Role> SUPPLIER_EDITORS = EnumSet.of(SUPPLIER_NPD, SUPPLIER_OFP, SUPPLIER_QUALITY,
			SUPPLIER_KAM);

	/** The three customer-facing approval types (SRS §13.4.7); a supplier's view never queries approvals at all. */
	private static final Set<ApprovalType> CUSTOMER_APPROVAL_TYPES = EnumSet.of(ApprovalType.CUSTOMER_SAMPLE_APPROVAL,
			ApprovalType.CUSTOMER_MILESTONE_ACKNOWLEDGMENT, ApprovalType.CUSTOMER_QUALITY_ACCEPTANCE);

	/** The "full, unrestricted view" grouping, mirroring RfqApi.customerApproved()'s own four-role branch. */
	private static final Set<Role> FULL_VIEWERS = EnumSet.of(ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ, ERGO_NPD);

	private final NpdProjectRepository projects;

	private final NpdActivityRepository activities;

	private final NpdStageHistoryRepository stageHistory;

	private final NpdPromptChecklistRepository checklists;

	private final NpdPromptResponseRepository responses;

	private final NpdIssueRiskRepository issueRisks;

	private final NpdApprovalRepository approvals;

	private final RfqApi rfqApi;

	private final QuotationApi quotationApi;

	private final QualityApi qualityApi;

	private final Directory directory;

	private final AuditLogService audit;

	private final JdbcTemplate jdbc;

	NpdService(NpdProjectRepository projects, NpdActivityRepository activities,
			NpdStageHistoryRepository stageHistory, NpdPromptChecklistRepository checklists,
			NpdPromptResponseRepository responses, NpdIssueRiskRepository issueRisks,
			NpdApprovalRepository approvals, RfqApi rfqApi, QuotationApi quotationApi, QualityApi qualityApi,
			Directory directory, AuditLogService audit, JdbcTemplate jdbc) {
		this.projects = projects;
		this.activities = activities;
		this.stageHistory = stageHistory;
		this.checklists = checklists;
		this.responses = responses;
		this.issueRisks = issueRisks;
		this.approvals = approvals;
		this.rfqApi = rfqApi;
		this.quotationApi = quotationApi;
		this.qualityApi = qualityApi;
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
	public NpdView convert(long rfqId, String comment) {
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
			visible = List.of(); // no NPD project list for this role: ERGO OFP/Quality (3a), customer/supplier (3b)
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

	/** SRS §12.15's customer "NPD" / supplier "NPD Tasks" nav entry point: the caller's own company's projects, with
	 * no field naming the other company (safe for both audiences). Every other role gets nothing here — this is not
	 * a broader list, just how 3b's customer/supplier views are reached at all. */
	@Transactional(readOnly = true)
	public List<NpdOwnProjectRow> myProjects() {
		CurrentUser me = CurrentUser.get();
		List<NpdProject> mine;
		if (CUSTOMER_EDITORS.contains(me.role())) {
			mine = projects.findByCustomerCompanyIdInOrderByIdDesc(Set.of(me.companyId()));
		}
		else if (SUPPLIER_EDITORS.contains(me.role())) {
			mine = projects.findBySupplierCompanyIdOrderByIdDesc(me.companyId());
		}
		else {
			mine = List.of();
		}
		return mine.stream()
			.map(p -> new NpdOwnProjectRow(p.id, p.npdNumber, p.partNumber, p.revision, p.stage, p.targetSampleDate,
					delayDays(p)))
			.toList();
	}

	@Transactional(readOnly = true)
	public NpdView get(long id) {
		CurrentUser me = CurrentUser.get();
		return view(me, visible(me, id));
	}

	/** SRS §12.8.1/§12.8.3 project-header fields (owner, risk level, sample dates), ERGO Admin/NPD only. Same shape as
	 * every other form in this module: a full replace of these fields, not a patch (send the current value back for
	 * anything you don't want to change). */
	public NpdView updateProject(long id, NpdProjectForm form) {
		CurrentUser me = CurrentUser.get();
		NpdProject project = visible(me, id);
		require(NpdAction.UPDATE_PROJECT, me, project);
		if (form.ownerUserId() != null) {
			UserRef owner = directory.users(Set.of(form.ownerUserId())).get(form.ownerUserId());
			if (owner == null) {
				throw ApiException.unprocessable("Owner not found");
			}
			// Same rule as M1 on an activity's responsible person: an NPD project's owner is an ERGO-internal
			// responsibility, so only an ERGO colleague can hold it — never a customer's or supplier's employee.
			if (owner.companyId() != me.companyId()) {
				throw ApiException.unprocessable("The NPD project owner must be an ERGO user");
			}
		}
		project.ownerUserId = form.ownerUserId();
		project.riskLevel = form.riskLevel();
		project.targetSampleDate = form.targetSampleDate();
		project.actualSampleSubmissionDate = form.actualSampleSubmissionDate();
		return view(me, project);
	}

	// ---------------------------------------------------------------- workflow (SRS §14.5)

	/** Forward-only "Close Stage": blocked unless every mandatory activity of the current stage is DONE, and (when
	 * approval_required_flag is set on top of that) has an APPROVED npd_approvals row linked to it. */
	public NpdView closeStage(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		NpdProject project = visible(me, id);
		require(NpdAction.CLOSE_STAGE, me, project);
		NpdStage current = project.stage;
		List<NpdActivity> mandatory = activities.findByNpdProjectIdAndPhaseNameAndMandatoryFlagTrue(id, current);
		// M2: the gate needs an APPROVED approval of the *right* approvalType linked to the activity, not just any
		// approved approval linked to it — otherwise ERGO could clear a CUSTOMER_SAMPLE_APPROVAL-gated activity by
		// requesting and self-approving an unrelated STAGE_GATE approval instead.
		Map<Long, Set<ApprovalType>> approvedTypesByActivity = approvals
			.findByNpdProjectIdAndApprovalStatusOrderById(id, ApprovalStatus.APPROVED).stream()
			.filter(a -> a.linkedActivityId != null)
			.collect(Collectors.groupingBy(a -> a.linkedActivityId, Collectors.mapping(a -> a.approvalType,
					Collectors.toSet())));
		List<String> open = mandatory.stream()
			.filter(a -> a.status != ActivityStatus.DONE || a.approvalRequiredFlag
					&& !approvedTypesByActivity.getOrDefault(a.id, Set.of()).contains(a.requiredApprovalType))
			.map(a -> a.status != ActivityStatus.DONE ? a.activityName : a.activityName + " (pending approval)")
			.toList();
		if (!open.isEmpty()) {
			throw ApiException
				.conflict("Complete these mandatory activities before closing this stage: " + String.join(", ", open));
		}
		// Phase 5d: Final Sample Readiness additionally needs a real Quality sign-off (SRS's "Final" inspection
		// against this project) before it can close - a deliberate exception to this codebase's usual "later module
		// reaches into the earlier one" build-order direction (ARCHITECTURE.md §8 2026-10-01), since Quality did not
		// exist when this stage gate was first built.
		if (current == NpdStage.FINAL_SAMPLE_READINESS && !qualityApi.npdFinalSampleGateMet(id)) {
			throw ApiException.conflict(
					"A final sample inspection must pass (or be released with deviation) before Final Sample Readiness can be closed");
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

	public NpdView addActivity(long projectId, ActivityForm form) {
		CurrentUser me = CurrentUser.get();
		NpdProject project = visible(me, projectId);
		require(NpdAction.ADD_ACTIVITY, me, project);
		NpdActivity activity = new NpdActivity();
		activity.npdProjectId = project.id;
		activity.phaseName = project.stage;
		activity.ownerOrganizationType = form.ownerOrganizationType();
		activity.ownerCompanyId = resolveOwnerCompanyId(me, project, form.ownerOrganizationType(), form.ownerCompanyId());
		apply(activity, form, project.id);
		activities.save(activity);
		return view(me, project);
	}

	public NpdView updateActivity(long activityId, ActivityForm form) {
		CurrentUser me = CurrentUser.get();
		NpdActivity activity = activities.findById(activityId).orElseThrow(() -> ApiException.notFound("Activity"));
		NpdProject project = visible(me, activity.npdProjectId);
		require(NpdAction.EDIT_ACTIVITY, me, project, activity);
		CompanyType newOwnerType = form.ownerOrganizationType();
		long newOwnerCompanyId = resolveOwnerCompanyId(me, project, newOwnerType, form.ownerCompanyId());
		if (activity.ownerOrganizationType != newOwnerType || !Objects.equals(activity.ownerCompanyId, newOwnerCompanyId)) {
			// Reassigning the owner: the previous owner's own remarks, responsible person and checklist answers are
			// that company's own words/identity, not ERGO's classification of the activity, and must not carry over
			// to whoever the activity now belongs to (security review finding H2). The questions themselves
			// (NpdPromptChecklist) are ERGO's authored template, not the leak, and stay put.
			activity.remarks = null;
			activity.responsibleUserId = null;
			Set<Long> checklistIds = ids(checklists.findByNpdActivityIdInOrderBySequenceNo(Set.of(activityId)),
					c -> c.id);
			if (!checklistIds.isEmpty()) {
				responses.deleteByChecklistIdIn(checklistIds);
			}
		}
		activity.ownerOrganizationType = newOwnerType;
		activity.ownerCompanyId = newOwnerCompanyId;
		apply(activity, form, project.id);
		return view(me, project);
	}

	/** SRS §17.4 "supplier can update only assigned activities" (extended to the customer too): status, actual date
	 * and the issue/risk flags/remarks of an activity the caller's own company owns, never its name, owner,
	 * mandatory/approval flags or dependency. ERGO Admin/NPD may also use this on any activity (already-broader
	 * rights), same as every other activity action in this module. */
	public NpdView updateOwnActivity(long activityId, ActivityProgressForm form) {
		CurrentUser me = CurrentUser.get();
		NpdActivity activity = activities.findById(activityId).orElseThrow(() -> ApiException.notFound("Activity"));
		NpdProject project = visible(me, activity.npdProjectId);
		requireOwnActivityScope(me, activity);
		require(NpdAction.UPDATE_OWN_ACTIVITY, me, project, activity);
		if (form.status() == ActivityStatus.BLOCKED) {
			throw ApiException.unprocessable("Use \"Mark Blocked\" to block an activity, with a reason");
		}
		activity.status = form.status();
		activity.actualDate = form.actualDate();
		activity.issueFlag = form.issueFlag();
		activity.riskFlag = form.riskFlag();
		activity.remarks = text(form.remarks());
		return view(me, project);
	}

	/** SRS §12.8.3/§17.4 "Mark Blocked": toggles blocked_flag, with a required reason (audited). ERGO on any
	 * activity; the owning company on its own only. */
	public NpdView markBlocked(long activityId, String reason) {
		CurrentUser me = CurrentUser.get();
		NpdActivity activity = activities.findById(activityId).orElseThrow(() -> ApiException.notFound("Activity"));
		NpdProject project = visible(me, activity.npdProjectId);
		requireOwnActivityScope(me, activity);
		require(NpdAction.MARK_BLOCKED, me, project, activity);
		audit.reason(required(reason, "A reason is required to mark an activity blocked"));
		activity.blockedFlag = true;
		return view(me, project);
	}

	/** Clears blocked_flag once resolved. Not named by the SRS, but built symmetrically: no permanent dead end. */
	public NpdView unblock(long activityId, String comment) {
		CurrentUser me = CurrentUser.get();
		NpdActivity activity = activities.findById(activityId).orElseThrow(() -> ApiException.notFound("Activity"));
		NpdProject project = visible(me, activity.npdProjectId);
		requireOwnActivityScope(me, activity);
		require(NpdAction.UNBLOCK, me, project, activity);
		reasonIfGiven(comment);
		activity.blockedFlag = false;
		return view(me, project);
	}

	// ---------------------------------------------------------------- prompt checklists (SRS §13.4.4-13.4.5)

	/** Author a checklist question, ERGO Admin/NPD only: template authoring, not a transactional response. */
	public NpdView addPromptChecklist(long projectId, PromptChecklistForm form) {
		CurrentUser me = CurrentUser.get();
		NpdProject project = visible(me, projectId);
		require(NpdAction.MANAGE_PROMPT_CHECKLIST, me, project);
		if (form.activityId() == null) {
			throw ApiException.unprocessable("An activity is required");
		}
		NpdActivity activity = activityInProject(project, form.activityId());
		NpdPromptChecklist checklist = new NpdPromptChecklist();
		checklist.npdActivityId = activity.id;
		applyChecklist(checklist, form);
		checklists.save(checklist);
		return view(me, project);
	}

	/** Edits the question itself; the checklist's own activity never moves ({@code activityId} is ignored here). */
	public NpdView updatePromptChecklist(long checklistId, PromptChecklistForm form) {
		CurrentUser me = CurrentUser.get();
		NpdPromptChecklist checklist = checklists.findById(checklistId)
			.orElseThrow(() -> ApiException.notFound("Prompt checklist"));
		NpdActivity activity = activities.findById(checklist.npdActivityId)
			.orElseThrow(() -> ApiException.notFound("Activity"));
		NpdProject project = visible(me, activity.npdProjectId);
		require(NpdAction.MANAGE_PROMPT_CHECKLIST, me, project);
		applyChecklist(checklist, form);
		return view(me, project);
	}

	/** Answer one question; upserts the one current answer (SRS §13.4.5, V9's UNIQUE constraint on checklist_id).
	 * ERGO on any activity; the owning company on its own only. */
	public NpdView respondToPrompt(long checklistId, PromptResponseForm form) {
		CurrentUser me = CurrentUser.get();
		NpdPromptChecklist checklist = checklists.findById(checklistId)
			.orElseThrow(() -> ApiException.notFound("Prompt checklist"));
		NpdActivity activity = activities.findById(checklist.npdActivityId)
			.orElseThrow(() -> ApiException.notFound("Activity"));
		NpdProject project = visible(me, activity.npdProjectId);
		requireOwnActivityScope(me, activity);
		require(NpdAction.RESPOND_TO_PROMPT, me, project, activity);
		NpdPromptResponse response = responses.findByChecklistId(checklistId).orElseGet(NpdPromptResponse::new);
		response.checklistId = checklistId;
		response.responseValue = form.responseValue().trim();
		response.remarks = text(form.remarks());
		response.respondedByUserId = me.userId();
		response.respondedAt = Instant.now();
		responses.save(response);
		return view(me, project);
	}

	// ---------------------------------------------------------------- issues/risks (SRS §13.4.6)

	/** Raise an issue/risk, ERGO Admin/NPD only (confirmed by the user: create is ERGO-only for every customer/
	 * supplier role, per §10.6's literal Create=X). */
	public NpdView raiseIssue(long projectId, IssueRiskForm form) {
		CurrentUser me = CurrentUser.get();
		NpdProject project = visible(me, projectId);
		require(NpdAction.RAISE_ISSUE, me, project);
		Long linkedActivityId = form.linkedActivityId() == null ? null
				: activityInProject(project, form.linkedActivityId()).id;
		NpdIssueRisk issue = new NpdIssueRisk();
		issue.npdProjectId = project.id;
		issue.linkedActivityId = linkedActivityId;
		issue.type = form.type();
		issue.title = form.title().trim();
		issue.description = text(form.description());
		issue.severity = form.severity();
		issue.ownerOrganizationType = form.ownerOrganizationType();
		issue.ownerCompanyId = resolveOwnerCompanyId(me, project, form.ownerOrganizationType(), form.ownerCompanyId());
		issue.targetDate = form.targetDate();
		issueRisks.save(issue);
		return view(me, project);
	}

	/** The narrower status-only update: ERGO on anything, the owning company on its own only — "respond, don't
	 * reclassify", the same pattern as an activity's own progress update. Reopening a closed issue (previous status
	 * CLOSED, new status not) requires a reason, audited (CLAUDE.md's reopen non-negotiable); every other move's
	 * comment is optional. Unavailable once the project itself is CLOSED, same as every other write in this module. */
	public NpdView updateIssueStatus(long issueId, IssueStatusForm form) {
		CurrentUser me = CurrentUser.get();
		NpdIssueRisk issue = issueRisks.findById(issueId).orElseThrow(() -> ApiException.notFound("Issue/risk"));
		NpdProject project = visible(me, issue.npdProjectId);
		requireOwnIssueScope(me, issue);
		require(NpdAction.UPDATE_ISSUE, me, project, issue);
		boolean reopening = issue.status == IssueRiskStatus.CLOSED && form.status() != IssueRiskStatus.CLOSED;
		if (reopening) {
			audit.reason(required(form.comment(), "A reason is required to reopen a closed issue"));
		}
		else {
			reasonIfGiven(form.comment());
		}
		issue.status = form.status();
		issue.closureDate = form.status() == IssueRiskStatus.CLOSED ? LocalDate.now() : null;
		return view(me, project);
	}

	// ---------------------------------------------------------------- approvals (SRS §13.4.7)

	/** Request an approval, ERGO Admin/NPD only. {@code stageName} is the project's own stage at request time (the
	 * gate the request sits in); {@code approverRole} is computed from {@code approvalType}, never client-supplied. */
	public NpdView requestApproval(long projectId, ApprovalForm form) {
		CurrentUser me = CurrentUser.get();
		NpdProject project = visible(me, projectId);
		require(NpdAction.REQUEST_APPROVAL, me, project);
		Long linkedActivityId = form.linkedActivityId() == null ? null
				: activityInProject(project, form.linkedActivityId()).id;
		NpdApproval approval = new NpdApproval();
		approval.npdProjectId = project.id;
		approval.linkedActivityId = linkedActivityId;
		approval.stageName = project.stage;
		approval.approvalType = form.approvalType();
		approval.requestedByUserId = me.userId();
		approval.approverRole = form.approvalType().approverRole();
		approval.approvalStatus = ApprovalStatus.PENDING;
		approvals.save(approval);
		return view(me, project);
	}

	/** Approve or reject a still-PENDING approval; a reason is required to reject (audited), optional to approve.
	 * Whoever the row's own approver_role names, plus ERGO Admin for an ERGO-owned type only (never a CUSTOMER_*
	 * type — that stays the customer's own gate) — {@link #visible} already confines a customer decider to their
	 * own project's company, so no separate company check is needed here. Never the same person who requested it. */
	public NpdView decideApproval(long approvalId, ApprovalDecisionForm form) {
		CurrentUser me = CurrentUser.get();
		NpdApproval approval = approvals.findById(approvalId).orElseThrow(() -> ApiException.notFound("Approval"));
		NpdProject project = visible(me, approval.npdProjectId);
		requireCanDecideApproval(me, project, approval);
		String comments = text(form.comments());
		if (form.approved()) {
			reasonIfGiven(comments);
		}
		else {
			comments = required(comments, "A reason is required to reject an approval");
			audit.reason(comments);
		}
		approval.approvalStatus = form.approved() ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
		approval.approvedByUserId = me.userId();
		approval.approvedAt = Instant.now();
		approval.approvalComments = comments;
		return view(me, project);
	}

	// ---------------------------------------------------------------- rules

	/** Record scope. Out-of-scope NPD projects do not exist for the caller (404, not 403). */
	private NpdProject visible(CurrentUser me, long id) {
		NpdProject project = projects.findById(id).orElse(null);
		boolean inScope = project != null && (FULL_VIEWERS.contains(me.role())
				|| me.role() == ERGO_KAM && directory.accountsOf(me.userId()).contains(project.customerCompanyId)
				|| CUSTOMER_EDITORS.contains(me.role()) && me.companyId() == project.customerCompanyId
				|| SUPPLIER_EDITORS.contains(me.role()) && me.companyId() == project.supplierCompanyId);
		if (!inScope) {
			throw ApiException.notFound("NPD project");
		}
		return project;
	}

	/** Project-level actions: header edit, stage close, and everything EDITORS alone may originate. */
	private static boolean allowed(NpdAction action, CurrentUser me, NpdProject project) {
		boolean editor = EDITORS.contains(me.role());
		return switch (action) {
			case UPDATE_PROJECT -> editor;
			case CLOSE_STAGE, ADD_ACTIVITY, MANAGE_PROMPT_CHECKLIST, RAISE_ISSUE, REQUEST_APPROVAL ->
				editor && project.stage != NpdStage.CLOSED;
			default -> false; // CONVERT acts on an RFQ; the rest need a specific activity/issue/approval
		};
	}

	/** Activity-level actions: ERGO's full edit, plus the owning company's own progress/response/blocked-toggle. */
	private static boolean allowed(NpdAction action, CurrentUser me, NpdProject project, NpdActivity activity) {
		if (project.stage == NpdStage.CLOSED) {
			return false;
		}
		boolean editor = EDITORS.contains(me.role());
		boolean ownCompany = (CUSTOMER_EDITORS.contains(me.role()) || SUPPLIER_EDITORS.contains(me.role()))
				&& ownsActivity(me, activity);
		return switch (action) {
			case EDIT_ACTIVITY -> editor;
			case MARK_BLOCKED -> !activity.blockedFlag && (editor || ownCompany);
			case UNBLOCK -> activity.blockedFlag && (editor || ownCompany);
			case UPDATE_OWN_ACTIVITY, RESPOND_TO_PROMPT -> editor || ownCompany;
			default -> false;
		};
	}

	private static boolean ownsActivity(CurrentUser me, NpdActivity activity) {
		return activity.ownerOrganizationType == me.companyType()
				&& Objects.equals(activity.ownerCompanyId, me.companyId());
	}

	/** A customer/supplier caller may only reach an activity their own company owns; another company's activity
	 * does not exist for them (404), the same record-scope pattern used at the project level ({@link #visible}).
	 * ERGO's EDITORS reach any activity regardless (their broader rights are enforced by require() instead). */
	private static void requireOwnActivityScope(CurrentUser me, NpdActivity activity) {
		if (!EDITORS.contains(me.role()) && !ownsActivity(me, activity)) {
			throw ApiException.notFound("Activity");
		}
	}

	private static boolean ownsIssue(CurrentUser me, NpdIssueRisk issue) {
		return issue.ownerOrganizationType == me.companyType() && Objects.equals(issue.ownerCompanyId, me.companyId());
	}

	/** A customer/supplier caller may only reach an issue/risk its own company owns; another company's issue does
	 * not exist for them (404), the exact same record-scope pattern as {@link #requireOwnActivityScope}. ERGO's
	 * EDITORS reach any issue regardless. */
	private static void requireOwnIssueScope(CurrentUser me, NpdIssueRisk issue) {
		if (!EDITORS.contains(me.role()) && !ownsIssue(me, issue)) {
			throw ApiException.notFound("Issue/risk");
		}
	}

	/** Issue/risk actions: ERGO on anything, the owning company on its own only (never the classification fields);
	 * unavailable once the project itself is CLOSED, same as every activity-level action. Scope (which company may
	 * reach this issue at all) is {@link #requireOwnIssueScope}'s job, checked separately/first — this only decides
	 * whether the action is currently available to a caller who is already in scope. */
	private static boolean allowed(NpdAction action, CurrentUser me, NpdProject project, NpdIssueRisk issue) {
		if (action != NpdAction.UPDATE_ISSUE || project.stage == NpdStage.CLOSED) {
			return false;
		}
		boolean editor = EDITORS.contains(me.role());
		boolean ownCompany = (CUSTOMER_EDITORS.contains(me.role()) || SUPPLIER_EDITORS.contains(me.role()))
				&& ownsIssue(me, issue);
		return editor || ownCompany;
	}

	/** Splits "this role could never decide this approval type" (404 — a customer/supplier or the wrong ERGO role
	 * must not be able to tell a hidden approval type exists on their own project just from getting a 409 instead
	 * of a 404, security review finding L2) from "the row just isn't decidable right now" (409: already decided, or
	 * the project itself is CLOSED — the latter matching every other write in this module). */
	private static void requireCanDecideApproval(CurrentUser me, NpdProject project, NpdApproval approval) {
		boolean adminOverride = me.role() == ERGO_ADMIN && approval.approvalType.ergoOwned();
		if (!adminOverride && me.role() != approval.approverRole) {
			throw ApiException.notFound("Approval");
		}
		if (project.stage == NpdStage.CLOSED || approval.approvalStatus != ApprovalStatus.PENDING) {
			throw ApiException.conflict("This action is not available for this approval right now");
		}
		// M-1: whoever requested an approval may not also be the one who decides it — approval rights are separate
		// from edit rights even when the same role holds both (e.g. ERGO NPD requests and would otherwise approve
		// its own STAGE_GATE). A different person of the same role, or ERGO Admin (for an ERGO-owned type), still
		// can.
		if (me.userId() == approval.requestedByUserId) {
			throw ApiException.conflict("You cannot decide an approval you requested yourself");
		}
	}

	/** Approval decisions, for the actions list only (the service method above enforces the real gate via
	 * {@link #requireCanDecideApproval}, which tells a not-my-type 404 apart from an already-decided 409): ERGO
	 * Admin always, plus whoever the row's own approver_role names; only while still PENDING and the project isn't
	 * CLOSED. */
	private static boolean allowed(NpdAction action, CurrentUser me, NpdProject project, NpdApproval approval) {
		return action == NpdAction.DECIDE_APPROVAL && project.stage != NpdStage.CLOSED
				&& approval.approvalStatus == ApprovalStatus.PENDING
				&& (me.role() == ERGO_ADMIN && approval.approvalType.ergoOwned() || me.role() == approval.approverRole)
				&& me.userId() != approval.requestedByUserId;
	}

	private static Set<NpdAction> projectActions(CurrentUser me, NpdProject project) {
		return Arrays.stream(NpdAction.values()).filter(a -> allowed(a, me, project))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(NpdAction.class)));
	}

	private static Set<NpdAction> activityActions(CurrentUser me, NpdProject project, NpdActivity activity) {
		return Arrays.stream(NpdAction.values()).filter(a -> allowed(a, me, project, activity))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(NpdAction.class)));
	}

	private static Set<NpdAction> issueActions(CurrentUser me, NpdProject project, NpdIssueRisk issue) {
		return Arrays.stream(NpdAction.values()).filter(a -> allowed(a, me, project, issue))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(NpdAction.class)));
	}

	private static Set<NpdAction> approvalActions(CurrentUser me, NpdProject project, NpdApproval approval) {
		return Arrays.stream(NpdAction.values()).filter(a -> allowed(a, me, project, approval))
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

	/** Scope ({@link #requireOwnIssueScope}) is already checked by every caller before this; the only remaining
	 * reason this can fail is the business-state gate (currently: the project is CLOSED), so 409 is correct here. */
	private static void require(NpdAction action, CurrentUser me, NpdProject project, NpdIssueRisk issue) {
		if (!allowed(action, me, project, issue)) {
			throw ApiException.conflict("This action is not available for this issue/risk right now");
		}
	}

	/** Shared by add and edit. Dates/status/mandatory flow straight from the form: the caller already validated the
	 * shape; only cross-references (dependency, responsible user) and the BLOCKED guard need checking here. Owner
	 * fields are set by the caller (resolveOwnerCompanyId), not here. */
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
		if (form.responsibleUserId() != null) {
			UserRef responsible = directory.users(Set.of(form.responsibleUserId())).get(form.responsibleUserId());
			if (responsible == null) {
				throw ApiException.unprocessable("Responsible user not found");
			}
			// M1: a responsible person must belong to this activity's own owner company, not merely exist somewhere
			// in the directory — otherwise ERGO could hand a supplier's (or the wrong supplier's) employee name to
			// the customer, or vice versa, as this activity's "Responsible" person.
			if (responsible.companyId() != activity.ownerCompanyId) {
				throw ApiException.unprocessable("Responsible user must belong to this activity's own owner company");
			}
		}
		if (form.approvalRequired() && form.requiredApprovalType() == null) {
			throw ApiException.unprocessable("A required approval type must be chosen when approval is required");
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
		activity.approvalRequiredFlag = form.approvalRequired();
		// Server decides, never the client's own pairing of the two fields (matches approverRole's own precedent).
		activity.requiredApprovalType = form.approvalRequired() ? form.requiredApprovalType() : null;
	}

	private static void applyChecklist(NpdPromptChecklist checklist, PromptChecklistForm form) {
		checklist.questionText = form.questionText().trim();
		checklist.responseType = form.responseType();
		checklist.mandatoryFlag = form.mandatory();
		checklist.sequenceNo = form.sequenceNo();
	}

	/** ERGO ownership is always the caller's own company, never client-chosen; customer/supplier ownership must
	 * equal this project's own customer/supplier company (rejected otherwise). Shared by activities and issues. */
	private static long resolveOwnerCompanyId(CurrentUser me, NpdProject project, CompanyType type, Long requested) {
		return switch (type) {
			case ERGO -> me.companyId();
			case CUSTOMER -> {
				if (!Objects.equals(requested, project.customerCompanyId)) {
					throw ApiException.unprocessable("Owner company must be this project's own customer");
				}
				yield requested;
			}
			case SUPPLIER -> {
				if (!Objects.equals(requested, project.supplierCompanyId)) {
					throw ApiException.unprocessable("Owner company must be this project's own supplier");
				}
				yield requested;
			}
		};
	}

	/** Validates a foreign activity id belongs to this project; used by issues, approvals and prompt checklists. */
	private NpdActivity activityInProject(NpdProject project, long activityId) {
		return activities.findById(activityId)
			.filter(a -> a.npdProjectId.equals(project.id))
			.orElseThrow(() -> ApiException.unprocessable("The activity must belong to this NPD project"));
	}

	// ---------------------------------------------------------------- per-audience view assembly

	private NpdView view(CurrentUser me, NpdProject p) {
		boolean isCustomer = CUSTOMER_EDITORS.contains(me.role());
		boolean isSupplier = SUPPLIER_EDITORS.contains(me.role());
		// RfqApi's own record scope is per-audience (forErgo/forCustomer/forSupplier): the caller's own visible()
		// pass already proved this project (and so this RFQ) is theirs, so each is safe to call here.
		String rfqNumber = isCustomer ? rfqApi.forCustomer(p.sourceRfqId).number()
				: isSupplier ? rfqApi.forSupplier(p.sourceRfqId).number() : rfqApi.forErgo(p.sourceRfqId).number();
		Map<Long, UserRef> owner = directory.users(ids(List.of(p), x -> x.ownerUserId));

		List<NpdStageHistoryEntry> history = stageHistory.findByNpdProjectIdOrderById(p.id);
		Map<Long, UserRef> historyUsers = directory.users(ids(history, h -> h.changedByUserId));
		List<StageHistoryView> historyViews = history.stream()
			.map(h -> new StageHistoryView(h.stageName, h.previousStageName, userName(historyUsers, h.changedByUserId),
					h.changedAt, h.remarks))
			.toList();
		// H1: whatever ERGO typed as a stage-move comment (remarks) could name the other party or a price — the
		// customer/supplier variant drops it; changedBy stays (every stage move is an ERGO-only action).
		List<ExternalStageHistoryView> externalHistoryViews = history.stream()
			.map(h -> new ExternalStageHistoryView(h.stageName, h.previousStageName,
					userName(historyUsers, h.changedByUserId), h.changedAt))
			.toList();

		List<NpdActivity> acts = isCustomer
				? activities.findByNpdProjectIdAndOwnerOrganizationTypeAndOwnerCompanyIdOrderById(p.id,
						CompanyType.CUSTOMER, me.companyId())
				: isSupplier
						? activities.findByNpdProjectIdAndOwnerOrganizationTypeAndOwnerCompanyIdOrderById(p.id,
								CompanyType.SUPPLIER, me.companyId())
						: activities.findByNpdProjectIdOrderById(p.id);
		Map<Long, UserRef> activityUsers = directory.users(ids(acts, a -> a.responsibleUserId));
		Map<Long, CompanyRef> activityCompanies = directory.companies(ids(acts, a -> a.ownerCompanyId));
		Set<Long> activityIds = ids(acts, a -> a.id);
		Map<Long, List<PromptChecklistView>> checklistsByActivityId = checklistsByActivity(activityIds);
		List<ActivityView> activityViews = acts.stream()
			.map(a -> new ActivityView(a.id, a.phaseName, a.activityName, a.activityDescription,
					a.ownerOrganizationType, name(activityCompanies, a.ownerCompanyId), a.responsibleUserId,
					userName(activityUsers, a.responsibleUserId), a.plannedDate, a.baselineDate, a.actualDate,
					a.dependencyActivityId, a.status, a.blockedFlag, a.riskFlag, a.issueFlag, a.mandatoryFlag,
					a.approvalRequiredFlag, a.requiredApprovalType, a.remarks,
					checklistsByActivityId.getOrDefault(a.id, List.of()), activityActions(me, p, a)))
			.toList();

		List<NpdIssueRisk> issues;
		List<NpdApproval> approvalRows;
		if (isCustomer) {
			issues = issuesFor(p, me.companyId());
			approvalRows = approvals.findByNpdProjectIdAndApprovalTypeInOrderById(p.id, CUSTOMER_APPROVAL_TYPES);
		}
		else if (isSupplier) {
			issues = issuesFor(p, me.companyId());
			approvalRows = List.of(); // no approvals data at all for a supplier (SRS §10.6: X on Approve)
		}
		else {
			issues = issueRisks.findByNpdProjectIdOrderById(p.id);
			approvalRows = approvals.findByNpdProjectIdOrderById(p.id);
		}
		Map<Long, CompanyRef> issueCompanies = directory.companies(ids(issues, i -> i.ownerCompanyId));
		List<IssueRiskView> issueViews = issues.stream().map(i -> issueView(me, p, i, issueCompanies)).toList();
		Map<Long, UserRef> approvalUsers = directory
			.users(ids(approvalRows, a -> a.requestedByUserId, a -> a.approvedByUserId));
		List<ApprovalView> approvalViews = approvalRows.stream().map(a -> approvalView(me, p, a, approvalUsers))
			.toList();

		Long delay = delayDays(p);
		Set<NpdAction> actions = projectActions(me, p);

		if (isSupplier) {
			return new SupplierNpdView("SUPPLIER", p.id, p.npdNumber, p.sourceRfqId, rfqNumber, p.partNumber, p.revision,
					p.description, p.quoteReference, p.stage, p.targetSampleDate, p.actualSampleSubmissionDate, delay,
					activityViews, issueViews, externalHistoryViews, actions, p.createdAt, p.updatedAt);
		}
		if (isCustomer) {
			CompanyRef customer = directory.company(p.customerCompanyId).orElseThrow();
			return new CustomerNpdView("CUSTOMER", p.id, p.npdNumber, p.sourceRfqId, rfqNumber,
					new Named(customer.id(), customer.code(), customer.name()), p.partNumber, p.revision, p.description,
					p.stage, p.targetSampleDate, p.actualSampleSubmissionDate, delay, activityViews,
					issueViews, approvalViews, externalHistoryViews, actions, p.createdAt, p.updatedAt);
		}
		CompanyRef customer = directory.company(p.customerCompanyId).orElseThrow();
		CompanyRef supplier = directory.company(p.supplierCompanyId).orElseThrow();
		return new ErgoNpdView("ERGO", p.id, p.npdNumber, p.sourceRfqId, rfqNumber,
				new Named(customer.id(), customer.code(), customer.name()),
				new Named(supplier.id(), supplier.code(), supplier.name()), p.partNumber, p.revision, p.description,
				p.quoteReference, p.stage, p.targetSampleDate, p.actualSampleSubmissionDate, delay, p.ownerUserId,
				userName(owner, p.ownerUserId), p.riskLevel, activityViews, issueViews, approvalViews, historyViews,
				actions, p.createdAt, p.updatedAt);
	}

	/** A customer's or supplier's own issues/risks: owned by their own company only. Deliberately NOT unioned with
	 * issues merely linked to one of their own activities: a linked issue can be owned by the other external party
	 * (e.g. an ERGO-raised issue about the customer's own activity but owned by the supplier because the supplier is
	 * the one resolving it) — showing it would leak that party's name through IssueRiskView.ownerCompanyName, which
	 * is exactly the two-way anonymity rule this module exists to enforce (ARCHITECTURE.md §8 2026-09-26). */
	private List<NpdIssueRisk> issuesFor(NpdProject project, long companyId) {
		return issueRisks.findByNpdProjectIdAndOwnerCompanyIdOrderById(project.id, companyId);
	}

	private static IssueRiskView issueView(CurrentUser me, NpdProject project, NpdIssueRisk i,
			Map<Long, CompanyRef> companies) {
		return new IssueRiskView(i.id, i.type, i.title, i.description, i.severity, i.ownerOrganizationType,
				name(companies, i.ownerCompanyId), i.linkedActivityId, i.targetDate, i.status, i.closureDate,
				issueActions(me, project, i));
	}

	private static ApprovalView approvalView(CurrentUser me, NpdProject project, NpdApproval a,
			Map<Long, UserRef> users) {
		return new ApprovalView(a.id, a.approvalType, a.stageName, a.linkedActivityId,
				userName(users, a.requestedByUserId), a.requestedAt, a.approverRole, a.approvalStatus,
				userName(users, a.approvedByUserId), a.approvedAt, a.approvalComments, approvalActions(me, project, a));
	}

	/** ponytail: one query per (checklist, response) pair, batched over the given activity ids — fine at 3b's
	 * expected volume (a handful of questions per activity). */
	private Map<Long, List<PromptChecklistView>> checklistsByActivity(Collection<Long> activityIds) {
		if (activityIds.isEmpty()) {
			return Map.of();
		}
		List<NpdPromptChecklist> items = checklists.findByNpdActivityIdInOrderBySequenceNo(activityIds);
		Map<Long, NpdPromptResponse> byChecklist = responses.findByChecklistIdIn(ids(items, c -> c.id)).stream()
			.collect(Collectors.toMap(r -> r.checklistId, r -> r));
		Map<Long, UserRef> responders = directory.users(ids(byChecklist.values(), r -> r.respondedByUserId));
		Map<Long, List<PromptChecklistView>> byActivity = new LinkedHashMap<>();
		for (NpdPromptChecklist c : items) {
			NpdPromptResponse r = byChecklist.get(c.id);
			PromptChecklistView view = new PromptChecklistView(c.id, c.questionText, c.responseType, c.mandatoryFlag,
					c.sequenceNo, r == null ? null : r.responseValue, r == null ? null : r.remarks,
					r == null ? null : userName(responders, r.respondedByUserId), r == null ? null : r.respondedAt);
			byActivity.computeIfAbsent(c.npdActivityId, k -> new ArrayList<>()).add(view);
		}
		return byActivity;
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
