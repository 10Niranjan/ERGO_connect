package com.ergo.connect.npd.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * NPD request and response shapes. Per-audience views (ErgoNpdView/CustomerNpdView/SupplierNpdView) follow the same
 * sealed-interface pattern as RfqDtos.RfqView/QuoteDtos: no field for the other side's company ever exists on the
 * customer/supplier types, and no query for it is ever made building them.
 */
public final class NpdDtos {

	private NpdDtos() {
	}

	/** Buttons the caller may use now, computed by the same rules that enforce them. They appear on a project, one of
	 * its activities, one of its issues/risks or one of its approvals. */
	public enum NpdAction {
		ASSIGN_PLAN, UPDATE_PROJECT, CLOSE_STAGE, ADD_ACTIVITY, EDIT_ACTIVITY, MARK_BLOCKED, UNBLOCK,
		UPDATE_OWN_ACTIVITY, MANAGE_PROMPT_CHECKLIST, RESPOND_TO_PROMPT, RAISE_ISSUE, UPDATE_ISSUE, REQUEST_APPROVAL,
		DECIDE_APPROVAL
	}

	public record Named(long id, String code, String name) {
	}

	/** Optional comment/reason, reused across actions; the service decides whether it is required. */
	public record CommentForm(@Size(max = 2000) String comment) {
	}

	/** SRS §12.8.1 NPD project list columns. ERGO-only: the other company is never in scope here (ERGO sees both). */
	public record NpdListRow(long id, String npdNumber, String rfqNumber, String customerName, String supplierName,
			String partNumber, NpdStage stage, LocalDate targetSampleDate, Long delayDays, String owner,
			RiskLevel riskLevel, PlanStatus planStatus) {
	}

	/** SRS §12.15's customer "NPD" / supplier "NPD Tasks" nav entry: the caller's own company's projects, with no
	 * field for the other company at all (safe for both audiences, unlike NpdListRow). Feeds the "reach a project by
	 * id" detail view — every other 3b field lives there instead of being duplicated onto this row. */
	public record NpdOwnProjectRow(long id, String npdNumber, String partNumber, String revision, NpdStage stage,
			LocalDate targetSampleDate, Long delayDays) {
	}

	/** Owner reassignment, risk level and sample dates — ERGO Admin/NPD project-header fields, edited outside the
	 * activity list. A full replace of these four fields, not a patch: resend the current value for anything you
	 * don't want to change (a field left off, e.g. via the raw API, clears it, it is not left alone). */
	public record NpdProjectForm(Long ownerUserId, RiskLevel riskLevel, LocalDate targetSampleDate,
			LocalDate actualSampleSubmissionDate) {
	}

	/** Create and edit an activity, ERGO Admin/NPD only. {@code ownerOrganizationType}/{@code ownerCompanyId} say
	 * who the activity is assigned to: ERGO ignores whatever company id is sent (always the caller's own ERGO
	 * company); for CUSTOMER/SUPPLIER the id must equal this project's own customer/supplier company (rejected
	 * otherwise) — see NpdService.resolveOwnerCompanyId. {@code status} may not be BLOCKED here (only
	 * markBlocked/unblock touch blocked_flag). {@code requiredApprovalType} says which approval type the Close
	 * Stage gate demands for this activity (SRS §14.5 "quality gate... where applicable"); the service requires it
	 * non-null when {@code approvalRequired} is true and forces it to {@code null} when false, regardless of what's
	 * sent — never trust the client's pairing of the two fields. {@code stage} is optional: the stage whose Close Stage this
	 * activity gates (the current one when omitted; a later one when ERGO NPD plans ahead, flow doc step 7). */

	public record ActivityForm(@NotBlank @Size(max = 200) String activityName,
			@Size(max = 4000) String activityDescription, @NotNull CompanyType ownerOrganizationType,
			Long ownerCompanyId, Long responsibleUserId, LocalDate plannedDate, LocalDate baselineDate,
			LocalDate actualDate, Long dependencyActivityId, @NotNull ActivityStatus status,
			@NotNull Boolean mandatory, @NotNull Boolean riskFlag, @NotNull Boolean issueFlag,
			@NotNull Boolean approvalRequired, ApprovalType requiredApprovalType,
			NpdStage stage) {
	}

	/** The owning company's own progress update on an activity it owns (SRS §17.4: "supplier can update only
	 * assigned activities"), never the name, owner, mandatory/approval flags or dependency — those stay ERGO's
	 * classification, set only through ActivityForm. {@code status} may not be BLOCKED here either (same rule). */
	public record ActivityProgressForm(@NotNull ActivityStatus status, LocalDate actualDate,
			@NotNull Boolean issueFlag, @NotNull Boolean riskFlag, @Size(max = 2000) String remarks) {
	}

	/** Author (create/edit) a checklist question, ERGO Admin/NPD only; {@code activityId} is required on create and
	 * ignored on edit (the checklist's own activity never moves). */
	public record PromptChecklistForm(Long activityId, @NotBlank @Size(max = 2000) String questionText,
			@NotNull PromptResponseType responseType, @NotNull Boolean mandatory, @NotNull @Positive Integer sequenceNo) {
	}

	public record PromptChecklistView(long id, String questionText, PromptResponseType responseType,
			boolean mandatoryFlag, int sequenceNo, String responseValue, String responseRemarks, String respondedBy,
			Instant respondedAt) {
	}

	/** Answer one question; upserts the one current answer (SRS §13.4.5). */
	public record PromptResponseForm(@NotBlank @Size(max = 4000) String responseValue,
			@Size(max = 2000) String remarks) {
	}

	/** Raise an issue/risk, ERGO Admin/NPD only. {@code ownerCompanyId} is required unless {@code ownerOrganizationType}
	 * is ERGO (then it is server-derived, matching ActivityForm's own ownership rule); {@code linkedActivityId} must
	 * belong to the same project. */
	public record IssueRiskForm(@NotNull IssueRiskType type, @NotBlank @Size(max = 200) String title,
			@Size(max = 4000) String description, RiskLevel severity, @NotNull CompanyType ownerOrganizationType,
			Long ownerCompanyId, LocalDate targetDate, Long linkedActivityId) {
	}

	/** The narrower status-only update the owning company (or ERGO) may make; never the classification fields.
	 * {@code comment} is optional, except that the service requires it (audited as the reason) when this reopens a
	 * closed issue (previous status CLOSED, new status not) — CLAUDE.md's reopen-needs-a-reason non-negotiable. */
	public record IssueStatusForm(@NotNull IssueRiskStatus status, @Size(max = 2000) String comment) {
	}

	public record IssueRiskView(long id, IssueRiskType type, String title, String description, RiskLevel severity,
			CompanyType ownerOrganizationType, String ownerCompanyName, Long linkedActivityId, LocalDate targetDate,
			IssueRiskStatus status, LocalDate closureDate, Set<NpdAction> actions) {
	}

	/** Request an approval, ERGO Admin/NPD only; {@code approverRole} is computed server-side from
	 * {@code approvalType} (ApprovalType#approverRole), never accepted from the client. */
	public record ApprovalForm(@NotNull ApprovalType approvalType, Long linkedActivityId) {
	}

	/** Approve or reject a pending approval; {@code comments} is required on reject, optional on approve (the same
	 * required-on-reject pattern as 2c's Not-Selected reason). */
	public record ApprovalDecisionForm(@NotNull Boolean approved, @Size(max = 2000) String comments) {
	}

	public record ApprovalView(long id, ApprovalType approvalType, NpdStage stageName, Long linkedActivityId,
			String requestedBy, Instant requestedAt, Role approverRole, ApprovalStatus approvalStatus,
			String approvedBy, Instant approvedAt, String approvalComments, Set<NpdAction> actions) {
	}

	public record ActivityView(long id, NpdStage phaseName, String activityName, String activityDescription,
			CompanyType ownerOrganizationType, String ownerCompanyName, Long responsibleUserId, String responsibleUser,
			LocalDate plannedDate, LocalDate baselineDate, LocalDate actualDate, Long dependencyActivityId,
			ActivityStatus status, boolean blockedFlag, boolean riskFlag, boolean issueFlag, boolean mandatoryFlag,
			boolean approvalRequiredFlag, ApprovalType requiredApprovalType, String remarks,
			List<PromptChecklistView> promptChecklists, Set<NpdAction> actions) {
	}

	/** SRS §13.4.2: one row per stage move, oldest first. {@code previousStage} is null for the very first row.
	 * ERGO-only ({@link ErgoNpdView}): {@code remarks} is whatever free text ERGO typed on convert/closeStage, which
	 * could name the other party or a price — see {@link ExternalStageHistoryView} for the customer/supplier
	 * variant. */
	public record StageHistoryView(NpdStage stage, NpdStage previousStage, String changedBy, Instant changedAt,
			String remarks) {
	}

	/** The customer/supplier-safe variant of {@link StageHistoryView}: no {@code remarks}. {@code changedBy} is
	 * still safe to show since every stage transition is an ERGO-only action (convert/closeStage), so it is always
	 * an ERGO user's name, never the other external party's. */
	public record ExternalStageHistoryView(NpdStage stage, NpdStage previousStage, String changedBy,
			Instant changedAt) {
	}

	/** Per-audience detail view (the "audience" discriminant follows RfqDtos.RfqView's own pattern, no Jackson
	 * polymorphism annotations needed: the controller returns this type and Jackson serializes whichever concrete
	 * record it is). */
	public sealed interface NpdView permits ErgoNpdView, CustomerNpdView, SupplierNpdView {
	}

	/** ERGO's unrestricted view: both customer and supplier, every activity/checklist/issue/approval. */
	public record ErgoNpdView(String audience, long id, String npdNumber, long rfqId, String rfqNumber, Named customer,
			Named supplier, String partNumber, String revision, String description, String quoteReference,
			NpdStage stage, LocalDate targetSampleDate, LocalDate actualSampleSubmissionDate, Long delayDays,
			Long ownerUserId, String owner, Long qualityOwnerUserId, String qualityOwner, RiskLevel riskLevel,
			PlanStatus planStatus, Long samplePoId, String samplePoNumber, List<ActivityView> activities,
			List<IssueRiskView> issuesRisks, List<ApprovalView> approvals, List<StageHistoryView> stageHistory,
			Set<NpdAction> actions, Instant createdAt, Instant updatedAt) implements NpdView {
	}

	/** Never carries the supplier (§8.1 anonymity, extended to NPD): activities/checklists/issues are the
	 * customer's own only, and approvals are limited to the three CUSTOMER_* types. {@code quoteReference} is
	 * also left out here (unlike the ERGO/supplier views): it is free text the supplier typed when submitting
	 * their quote, so nothing stops it naming their own company — showing it to the customer would leak supplier
	 * identity through a field neither party ever intended as a disclosure channel. {@code riskLevel} (ERGO's own
	 * internal project risk rating, not an SRS §12.8.3 header field) is dropped for the same reason. {@code
	 * stageHistory} is the {@link ExternalStageHistoryView} variant (no {@code remarks}). */
	public record CustomerNpdView(String audience, long id, String npdNumber, long rfqId, String rfqNumber,
			Named customer, Long samplePoId, String samplePoNumber, String partNumber, String revision, String description,
			NpdStage stage, LocalDate targetSampleDate, LocalDate actualSampleSubmissionDate, Long delayDays,
			List<ActivityView> activities, List<IssueRiskView> issuesRisks, List<ApprovalView> approvals,
			List<ExternalStageHistoryView> stageHistory, Set<NpdAction> actions, Instant createdAt, Instant updatedAt)
			implements NpdView {
	}

	/** Never carries the customer, and no approvals at all (SRS §10.6: every supplier role is X on Approve).
	 * {@code riskLevel} is dropped, same reasoning as {@link CustomerNpdView}. {@code stageHistory} is the
	 * {@link ExternalStageHistoryView} variant (no {@code remarks}). */
	public record SupplierNpdView(String audience, long id, String npdNumber, long rfqId, String rfqNumber,
			String partNumber, String revision, String description, String quoteReference, NpdStage stage,
			LocalDate targetSampleDate, LocalDate actualSampleSubmissionDate, Long delayDays,
			List<ActivityView> activities, List<IssueRiskView> issuesRisks,
			List<ExternalStageHistoryView> stageHistory, Set<NpdAction> actions, Instant createdAt,
			Instant updatedAt) implements NpdView {
	}

}
