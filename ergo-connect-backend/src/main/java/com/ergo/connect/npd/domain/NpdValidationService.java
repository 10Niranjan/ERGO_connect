package com.ergo.connect.npd.domain;

import static com.ergo.connect.common.security.Role.CUSTOMER_NPD;
import static com.ergo.connect.common.security.Role.CUSTOMER_QUALITY;
import static com.ergo.connect.common.security.Role.SUPPLIER_NPD;
import static com.ergo.connect.common.security.Role.SUPPLIER_QUALITY;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
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
import com.ergo.connect.npd.domain.NpdDtos.NpdAction;
import com.ergo.connect.npd.domain.NpdDtos.NpdView;
import com.ergo.connect.npd.domain.NpdDtos.ValidationDecisionForm;

/**
 * Flow doc steps 9-10, the quality validation and the customer's final approval:
 * <ol>
 * <li>With Final Sample Readiness reached and its mandatory steps done, <b>Supplier Quality approves internally</b>,
 * which submits the project to ERGO Quality and Supplier Quality ({@link #submit}).</li>
 * <li><b>ERGO Quality and Supplier Quality must both approve</b> ({@link #decide}). A rejection by either sends the
 * project back to a development stage for rework, with the failure comments in the history.</li>
 * <li>Closing Final Sample Readiness (NpdService) submits the project to <b>Customer NPD and Customer Quality</b>, who
 * must <b>both</b> approve. A rejection by either sends it back to Final Sample Readiness for corrective action and
 * resubmission by Supplier Quality.</li>
 * </ol>
 * Every decision is a row in {@code npd_validation_decisions} (append-only, one per party per round). The role
 * decides the party; nobody decides for someone else, nobody decides twice in a round. The status moves only here
 * (and at the Final Sample Readiness close), under the project row lock.
 */
@Service
@Transactional
public class NpdValidationService {

	private final NpdService npd;

	private final NpdProjectRepository projects;

	private final NpdActivityRepository activities;

	private final NpdStageHistoryRepository stageHistory;

	private final NpdValidationDecisionRepository decisions;

	private final AuditLogService audit;

	private final Notifier notifier;

	NpdValidationService(NpdService npd, NpdProjectRepository projects, NpdActivityRepository activities,
			NpdStageHistoryRepository stageHistory, NpdValidationDecisionRepository decisions, AuditLogService audit,
			Notifier notifier) {
		this.npd = npd;
		this.projects = projects;
		this.activities = activities;
		this.stageHistory = stageHistory;
		this.decisions = decisions;
		this.audit = audit;
		this.notifier = notifier;
	}

	/** Supplier Quality's internal approval, which submits the project to ERGO Quality and Supplier Quality. */
	public NpdView submit(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		npd.lockProject(id);
		NpdProject project = npd.visible(me, id);
		if (!actions(me, project, Set.of(), null).contains(NpdAction.SUBMIT_VALIDATION)) {
			throw ApiException.conflict("This action is not available for this NPD project right now");
		}
		List<String> open = npd.openMandatory(project);
		if (!open.isEmpty()) {
			throw ApiException.conflict("Complete these mandatory activities before submitting to validation: " + String.join(", ", open));
		}
		String note = text(comment);
		if (note != null) {
			audit.reason(note);
		}
		record(me, project, ValidationParty.SUPPLIER_INTERNAL, NpdValidationDecision.Decision.APPROVED, note, null);
		project.validationStatus = ValidationStatus.IN_VALIDATION;
		notifier.send(NotificationKind.NPD_VALIDATION_REQUESTED, "npd", project.id, project.npdNumber,
				"Part " + project.partNumber, ergoQualityTeam(project).and(NpdService.editors(project.supplierCompanyId, Set.of(SUPPLIER_QUALITY))));
		return npd.view(me, project);
	}

	/** One party's approval or rejection in the current round. */
	public NpdView decide(long id, ValidationDecisionForm form) {
		CurrentUser me = CurrentUser.get();
		npd.lockProject(id);
		NpdProject project = npd.visible(me, id);
		ValidationParty party = ValidationParty.ofRole(me.role());
		if (party == null) {
			throw ApiException.forbidden("Your role takes no part in the quality validation");
		}
		Set<ValidationParty> decided = decidedThisRound(project);
		Long submitter = decisions.findByNpdProjectIdAndRoundNoOrderById(project.id, project.validationRound).stream()
			.filter(d -> d.party == ValidationParty.SUPPLIER_INTERNAL).map(d -> d.decidedByUserId).findFirst().orElse(null);
		if (party == ValidationParty.SUPPLIER_QUALITY && submitter != null && submitter == me.userId()) {
			throw ApiException.conflict("You submitted this round yourself: a colleague in Supplier Quality has to validate it");
		}
		if (!actions(me, project, decided, submitter).contains(NpdAction.DECIDE_VALIDATION)) {
			throw ApiException.conflict("This action is not available for this NPD project right now");
		}
		boolean customerPhase = project.validationStatus == ValidationStatus.IN_CUSTOMER_REVIEW;
		String comment = text(form.comment());
		if (form.approved()) {
			if (comment != null) {
				audit.reason(comment);
			}
			record(me, project, party, NpdValidationDecision.Decision.APPROVED, comment, null);
			Set<ValidationParty> now = EnumSet.copyOf(decided);
			now.add(party);
			if (customerPhase && now.containsAll(ValidationParty.CUSTOMER_REVIEWERS)) {
				project.validationStatus = ValidationStatus.CUSTOMER_APPROVED;
				notifier.send(NotificationKind.NPD_CUSTOMER_APPROVED, "npd", project.id, project.npdNumber,
						"Part " + project.partNumber, ergoTeam(project));
			}
			else if (!customerPhase && now.containsAll(ValidationParty.VALIDATORS)) {
				project.validationStatus = ValidationStatus.VALIDATED;
				notifier.send(NotificationKind.NPD_VALIDATION_COMPLETE, "npd", project.id, project.npdNumber,
						"Part " + project.partNumber, ergoTeam(project));
			}
			return npd.view(me, project);
		}

		String reason = text(comment);
		if (reason == null) {
			throw ApiException.unprocessable("A reason is required to reject");
		}
		audit.reason(reason);
		NpdStage target;
		if (customerPhase) {
			target = NpdStage.FINAL_SAMPLE_READINESS; // back to the quality stage, for corrective action and resubmission
		}
		else {
			target = form.returnStage();
			if (target == null || target.compareTo(NpdStage.PLANNING) < 0 || target.compareTo(NpdStage.FINAL_SAMPLE_READINESS) >= 0) {
				throw ApiException.unprocessable("Say which development stage the project goes back to (Planning to Trial Execution)");
			}
		}
		record(me, project, party, NpdValidationDecision.Decision.REJECTED, reason, target);
		sendBack(me, project, target, reason, !customerPhase);
		return npd.view(me, project);
	}

	/** The project goes back to {@code target}; the next submission starts a new round. {@code reopen}: the finished
	 * steps of the development stage it returns to are put back in progress, so the corrective work has to be done
	 * again before the stage can close. */
	private void sendBack(CurrentUser me, NpdProject project, NpdStage target, String reason, boolean reopen) {
		NpdStage from = project.stage;
		if (from != target) {
			project.stage = target;
			NpdStageHistoryEntry history = new NpdStageHistoryEntry();
			history.npdProjectId = project.id;
			history.stageName = target;
			history.previousStageName = from;
			history.changedByUserId = me.userId();
			history.remarks = "Sent back by quality validation: " + reason;
			stageHistory.save(history);
		}
		if (reopen) {
			for (NpdActivity activity : activities.findByNpdProjectIdAndPhaseName(project.id, target)) {
				if (activity.status == ActivityStatus.DONE && activity.ownerOrganizationType != CompanyType.CUSTOMER) {
					activity.status = ActivityStatus.IN_PROGRESS;
					activity.actualDate = null;
				}
			}
		}
		project.validationStatus = ValidationStatus.NOT_STARTED;
		project.validationRound++;
		notifier.send(NotificationKind.NPD_VALIDATION_RETURNED, "npd", project.id, project.npdNumber,
				"Now in " + NpdService.pretty(target),
				ergoTeam(project).and(NpdService.editors(project.supplierCompanyId, Set.of(SUPPLIER_NPD, SUPPLIER_QUALITY))));
	}

	private void record(CurrentUser me, NpdProject project, ValidationParty party, NpdValidationDecision.Decision decision,
			String comment, NpdStage returnStage) {
		NpdValidationDecision row = new NpdValidationDecision();
		row.npdProjectId = project.id;
		row.roundNo = project.validationRound;
		row.party = party;
		row.decision = decision;
		row.comment = comment;
		row.returnStage = returnStage;
		row.decidedByUserId = me.userId();
		try {
			decisions.save(row);
		}
		catch (DataIntegrityViolationException e) {
			throw ApiException.conflict("This decision has already been recorded"); // race defense (DB UNIQUE)
		}
	}

	private Set<ValidationParty> decidedThisRound(NpdProject project) {
		Set<ValidationParty> decided = EnumSet.noneOf(ValidationParty.class);
		for (NpdValidationDecision d : decisions.findByNpdProjectIdAndRoundNoOrderById(project.id, project.validationRound)) {
			decided.add(d.party);
		}
		return decided;
	}

	/**
	 * The loop's buttons for the caller. Submitting: Supplier Quality, with the plan agreed and the project at Final
	 * Sample Readiness, nothing submitted yet. Deciding: the caller's own party, in the phase that party belongs to,
	 * once per round. Both need the NPD module to accept changes and the caller's role to act on it.
	 */
	static Set<NpdAction> actions(CurrentUser me, NpdProject project, Set<ValidationParty> decidedThisRound, Long submitterThisRound) {
		Set<NpdAction> actions = EnumSet.noneOf(NpdAction.class);
		if (project.planStatus != PlanStatus.AGREED || project.stage == NpdStage.CLOSED || !ModuleSwitch.writable(Module.NPD)
				|| !ModuleAccess.actors(Module.NPD, me.companyType()).contains(me.role())) {
			return actions;
		}
		if (me.role() == SUPPLIER_QUALITY && project.stage == NpdStage.FINAL_SAMPLE_READINESS
				&& project.validationStatus == ValidationStatus.NOT_STARTED) {
			actions.add(NpdAction.SUBMIT_VALIDATION);
		}
		ValidationParty party = ValidationParty.ofRole(me.role());
		if (party != null && !decidedThisRound.contains(party)
				&& !(party == ValidationParty.SUPPLIER_QUALITY && submitterThisRound != null && me.userId() == submitterThisRound)
				&& (project.validationStatus == ValidationStatus.IN_VALIDATION && ValidationParty.VALIDATORS.contains(party)
						|| project.validationStatus == ValidationStatus.IN_CUSTOMER_REVIEW
								&& ValidationParty.CUSTOMER_REVIEWERS.contains(party))) {
			actions.add(NpdAction.DECIDE_VALIDATION);
		}
		return actions;
	}

	/** ERGO's NPD owner and quality owner (the two the KAM assigned), or ERGO's owning roles when neither is set. */
	private static Recipients ergoTeam(NpdProject project) {
		List<Long> users = new ArrayList<>();
		if (project.ownerUserId != null) {
			users.add(project.ownerUserId);
		}
		if (project.qualityOwnerUserId != null) {
			users.add(project.qualityOwnerUserId);
		}
		return users.isEmpty() ? Recipients.ergo(Role.ERGO_NPD) : Recipients.users(users);
	}

	/** ERGO Quality's side of a submission: the assigned quality owner, or every ERGO Quality user when none is set. */
	private static Recipients ergoQualityTeam(NpdProject project) {
		return project.qualityOwnerUserId != null ? Recipients.users(List.of(project.qualityOwnerUserId))
				: Recipients.ergo(Role.ERGO_QUALITY);
	}

	private static String text(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

}
