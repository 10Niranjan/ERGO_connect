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
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;
import com.ergo.connect.npd.domain.NpdDtos.ActivityForm;
import com.ergo.connect.npd.domain.NpdDtos.ActivityProgressForm;
import com.ergo.connect.npd.domain.NpdDtos.ActivityView;
import com.ergo.connect.npd.domain.NpdDtos.ApprovalDecisionForm;
import com.ergo.connect.npd.domain.NpdDtos.ApprovalForm;
import com.ergo.connect.npd.domain.NpdDtos.ApprovalView;
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
import com.ergo.connect.npd.domain.NpdDtos.PlanHistoryView;
import com.ergo.connect.npd.domain.NpdDtos.PlanStepForm;
import com.ergo.connect.npd.domain.NpdDtos.PromptChecklistForm;
import com.ergo.connect.npd.domain.NpdDtos.PromptChecklistView;
import com.ergo.connect.npd.domain.NpdDtos.PromptResponseForm;
import com.ergo.connect.npd.domain.NpdDtos.ProgressView;
import com.ergo.connect.npd.domain.NpdDtos.ValidationDecisionView;
import com.ergo.connect.npd.domain.NpdDtos.ValidationView;
import com.ergo.connect.npd.domain.NpdDtos.StageProgress;
import com.ergo.connect.npd.domain.NpdDtos.StageState;
import com.ergo.connect.npd.domain.NpdDtos.StageHistoryView;
import com.ergo.connect.npd.domain.NpdDtos.SupplierNpdView;
import com.ergo.connect.quality.api.QualityApi;
import com.ergo.connect.quotation.api.QuotationApi.ApprovedPart;
import com.ergo.connect.rfq.api.RfqApi;
import com.ergo.connect.rfq.api.RfqApi.ItemRef;

/**
 * Phase 3a: the 9-stage NPD workflow (SRS §13.4, §14.5, §17.4), ERGO-only.
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

	/** The three customer-facing approval types (SRS §13.4.7); a supplier's view never queries approvals at all. */
	private static final Set<ApprovalType> CUSTOMER_APPROVAL_TYPES = EnumSet.of(ApprovalType.CUSTOMER_SAMPLE_APPROVAL,
			ApprovalType.CUSTOMER_MILESTONE_ACKNOWLEDGMENT, ApprovalType.CUSTOMER_QUALITY_ACCEPTANCE);


	private final NpdProjectRepository projects;

	private final NpdActivityRepository activities;

	private final NpdStageHistoryRepository stageHistory;

	private final NpdPromptChecklistRepository checklists;

	private final NpdPromptResponseRepository responses;

	private final NpdIssueRiskRepository issueRisks;

	private final NpdApprovalRepository approvals;

	private final SamplePoRepository samplePos;

	private final NpdPlanHistoryRepository planHistory;

	private final NpdValidationDecisionRepository validationDecisions;

	private final RfqApi rfqApi;

	private final QualityApi qualityApi;

	private final Directory directory;

	private final AuditLogService audit;

	private final JdbcTemplate jdbc;

	private final Notifier notifier;

	NpdService(NpdProjectRepository projects, NpdActivityRepository activities,
			NpdStageHistoryRepository stageHistory, NpdPromptChecklistRepository checklists,
			NpdPromptResponseRepository responses, NpdIssueRiskRepository issueRisks,
			NpdApprovalRepository approvals, SamplePoRepository samplePos, NpdPlanHistoryRepository planHistory,
			NpdValidationDecisionRepository validationDecisions, RfqApi rfqApi, QualityApi qualityApi, Directory directory, AuditLogService audit, JdbcTemplate jdbc,
			Notifier notifier) {
		this.notifier = notifier;
		this.planHistory = planHistory;
		this.validationDecisions = validationDecisions;
		this.projects = projects;
		this.activities = activities;
		this.stageHistory = stageHistory;
		this.checklists = checklists;
		this.responses = responses;
		this.issueRisks = issueRisks;
		this.approvals = approvals;
		this.samplePos = samplePos;
		this.rfqApi = rfqApi;
		this.qualityApi = qualityApi;
		this.directory = directory;
		this.audit = audit;
		this.jdbc = jdbc;
	}

	// ---------------------------------------------------------------- Sample PO -> project, plan assignment

	/** Flow doc step 7: the KAM's acceptance of a Sample PO creates the sample development project, assigned to one ERGO
	 * NPD and one ERGO Quality user. The plan is still to be built by ERGO NPD, so the supplier cannot see the project
	 * yet ({@link PlanStatus#AWAITING_PLAN}). Called only by SamplePoService, which has already checked the caller,
	 * the scope and the Sample PO's state. */
	NpdView createFromSamplePo(CurrentUser me, SamplePo po, ApprovedPart quote, ItemRef part, UserRef npdUser,
			UserRef qualityUser, String comment) {
		NpdProject project = new NpdProject();
		project.npdNumber = "NPD-%d-%05d".formatted(Year.now().getValue(),
				jdbc.queryForObject("SELECT nextval('npd_number_seq')", Long.class));
		project.sourceRfqId = quote.rfqId();
		project.sourceRfqItemId = po.rfqItemId;
		project.customerCompanyId = quote.customerCompanyId();
		project.supplierCompanyId = quote.supplierCompanyId();
		project.samplePoId = po.id;
		project.partNumber = part.partNumber();
		project.revision = part.revision();
		project.description = part.description();
		project.quoteReference = quote.quoteReference();
		// The project starts in Planning: ERGO NPD builds the plan, then releases it to the supplier.
		project.stage = NpdStage.PLANNING;
		project.planStatus = PlanStatus.AWAITING_PLAN;
		project.targetSampleDate = po.requiredDate;
		project.ownerUserId = npdUser.id();
		project.qualityOwnerUserId = qualityUser.id();
		project.createdByUserId = me.userId();
		try {
			projects.save(project);
		}
		catch (DataIntegrityViolationException e) {
			throw ApiException.conflict("A project already exists for this part."); // race defense (DB UNIQUE)
		}
		reasonIfGiven(comment);
		NpdStageHistoryEntry history = new NpdStageHistoryEntry();
		history.npdProjectId = project.id;
		history.stageName = NpdStage.PLANNING;
		history.previousStageName = null; // the very first row
		history.changedByUserId = me.userId();
		history.remarks = text(comment);
		stageHistory.save(history);
		notifier.send(NotificationKind.NPD_PROJECT_ASSIGNED, "npd", project.id, project.npdNumber, "Part " + project.partNumber,
				Recipients.users(Set.of(npdUser.id(), qualityUser.id())));
		return view(me, project);
	}

	/** Flow doc step 11: the KAM's acceptance of a production order creates the OFP project, a project like the sample one
	 * (same plan, execution, validation and approval workflow) with only the relevant stages and the OFP teams added,
	 * assigned to one ERGO OFP and one ERGO Quality user. Called only by ProductionPoService, which has already checked the
	 * caller, the scope and the state of the production order. */
	NpdView createFromProductionPo(CurrentUser me, ProductionPo po, NpdProject source, UserRef ofpUser, UserRef qualityUser,
			String comment) {
		NpdProject project = new NpdProject();
		project.npdNumber = "OFP-%d-%05d".formatted(Year.now().getValue(),
				jdbc.queryForObject("SELECT nextval('npd_number_seq')", Long.class));
		project.kind = ProjectKind.PRODUCTION;
		project.productionPoId = po.id;
		project.sourceProjectId = source.id;
		project.sourceRfqId = source.sourceRfqId;
		project.sourceRfqItemId = source.sourceRfqItemId;
		project.customerCompanyId = source.customerCompanyId;
		project.supplierCompanyId = source.supplierCompanyId;
		project.partNumber = source.partNumber;
		project.revision = source.revision;
		project.description = source.description;
		project.quoteReference = source.quoteReference;
		project.stage = NpdStage.PLANNING;
		project.planStatus = PlanStatus.AWAITING_PLAN;
		project.targetSampleDate = po.requiredDate;
		project.ownerUserId = ofpUser.id();
		project.qualityOwnerUserId = qualityUser.id();
		project.createdByUserId = me.userId();
		try {
			projects.save(project);
		}
		catch (DataIntegrityViolationException e) {
			throw ApiException.conflict("A project already exists for this production order."); // race defense (DB UNIQUE)
		}
		reasonIfGiven(comment);
		NpdStageHistoryEntry history = new NpdStageHistoryEntry();
		history.npdProjectId = project.id;
		history.stageName = NpdStage.PLANNING;
		history.previousStageName = null;
		history.changedByUserId = me.userId();
		history.remarks = text(comment);
		stageHistory.save(history);
		notifier.send(NotificationKind.NPD_PROJECT_ASSIGNED, "npd", project.id, project.npdNumber, "Part " + project.partNumber,
				Recipients.users(Set.of(ofpUser.id(), qualityUser.id())));
		return view(me, project);
	}

	/** Flow doc step 7: ERGO NPD, once the plan template is built, assigns it to the supplier's NPD, RFQ and Quality
	 * teams. The supplier sees the project from here on, with the steps that are theirs. */
	public NpdView assignPlan(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		NpdProject project = visible(me, id);
		require(NpdAction.ASSIGN_PLAN, me, project);
		if (activities.findByNpdProjectIdAndOwnerOrganizationTypeAndOwnerCompanyIdOrderById(id, CompanyType.SUPPLIER,
				project.supplierCompanyId).isEmpty()) {
			throw ApiException.conflict("Add at least one step for the supplier before assigning the plan");
		}
		reasonIfGiven(comment);
		project.planStatus = PlanStatus.ASSIGNED_TO_SUPPLIER;
		planEvent(me, project, NpdPlanHistoryEntry.Event.RELEASED, comment);
		notifier.send(NotificationKind.NPD_PLAN_ASSIGNED, "npd", project.id, project.npdNumber, "Part " + project.partNumber,
				editors(project.supplierCompanyId, supplierPlanTeam(project)));
		return view(me, project);
	}

	// ---------------------------------------------------------------- plan submission and agreement (flow doc step 8)

	/** The supplier fills in a step of the plan ERGO NPD released to it: the date, who is responsible, a remark. Only
	 * while the plan is with the supplier (assigned, or sent back); never the template itself (name, stage, owner). */
	public NpdView updatePlanStep(long activityId, PlanStepForm form) {
		CurrentUser me = CurrentUser.get();
		NpdActivity activity = activities.findById(activityId).orElseThrow(() -> ApiException.notFound("Activity"));
		lockProject(activity.npdProjectId);
		NpdProject project = visible(me, activity.npdProjectId);
		requireOwnActivityScope(me, project, activity);
		require(NpdAction.EDIT_PLAN_STEP, me, project, activity);
		if (form.responsibleUserId() != null) {
			UserRef responsible = directory.users(Set.of(form.responsibleUserId())).get(form.responsibleUserId());
			// the same rule as an activity's responsible person: someone of the step's own company, never a stranger
			if (responsible == null || responsible.companyId() != activity.ownerCompanyId) {
				throw ApiException.unprocessable("Responsible user must belong to your company");
			}
		}
		activity.plannedDate = form.plannedDate();
		activity.responsibleUserId = form.responsibleUserId();
		activity.remarks = text(form.remarks());
		return view(me, project);
	}

	/** Supplier NPD hands the filled-in plan to ERGO NPD. Every step the supplier owns needs a planned date first. */
	public NpdView submitPlan(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		lockProject(id);
		NpdProject project = visible(me, id);
		require(NpdAction.SUBMIT_PLAN, me, project);
		List<NpdActivity> steps = activities.findByNpdProjectIdAndOwnerOrganizationTypeAndOwnerCompanyIdOrderById(id,
				CompanyType.SUPPLIER, project.supplierCompanyId);
		List<String> undated = steps.stream().filter(a -> a.plannedDate == null).map(a -> a.activityName).toList();
		if (steps.isEmpty() || !undated.isEmpty()) {
			throw ApiException.conflict(steps.isEmpty() ? "The plan has no steps for you to submit"
					: "Give every step a planned date before submitting: " + String.join(", ", undated));
		}
		reasonIfGiven(comment);
		project.planStatus = PlanStatus.SUBMITTED;
		planEvent(me, project, NpdPlanHistoryEntry.Event.SUBMITTED, comment);
		notifier.send(NotificationKind.NPD_PLAN_SUBMITTED, "npd", project.id, project.npdNumber, "Part " + project.partNumber,
				planReviewers(project));
		return view(me, project);
	}

	/** ERGO NPD agrees the supplier's plan: execution starts. */
	public NpdView agreePlan(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		lockProject(id);
		NpdProject project = visible(me, id);
		require(NpdAction.AGREE_PLAN, me, project);
		reasonIfGiven(comment);
		project.planStatus = PlanStatus.AGREED;
		planEvent(me, project, NpdPlanHistoryEntry.Event.AGREED, comment);
		notifier.send(NotificationKind.NPD_PLAN_AGREED, "npd", project.id, project.npdNumber, "Part " + project.partNumber,
				editors(project.supplierCompanyId, supplierPlanTeam(project)));
		return view(me, project);
	}

	/** ERGO NPD sends the plan back to the supplier. The comment is mandatory, is kept in the plan's trail and reaches the
	 * supplier. */
	public NpdView sendBackPlan(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		lockProject(id);
		NpdProject project = visible(me, id);
		require(NpdAction.SEND_BACK_PLAN, me, project);
		String reason = required(comment, "A comment is required to send the plan back");
		audit.reason(reason);
		project.planStatus = PlanStatus.CHANGES_REQUESTED;
		planEvent(me, project, NpdPlanHistoryEntry.Event.SENT_BACK, reason);
		notifier.send(NotificationKind.NPD_PLAN_RETURNED, "npd", project.id, project.npdNumber, "Part " + project.partNumber,
				editors(project.supplierCompanyId, NpdRights.of(project).planSubmitters));
		return view(me, project);
	}

	/** Takes the project row's lock, so a step edit cannot slip in between a submit and ERGO's decision on it: the plan
	 * state read afterwards is the committed one. */
	void lockProject(long projectId) {
		jdbc.query("SELECT 1 FROM npd_projects WHERE npd_project_id = ? FOR UPDATE", rs -> {
		}, projectId);
	}

	private void planEvent(CurrentUser me, NpdProject project, NpdPlanHistoryEntry.Event event, String comment) {
		NpdPlanHistoryEntry entry = new NpdPlanHistoryEntry();
		entry.npdProjectId = project.id;
		entry.event = event;
		entry.comment = text(comment);
		entry.byUserId = me.userId();
		planHistory.save(entry);
	}

	/** The supplier teams a released plan goes to: NPD, RFQ and Quality, and the OFP team on a production project. */
	private static Set<Role> supplierPlanTeam(NpdProject project) {
		return project.kind == ProjectKind.PRODUCTION
				? Set.of(SUPPLIER_NPD, Role.SUPPLIER_RFQ, SUPPLIER_QUALITY, SUPPLIER_OFP)
				: Set.of(SUPPLIER_NPD, Role.SUPPLIER_RFQ, SUPPLIER_QUALITY);
	}

	/** Who reviews a submitted plan: ERGO's owning NPD roles (they alone can decide it) and the project's NPD owner. */
	private static Recipients planReviewers(NpdProject project) {
		Recipients owners = Recipients.ergo(NpdRights.of(project).owners.toArray(Role[]::new));
		return project.ownerUserId == null ? owners : Recipients.users(Set.of(project.ownerUserId)).and(owners);
	}

	// ---------------------------------------------------------------- reads

	@Transactional(readOnly = true)
	public List<NpdListRow> list() {
		CurrentUser me = CurrentUser.get();
		List<NpdProject> candidates;
		if (me.companyType() != CompanyType.ERGO) {
			candidates = List.of(); // the customer and supplier lists are /mine
		}
		else if (me.role() == ERGO_KAM) {
			Set<Long> accounts = directory.accountsOf(me.userId());
			candidates = accounts.isEmpty() ? List.of() : projects.findByCustomerCompanyIdInOrderByIdDesc(accounts);
		}
		else {
			candidates = projects.findAllByOrderByIdDesc();
		}
		// the record scope decides, per project and its kind
		List<NpdProject> visible = candidates.stream().filter(p -> inScope(me, p)).toList();
		Map<Long, CompanyRef> customers = directory.companies(ids(visible, p -> p.customerCompanyId));
		Map<Long, CompanyRef> suppliers = directory.companies(ids(visible, p -> p.supplierCompanyId));
		Map<Long, UserRef> owners = directory.users(ids(visible, p -> p.ownerUserId));
		return visible.stream()
			.map(p -> new NpdListRow(p.id, p.npdNumber, p.kind, rfqNumber(p.sourceRfqId), name(customers, p.customerCompanyId),
					name(suppliers, p.supplierCompanyId), p.partNumber, p.stage, p.targetSampleDate, delayDays(p),
					userName(owners, p.ownerUserId), p.riskLevel, p.planStatus))
			.toList();
	}

	/** SRS §12.15's customer "NPD" / supplier "NPD Tasks" nav entry point: the caller's own company's projects, with
	 * no field naming the other company (safe for both audiences). Every other role gets nothing here — this is not
	 * a broader list, just how 3b's customer/supplier views are reached at all. */
	@Transactional(readOnly = true)
	public List<NpdOwnProjectRow> myProjects() {
		CurrentUser me = CurrentUser.get();
		List<NpdProject> candidates;
		if (me.companyType() == CompanyType.CUSTOMER) {
			candidates = projects.findByCustomerCompanyIdInOrderByIdDesc(Set.of(me.companyId()));
		}
		else if (me.companyType() == CompanyType.SUPPLIER) {
			candidates = projects.findBySupplierCompanyIdAndPlanStatusNotOrderByIdDesc(me.companyId(), PlanStatus.AWAITING_PLAN);
		}
		else {
			candidates = List.of();
		}
		// which of the company's projects a role reaches depends on the project's kind (the OFP teams reach OFP projects only)
		List<NpdProject> mine = candidates.stream().filter(p -> inScope(me, p)).toList();
		return mine.stream()
			.map(p -> new NpdOwnProjectRow(p.id, p.npdNumber, p.kind, p.partNumber, p.revision, p.stage, p.targetSampleDate,
					delayDays(p)))
			.toList();
	}

	@Transactional(readOnly = true)
	public NpdView get(long id) {
		CurrentUser me = CurrentUser.get();
		return view(me, visible(me, id));
	}

	/** SRS §12.8.3/§15.5 audit trail tab. ERGO only, same rule as RFQ's history() (ARCHITECTURE.md §8 2026-10-05).
	 * Single entity only: {@code NpdProject} header row changes, not its activities/approvals/issues. */
	@Transactional(readOnly = true)
	public List<AuditEntry> history(long id) {
		CurrentUser me = CurrentUser.get();
		visible(me, id);
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.forbidden("The audit trail is internal to ERGO");
		}
		return audit.history("npd", "NpdProject", id);
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
		List<String> open = openMandatory(project);
		if (!open.isEmpty()) {
			throw ApiException
				.conflict("Complete these mandatory activities before closing this stage: " + String.join(", ", open));
		}
		// Phase 5d: Final Sample Readiness additionally needs a real Quality sign-off (SRS's "Final" inspection
		// against this project) before it can close - a deliberate exception to this codebase's usual "later module
		// reaches into the earlier one" build-order direction (ARCHITECTURE.md §8 2026-10-01), since Quality did not
		// exist when this stage gate was first built.
		if (current == NpdStage.FINAL_SAMPLE_READINESS) {
			if (!ModuleSwitch.readable(Module.QUALITY)) {
				// the gate cannot be evaluated, so it fails closed; the stage closes once Quality is back on
				throw ApiException.conflict("Final Sample Readiness is signed off by the Quality module, which is not "
						+ "available right now. Nothing was changed; close the stage once it is back on.");
			}
			if (!qualityApi.npdFinalSampleGateMet(id)) {
				throw ApiException.conflict(
						"A final sample inspection must pass (or be released with deviation) before Final Sample Readiness can be closed");
			}
			// flow doc step 9: ERGO Quality and Supplier Quality must both have approved; closing the stage submits the
			// project to Customer NPD and Customer Quality (step 10)
			if (project.validationStatus != ValidationStatus.VALIDATED) {
				throw ApiException.conflict("ERGO Quality and Supplier Quality must both approve the validation before Final "
						+ "Sample Readiness can be closed");
			}
		}
		if (current == NpdStage.CUSTOMER_FEEDBACK && project.validationStatus != ValidationStatus.CUSTOMER_APPROVED) {
			throw ApiException.conflict("Customer NPD and Customer Quality must both approve before Customer Feedback can be closed");
		}
		NpdStage next = current.next(project.kind);
		reasonIfGiven(comment);
		project.stage = next;
		NpdStageHistoryEntry history = new NpdStageHistoryEntry();
		history.npdProjectId = project.id;
		history.stageName = next;
		history.previousStageName = current;
		history.changedByUserId = me.userId();
		history.remarks = text(comment);
		stageHistory.save(history);
		if (current == NpdStage.FINAL_SAMPLE_READINESS) {
			project.validationStatus = ValidationStatus.IN_CUSTOMER_REVIEW;
			notifier.send(NotificationKind.NPD_CUSTOMER_REVIEW_REQUESTED, "npd", project.id, project.npdNumber,
					"Part " + project.partNumber, editors(project.customerCompanyId, Set.of(CUSTOMER_NPD, CUSTOMER_QUALITY)));
		}
		notifier.send(NotificationKind.NPD_STAGE_CHANGED, "npd", project.id, project.npdNumber, "Now in " + pretty(next),
				everyone(project));
		return view(me, project);
	}

	/** The mandatory activities of the project's current stage that are not finished (or wait for their approval), by name. */
	List<String> openMandatory(NpdProject project) {
		List<NpdActivity> mandatory = activities.findByNpdProjectIdAndPhaseNameAndMandatoryFlagTrue(project.id, project.stage);
		// M2: the gate needs an APPROVED approval of the *right* approvalType linked to the activity, not just any
		// approved approval linked to it - otherwise ERGO could clear a CUSTOMER_SAMPLE_APPROVAL-gated activity by
		// requesting and self-approving an unrelated STAGE_GATE approval instead.
		Map<Long, Set<ApprovalType>> approvedTypesByActivity = approvals
			.findByNpdProjectIdAndApprovalStatusOrderById(project.id, ApprovalStatus.APPROVED).stream()
			.filter(a -> a.linkedActivityId != null)
			.collect(Collectors.groupingBy(a -> a.linkedActivityId, Collectors.mapping(a -> a.approvalType,
					Collectors.toSet())));
		return mandatory.stream()
			.filter(a -> a.status != ActivityStatus.DONE || a.approvalRequiredFlag
					&& !approvedTypesByActivity.getOrDefault(a.id, Set.of()).contains(a.requiredApprovalType))
			.map(a -> a.status != ActivityStatus.DONE ? a.activityName : a.activityName + " (pending approval)")
			.toList();
	}

	// ---------------------------------------------------------------- activities (SRS §13.4.3, §17.4)

	public NpdView addActivity(long projectId, ActivityForm form) {
		CurrentUser me = CurrentUser.get();
		lockProject(projectId);
		NpdProject project = visible(me, projectId);
		require(NpdAction.ADD_ACTIVITY, me, project);
		NpdActivity activity = new NpdActivity();
		activity.npdProjectId = project.id;
		activity.phaseName = stageFor(project, form.stage());
		activity.ownerOrganizationType = form.ownerOrganizationType();
		activity.ownerCompanyId = resolveOwnerCompanyId(me, project, form.ownerOrganizationType(), form.ownerCompanyId());
		apply(activity, form, project);
		activities.save(activity);
		assigned(project, activity);
		return view(me, project);
	}

	public NpdView updateActivity(long activityId, ActivityForm form) {
		CurrentUser me = CurrentUser.get();
		NpdActivity activity = activities.findById(activityId).orElseThrow(() -> ApiException.notFound("Activity"));
		lockProject(activity.npdProjectId);
		NpdProject project = visible(me, activity.npdProjectId);
		require(NpdAction.EDIT_ACTIVITY, me, project, activity);
		CompanyType newOwnerType = form.ownerOrganizationType();
		long newOwnerCompanyId = resolveOwnerCompanyId(me, project, newOwnerType, form.ownerCompanyId());
		boolean ownerChanged = activity.ownerOrganizationType != newOwnerType
				|| !Objects.equals(activity.ownerCompanyId, newOwnerCompanyId);
		if (ownerChanged) {
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
		if (form.stage() != null && form.stage() != activity.phaseName) {
			if (project.planStatus != PlanStatus.AWAITING_PLAN) {
				throw ApiException.conflict("A step can be moved to another stage only while the plan is being built");
			}
			activity.phaseName = stageFor(project, form.stage());
		}
		activity.ownerOrganizationType = newOwnerType;
		activity.ownerCompanyId = newOwnerCompanyId;
		apply(activity, form, project);
		if (ownerChanged) {
			assigned(project, activity);
		}
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
		requireOwnActivityScope(me, project, activity);
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
		requireOwnActivityScope(me, project, activity);
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
		requireOwnActivityScope(me, project, activity);
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
		requireOwnActivityScope(me, project, activity);
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
		requireOwnIssueScope(me, project, issue);
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
		notifier.send(NotificationKind.NPD_APPROVAL_REQUESTED, "npd", project.id, project.npdNumber,
				pretty(approval.approvalType), approverSide(project, approval.approverRole));
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
		notifier.send(NotificationKind.NPD_APPROVAL_DECIDED, "npd", project.id, project.npdNumber,
				pretty(approval.approvalType) + (form.approved() ? ": approved" : ": rejected"),
				Recipients.users(java.util.Set.of(approval.requestedByUserId)));
		return view(me, project);
	}

	// ---------------------------------------------------------------- notifications (SRS §14.9)

	/** The daily reminder scan: planned dates that have passed on activities not yet done. The owner is reminded, and
	 * ERGO's NPD team always. */
	List<com.ergo.connect.common.event.OverdueItem> overdue(java.time.LocalDate today) {
		List<com.ergo.connect.common.event.OverdueItem> items = new java.util.ArrayList<>();
		for (NpdActivity a : activities.findByPlannedDateBeforeAndStatusNot(today, ActivityStatus.DONE)) {
			NpdProject project = projects.findById(a.npdProjectId).orElse(null);
			// A closed project's activities can no longer be edited, so they could never be cleared (review 6e M2). Before the plan is agreed nobody
			// can update a step either, so a planned date alone is no reason to remind.
			// (slice 4 review L4)
			if (project != null && project.stage != NpdStage.CLOSED && executing(project) && NpdRights.of(project).writable()) {
				items.add(new com.ergo.connect.common.event.OverdueItem(NotificationKind.OVERDUE_ACTIVITY, "npd", project.id, project.npdNumber,
						a.activityName + " was planned for " + a.plannedDate,
						ownerSide(project, a).and(Recipients.ergo(Role.ERGO_ADMIN, Role.ERGO_NPD)), "npd-activity:" + a.id));
			}
		}
		return items;
	}

	/** An empty role set (module read-only or hidden) must notify nobody; Recipients.company with no roles means "anyone". */
	static Recipients editors(long companyId, java.util.Set<Role> roles) {
		return roles.isEmpty() ? Recipients.none() : Recipients.company(companyId, roles.toArray(Role[]::new));
	}

	private static Recipients ownerSide(NpdProject project, NpdActivity activity) {
		NpdRights r = NpdRights.of(project);
		return switch (activity.ownerOrganizationType) {
			case ERGO -> activity.responsibleUserId != null ? Recipients.users(java.util.Set.of(activity.responsibleUserId))
					: Recipients.ergo(Role.ERGO_NPD);
			case CUSTOMER -> editors(activity.ownerCompanyId, r.customerEditors);
			case SUPPLIER -> editors(activity.ownerCompanyId, r.supplierEditors);
		};
	}

	/** ERGO's NPD team and each side's NPD editors: everyone who works on this project. */
	private static Recipients everyone(NpdProject project) {
		NpdRights r = NpdRights.of(project);
		return (project.kind == ProjectKind.PRODUCTION ? Recipients.ergo(Role.ERGO_ADMIN, Role.ERGO_NPD, Role.ERGO_OFP)
				: Recipients.ergo(Role.ERGO_ADMIN, Role.ERGO_NPD))
			.and(editors(project.customerCompanyId, r.customerEditors))
			.and(editors(project.supplierCompanyId, r.supplierEditors));
	}

	/** The side the approval's role belongs to: an ERGO role (plus Admin, who may decide ERGO-owned types) or the
	 * project's customer. */
	private static Recipients approverSide(NpdProject project, Role approver) {
		return switch (approver.companyType()) {
			case ERGO -> Recipients.ergo(NpdRights.of(project).owners.toArray(Role[]::new));
			case CUSTOMER -> Recipients.company(project.customerCompanyId, approver);
			case SUPPLIER -> Recipients.company(project.supplierCompanyId, approver);
		};
	}

	/** SRS §14.9 "record assignment": the owner of an activity hears that it is theirs - the named responsible person
	 * for an ERGO-owned one, otherwise the owner company's NPD editors. */
	private void assigned(NpdProject project, NpdActivity activity) {
		notifier.send(NotificationKind.NPD_ACTIVITY_ASSIGNED, "npd", project.id, project.npdNumber, activity.activityName,
				ownerSide(project, activity));
	}

	static String pretty(Enum<?> value) {
		String text = value.name().toLowerCase().replace('_', ' ');
		return Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	// ---------------------------------------------------------------- rules

	/** Record scope. Out-of-scope NPD projects do not exist for the caller (404, not 403). */
	NpdProject visible(CurrentUser me, long id) {
		NpdProject project = projects.findById(id).orElse(null);
		if (!inScope(me, project)) {
			throw ApiException.notFound("NPD project");
		}
		return project;
	}

	/** The boolean half of {@link #visible}, for a non-throwing cross-module probe (see {@code
	 * NpdApiService#partiesVisibleToCaller}). */
	boolean inScope(CurrentUser me, long id) {
		return inScope(me, projects.findById(id).orElse(null));
	}

	boolean inScope(CurrentUser me, NpdProject project) {
		if (project == null || !ModuleSwitch.readable(Module.NPD)) {
			return false;
		}
		NpdRights r = NpdRights.of(project);
		// an OFP project also needs OFP to be visible
		if (project.kind == ProjectKind.PRODUCTION && !ModuleSwitch.readable(Module.OFP)) {
			return false;
		}
		return r.canView(me.role()) && (r.fullViewers.contains(me.role())
				|| me.role() == ERGO_KAM && directory.accountsOf(me.userId()).contains(project.customerCompanyId)
				|| r.customerViewers.contains(me.role()) && me.companyId() == project.customerCompanyId
				// the supplier meets a project only once ERGO has released the plan to it (flow doc step 7)
				|| r.supplierViewers.contains(me.role()) && me.companyId() == project.supplierCompanyId
						&& project.planStatus != PlanStatus.AWAITING_PLAN);
	}

	/** Project-level actions: header edit, stage close, and everything EDITORS alone may originate. */
	private static boolean allowed(NpdAction action, CurrentUser me, NpdProject project) {
		NpdRights r = NpdRights.of(project);
		if (!r.writable()) {
			return false; // every module the project belongs to must accept changes
		}
		boolean editor = r.editors.contains(me.role());
		return switch (action) {
			case UPDATE_PROJECT -> editor;
			// a project moves on only once the plan is agreed (flow doc step 8)
			case CLOSE_STAGE -> r.owners.contains(me.role()) && project.stage != NpdStage.CLOSED
					&& project.planStatus == PlanStatus.AGREED;
			case ADD_ACTIVITY -> editor && project.stage != NpdStage.CLOSED && !planUnderReview(project) && !underValidation(project);
			case MANAGE_PROMPT_CHECKLIST -> editor && project.stage != NpdStage.CLOSED && !planUnderReview(project) && !underValidation(project);
			case RAISE_ISSUE -> editor && project.stage != NpdStage.CLOSED;
			// approvals (sample, quality, stage gate) belong to the work, which starts once the plan is agreed
			case REQUEST_APPROVAL -> editor && project.stage != NpdStage.CLOSED && executing(project);
			// the supplier's NPD team hands the filled-in plan to ERGO NPD
			case SUBMIT_PLAN -> r.planSubmitters.contains(me.role()) && r.supplierEditors.contains(me.role())
					&& project.planStatus.openForSupplier();
			case AGREE_PLAN, SEND_BACK_PLAN -> r.owners.contains(me.role()) && project.planStatus == PlanStatus.SUBMITTED;
			// only ERGO NPD's owning roles release the plan; the KAM who created the project does not
			case ASSIGN_PLAN -> r.owners.contains(me.role())
					&& project.planStatus == PlanStatus.AWAITING_PLAN;
			default -> false; // the rest need a specific activity/issue/approval
		};
	}

	/** Activity-level actions: ERGO's full edit, plus the owning company's own progress/response/blocked-toggle. */
	private static boolean allowed(NpdAction action, CurrentUser me, NpdProject project, NpdActivity activity) {
		if (project.stage == NpdStage.CLOSED || frozenByValidation(project, activity)) {
			return false;
		}
		NpdRights r = NpdRights.of(project);
		if (!r.writable()) {
			return false;
		}
		boolean editor = r.editors.contains(me.role());
		boolean ownCompany = (r.customerEditors.contains(me.role()) || r.supplierEditors.contains(me.role()))
				&& ownsActivity(me, activity);
		return switch (action) {
			case EDIT_ACTIVITY -> editor && !planUnderReview(project);
			// the supplier fills in its own steps while the plan is with it (flow doc step 8)
			case EDIT_PLAN_STEP -> r.supplierEditors.contains(me.role()) && ownCompany
					&& activity.ownerOrganizationType == CompanyType.SUPPLIER && project.planStatus.openForSupplier();
			// work on a step starts only once the plan is agreed
			case MARK_BLOCKED -> executing(project) && !activity.blockedFlag && (editor || ownCompany);
			case UNBLOCK -> executing(project) && activity.blockedFlag && (editor || ownCompany);
			case UPDATE_OWN_ACTIVITY, RESPOND_TO_PROMPT -> executing(project) && (editor || ownCompany);
			default -> false;
		};
	}

	/** The plan is with ERGO NPD for its decision: nobody changes the steps meanwhile. */
	private static boolean planUnderReview(NpdProject project) {
		return project.planStatus == PlanStatus.SUBMITTED;
	}

	/** The final-sample work is with the quality teams for validation: it must not change under their feet. */
	private static boolean underValidation(NpdProject project) {
		return project.stage == NpdStage.FINAL_SAMPLE_READINESS
				&& (project.validationStatus == ValidationStatus.IN_VALIDATION || project.validationStatus == ValidationStatus.VALIDATED);
	}

	/** The steps of Final Sample Readiness stay as they were submitted from the submission until the customer has decided. */
	private static boolean frozenByValidation(NpdProject project, NpdActivity activity) {
		return activity.phaseName == NpdStage.FINAL_SAMPLE_READINESS && (project.validationStatus == ValidationStatus.IN_VALIDATION
				|| project.validationStatus == ValidationStatus.VALIDATED || project.validationStatus == ValidationStatus.IN_CUSTOMER_REVIEW);
	}

	/** Execution has started: the plan is agreed (projects from before the plan flow count as agreed). */
	private static boolean executing(NpdProject project) {
		return project.planStatus == PlanStatus.AGREED;
	}

	private static boolean ownsActivity(CurrentUser me, NpdActivity activity) {
		return activity.ownerOrganizationType == me.companyType()
				&& Objects.equals(activity.ownerCompanyId, me.companyId());
	}

	/** A customer/supplier caller may only reach an activity their own company owns; another company's activity
	 * does not exist for them (404), the same record-scope pattern used at the project level ({@link #visible}).
	 * ERGO's EDITORS reach any activity regardless (their broader rights are enforced by require() instead). */
	private static void requireOwnActivityScope(CurrentUser me, NpdProject project, NpdActivity activity) {
		if (!NpdRights.of(project).editors.contains(me.role()) && !ownsActivity(me, activity)) {
			throw ApiException.notFound("Activity");
		}
	}

	private static boolean ownsIssue(CurrentUser me, NpdIssueRisk issue) {
		return issue.ownerOrganizationType == me.companyType() && Objects.equals(issue.ownerCompanyId, me.companyId());
	}

	/** A customer/supplier caller may only reach an issue/risk its own company owns; another company's issue does
	 * not exist for them (404), the exact same record-scope pattern as {@link #requireOwnActivityScope}. ERGO's
	 * EDITORS reach any issue regardless. */
	private static void requireOwnIssueScope(CurrentUser me, NpdProject project, NpdIssueRisk issue) {
		if (!NpdRights.of(project).editors.contains(me.role()) && !ownsIssue(me, issue)) {
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
		NpdRights r = NpdRights.of(project);
		if (!r.writable()) {
			return false;
		}
		boolean editor = r.editors.contains(me.role());
		boolean ownCompany = (r.customerEditors.contains(me.role()) || r.supplierEditors.contains(me.role()))
				&& ownsIssue(me, issue);
		return editor || ownCompany;
	}

	/** Splits "this role could never decide this approval type" (404 — a customer/supplier or the wrong ERGO role
	 * must not be able to tell a hidden approval type exists on their own project just from getting a 409 instead
	 * of a 404, security review finding L2) from "the row just isn't decidable right now" (409: already decided, or
	 * the project itself is CLOSED — the latter matching every other write in this module). */
	private static void requireCanDecideApproval(CurrentUser me, NpdProject project, NpdApproval approval) {
		if (!mayDecide(me, project, approval)) {
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
	 * {@link #requireCanDecideApproval}, which tells a not-my-type 404 apart from an already-decided 409): the
	 * final-approval role of the approval's own side (see {@link #mayDecide}); only while still PENDING and the project isn't
	 * CLOSED. */
	private static boolean allowed(NpdAction action, CurrentUser me, NpdProject project, NpdApproval approval) {
		return action == NpdAction.DECIDE_APPROVAL && project.stage != NpdStage.CLOSED
				&& approval.approvalStatus == ApprovalStatus.PENDING
				&& NpdRights.of(project).writable() && mayDecide(me, project, approval) && me.userId() != approval.requestedByUserId;
	}

	/** The role sheet: only the final-approval role of the approval's own side decides it - ERGO's "O" roles an
	 * ERGO-owned type, the customer's "CO" role a customer type. Which side is the type's own, not the named role. */
	private static boolean mayDecide(CurrentUser me, NpdProject project, NpdApproval approval) {
		return NpdRights.of(project).approvers.contains(me.role())
				&& me.companyType() == approval.approvalType.approverRole().companyType();
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

	/** A plan step may sit in any stage from the project's current one on (flow doc step 7: the plan covers the whole
	 * project); no stage given means the current one. */
	private static NpdStage stageFor(NpdProject project, NpdStage requested) {
		if (requested == null) {
			return project.stage;
		}
		if (requested.compareTo(project.stage) < 0 || requested == NpdStage.CLOSED) {
			throw ApiException.unprocessable("A step can only be planned in the current stage or a later one");
		}
		if (requested.skippedBy(project.kind)) {
			throw ApiException.unprocessable("An OFP project does not go through this stage");
		}
		return requested;
	}

	/** Shared by add and edit. Dates/status/mandatory flow straight from the form: the caller already validated the
	 * shape; only cross-references (dependency, responsible user) and the BLOCKED guard need checking here. Owner
	 * fields are set by the caller (resolveOwnerCompanyId), not here. */
	private void apply(NpdActivity activity, ActivityForm form, NpdProject project) {
		long projectId = project.id;
		// the progress endpoints are closed until the plan is agreed; this form must not be a second door to the same change
		if (!executing(project) && (form.status() != ActivityStatus.NOT_STARTED || form.actualDate() != null)) {
			throw ApiException.unprocessable("A step can be started or finished only once the plan is agreed");
		}
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

	NpdView view(CurrentUser me, NpdProject p) {
		NpdRights rights = NpdRights.of(p);
		boolean isCustomer = rights.customerViewers.contains(me.role());
		boolean isSupplier = rights.supplierViewers.contains(me.role());
		// RfqApi's own record scope is per-audience (forErgo/forCustomer/forSupplier): the caller's own visible()
		// pass already proved this project (and so this RFQ) is theirs, so each is safe to call here.
		// the source RFQ's number is RFQ-module data: shown only while that module is not hidden
		// the OFP teams have no RFQ access of their own: they get no RFQ number rather than a refusal
		boolean rfqVisible = ModuleSwitch.readable(Module.RFQ) && (!(isCustomer || isSupplier) || ModuleAccess.canView(me.role(), Module.RFQ));
		String rfqNumber = !rfqVisible ? null
				: isCustomer ? rfqApi.forCustomer(p.sourceRfqId).number()
				: isSupplier ? rfqApi.forSupplierItem(p.sourceRfqItemId).rfqNumber() : rfqApi.numberOf(p.sourceRfqId);
		Map<Long, UserRef> owner = directory.users(ids(List.of(p), x -> x.ownerUserId, x -> x.qualityOwnerUserId));
		SamplePo samplePo = p.samplePoId == null ? null : samplePos.findById(p.samplePoId).orElse(null);
		Long samplePoId = samplePo == null ? null : samplePo.id;
		String samplePoNumber = samplePo == null ? null : samplePo.samplePoNumber;

		List<NpdStageHistoryEntry> history = stageHistory.findByNpdProjectIdOrderById(p.id);
		Map<Long, UserRef> historyUsers = directory.users(ids(history, h -> h.changedByUserId));
		List<StageHistoryView> historyViews = history.stream()
			.map(h -> new StageHistoryView(h.stageName, h.previousStageName, userName(historyUsers, h.changedByUserId),
					h.changedAt, h.remarks))
			.toList();
		// H1: whatever ERGO typed as a stage-move comment (remarks) could name the other party or a price — the
		// customer/supplier variant drops it; changedBy stays (every stage move is an ERGO-only action).
		// A stage move can now also be a customer's or the supplier's rejection (quality validation), so the name of whoever
		// moved it is shown only if that is ERGO or the caller's own company, never the other external party's people.
		// The customer also never sees a move back that the supplier-side validation caused (only its own rejection).
		Map<Long, CompanyRef> historyCompanies = directory.companies(ids(historyUsers.values(), UserRef::companyId));
		List<ExternalStageHistoryView> externalHistoryViews = history.stream()
			.filter(h -> !isCustomer || h.previousStageName == null || h.stageName.compareTo(h.previousStageName) >= 0
					|| h.previousStageName == NpdStage.CUSTOMER_FEEDBACK)
			.map(h -> new ExternalStageHistoryView(h.stageName, h.previousStageName,
					mayNameChanger(me, historyUsers.get(h.changedByUserId), historyCompanies) ? userName(historyUsers, h.changedByUserId) : null,
					h.changedAt))
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
		ProgressView progress = progress(p, acts);
		List<NpdValidationDecision> decisionRows = validationDecisions.findByNpdProjectIdOrderById(p.id);
		Set<ValidationParty> decidedNow = decisionRows.stream().filter(d -> d.roundNo == p.validationRound)
			.map(d -> d.party).collect(Collectors.toCollection(() -> EnumSet.noneOf(ValidationParty.class)));
		ValidationView validation = validationView(me, p, decisionRows);
		actions = new java.util.LinkedHashSet<>(actions);
		Long submitter = decisionRows.stream().filter(d -> d.roundNo == p.validationRound && d.party == ValidationParty.SUPPLIER_INTERNAL)
			.map(d -> d.decidedByUserId).findFirst().orElse(null);
		actions.addAll(NpdValidationService.actions(me, p, decidedNow, submitter));
		// the button only where closing can work; closeStage itself still checks the inspection gate first, then this one
		if (p.stage == NpdStage.FINAL_SAMPLE_READINESS && p.validationStatus != ValidationStatus.VALIDATED
				|| p.stage == NpdStage.CUSTOMER_FEEDBACK && p.validationStatus != ValidationStatus.CUSTOMER_APPROVED) {
			actions.remove(NpdAction.CLOSE_STAGE);
		}

		List<NpdPlanHistoryEntry> planHistoryRows = isCustomer ? List.of() : planHistory.findByNpdProjectIdOrderById(p.id);

		if (isSupplier) {
			return new SupplierNpdView("SUPPLIER", p.id, p.npdNumber, p.kind, p.sourceRfqId, rfqNumber, p.partNumber, p.revision,
					p.description, p.quoteReference, p.stage, p.targetSampleDate, p.actualSampleSubmissionDate, delay,
					activityViews, issueViews, externalHistoryViews, p.planStatus, planViews(planHistoryRows, true), progress, validation, actions,
					p.createdAt, p.updatedAt);
		}
		if (isCustomer) {
			CompanyRef customer = directory.company(p.customerCompanyId).orElseThrow();
			return new CustomerNpdView("CUSTOMER", p.id, p.npdNumber, p.kind, p.sourceRfqId, rfqNumber,
					new Named(customer.id(), customer.code(), customer.name()), samplePoId, samplePoNumber, p.partNumber, p.revision,
					p.description, p.stage, p.targetSampleDate, p.actualSampleSubmissionDate, delay, activityViews,
					issueViews, approvalViews, externalHistoryViews, progress, validation, actions, p.createdAt, p.updatedAt);
		}
		if (me.companyType() != CompanyType.ERGO) {
			// the ERGO view carries both companies: only ever for an ERGO caller, whatever the role sets say
			throw ApiException.forbidden("You do not have access to this NPD project");
		}
		CompanyRef customer = directory.company(p.customerCompanyId).orElseThrow();
		CompanyRef supplier = directory.company(p.supplierCompanyId).orElseThrow();
		return new ErgoNpdView("ERGO", p.id, p.npdNumber, p.kind, p.sourceRfqId, rfqNumber,
				new Named(customer.id(), customer.code(), customer.name()),
				new Named(supplier.id(), supplier.code(), supplier.name()), p.partNumber, p.revision, p.description,
				p.quoteReference, p.stage, p.targetSampleDate, p.actualSampleSubmissionDate, delay, p.ownerUserId,
				userName(owner, p.ownerUserId), p.qualityOwnerUserId, userName(owner, p.qualityOwnerUserId), p.riskLevel,
				p.planStatus, planViews(planHistoryRows, false), samplePoId, samplePoNumber, activityViews, issueViews,
				approvalViews, historyViews, progress, validation, actions, p.createdAt, p.updatedAt);
	}

	/** The plan's trail. ERGO sees every comment. The supplier sees ERGO NPD's send-back reason (written for it) and its own
	 * submit note, never what ERGO typed when assigning or agreeing the plan: that is internal and could name the customer
	 * or a price. The customer gets no plan at all (the caller passes no rows for it). */
	private List<PlanHistoryView> planViews(List<NpdPlanHistoryEntry> rows, boolean forSupplier) {
		Map<Long, UserRef> users = directory.users(ids(rows, r -> r.byUserId));
		return rows.stream()
			.map(r -> new PlanHistoryView(r.event.name(),
					forSupplier && r.event != NpdPlanHistoryEntry.Event.SENT_BACK && r.event != NpdPlanHistoryEntry.Event.SUBMITTED ? null : r.comment,
					userName(users, r.byUserId), r.createdAt))
			.toList();
	}

	/** Share of the caller's visible activities that are done, in total and per stage. A stage before the project's
	 * current one is completed, whatever its activities say; stages without activities still appear. */
	private static ProgressView progress(NpdProject p, List<NpdActivity> visibleActivities) {
		List<StageProgress> stages = new ArrayList<>();
		for (NpdStage stage : NpdStage.values()) {
			if (stage == NpdStage.PENDING_CONVERSION || stage == NpdStage.CLOSED || stage.skippedBy(p.kind)) {
				continue;
			}
			List<NpdActivity> inStage = visibleActivities.stream().filter(a -> a.phaseName == stage).toList();
			int done = (int) inStage.stream().filter(a -> a.status == ActivityStatus.DONE).count();
			StageState state = stage.compareTo(p.stage) < 0 ? StageState.COMPLETED
					: stage == p.stage ? StageState.CURRENT : StageState.UPCOMING;
			stages.add(new StageProgress(stage, done, inStage.size(), state));
		}
		int total = visibleActivities.size();
		int done = (int) visibleActivities.stream().filter(a -> a.status == ActivityStatus.DONE).count();
		return new ProgressView(total == 0 ? 0 : done * 100 / total, done, total, stages);
	}

	private static boolean mayNameChanger(CurrentUser me, UserRef changer, Map<Long, CompanyRef> companies) {
		if (changer == null) {
			return false;
		}
		CompanyRef company = companies.get(changer.companyId());
		return changer.companyId() == me.companyId() || company != null && company.type() == CompanyType.ERGO;
	}

	/** The quality history for one audience. ERGO sees everything. The supplier sees its own people's decisions and ERGO
	 * Quality's rejection reasons (written for the rework), but of the customer only the outcome: never its words or
	 * people. The customer sees only its own decisions and a coarse status that does not reveal the supplier-side
	 * steps. */
	private ValidationView validationView(CurrentUser me, NpdProject p, List<NpdValidationDecision> rows) {
		NpdRights rights = NpdRights.of(p);
		boolean customer = rights.customerViewers.contains(me.role());
		boolean supplier = rights.supplierViewers.contains(me.role());
		Map<Long, UserRef> users = directory.users(ids(rows, r -> r.decidedByUserId));
		List<ValidationDecisionView> views = new ArrayList<>();
		Set<Integer> emittedCustomerRounds = new java.util.HashSet<>();
		for (NpdValidationDecision d : rows) {
			boolean customerParty = d.party.side() == CompanyType.CUSTOMER;
			if (customer && !customerParty) {
				continue;
			}
			String party = d.party.name();
			String comment = d.comment;
			String by = userName(users, d.decidedByUserId);
			if (supplier && customerParty) {
				if (!emittedCustomerRounds.add(d.roundNo)) {
					continue;
				}
				List<NpdValidationDecision> customerRound = rows.stream()
					.filter(x -> x.roundNo == d.roundNo && x.party.side() == CompanyType.CUSTOMER)
					.toList();
				boolean rejected = customerRound.stream().anyMatch(x -> x.decision == NpdValidationDecision.Decision.REJECTED);
				if (!rejected && customerRound.size() < ValidationParty.CUSTOMER_REVIEWERS.size()) {
					emittedCustomerRounds.remove(d.roundNo); // still waiting for the second customer team
					continue;
				}
				views.add(new ValidationDecisionView(d.roundNo, "CUSTOMER", rejected ? "REJECTED" : "APPROVED", null, null,
						customerRound.getLast().createdAt, rejected ? d.returnStage : null));
				continue;
			}
			else if (supplier && d.party == ValidationParty.ERGO_QUALITY && d.decision == NpdValidationDecision.Decision.APPROVED) {
				comment = null; // an approval note is internal to ERGO
			}
			views.add(new ValidationDecisionView(d.roundNo, party, d.decision.name(), comment, by, d.createdAt, d.returnStage));
		}
		ValidationStatus status = p.validationStatus;
		if (customer && (status == ValidationStatus.IN_VALIDATION || status == ValidationStatus.VALIDATED)) {
			status = ValidationStatus.NOT_STARTED;
		}
		// the customer's round counts the customer's own rejections: the supplier-side rounds are not its business
		int round = customer ? 1 + (int) rows.stream().filter(d -> d.party.side() == CompanyType.CUSTOMER
				&& d.decision == NpdValidationDecision.Decision.REJECTED).count() : p.validationRound;
		return new ValidationView(status, round, views);
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
		return ModuleSwitch.readable(Module.RFQ) ? rfqApi.numberOf(rfqId) : null;
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
