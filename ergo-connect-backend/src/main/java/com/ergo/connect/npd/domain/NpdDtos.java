package com.ergo.connect.npd.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.ergo.connect.common.security.CompanyType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * NPD request and response shapes. One ERGO-facing view is enough for 3a (no customer/supplier NPD access yet); 3b
 * adds their own DTOs the way RfqDtos/QuoteDtos split by audience today.
 */
public final class NpdDtos {

	private NpdDtos() {
	}

	/** Buttons the caller may use now, computed by the same rules that enforce them. CONVERT only ever appears on a
	 * conversion-queue row (it acts on an RFQ, not a project); the rest appear on a project or one of its activities. */
	public enum NpdAction {
		CONVERT, UPDATE_PROJECT, CLOSE_STAGE, ADD_ACTIVITY, EDIT_ACTIVITY, MARK_BLOCKED, UNBLOCK
	}

	public record Named(long id, String code, String name) {
	}

	/** Optional comment/reason, reused across actions; the service decides whether it is required. */
	public record CommentForm(@Size(max = 2000) String comment) {
	}

	/** SRS §12.8.2: a customer-approved RFQ waiting for manual conversion, not yet converted. */
	public record ConversionQueueRow(long rfqId, String rfqNumber, String customerName, String partNumber,
			String revision, String description, Instant approvedAt, Set<NpdAction> actions) {
	}

	/** SRS §12.8.1 NPD project list columns. Supplier is ERGO-internal (§8.1 anonymity extended) but this row is
	 * ERGO-only in 3a (no customer/supplier NPD access exists yet), so it is shown here exactly as the detail view. */
	public record NpdListRow(long id, String npdNumber, String rfqNumber, String customerName, String supplierName,
			String partNumber, NpdStage stage, LocalDate targetSampleDate, Long delayDays, String owner,
			RiskLevel riskLevel) {
	}

	/** Owner reassignment, risk level and sample dates — ERGO Admin/NPD project-header fields, edited outside the
	 * activity list. A full replace of these four fields, not a patch: resend the current value for anything you
	 * don't want to change (a field left off, e.g. via the raw API, clears it, it is not left alone). */
	public record NpdProjectForm(Long ownerUserId, RiskLevel riskLevel, LocalDate targetSampleDate,
			LocalDate actualSampleSubmissionDate) {
	}

	/** Create and edit an activity. Owner (organization/company) is always ERGO in 3a and is server-derived, never
	 * client-supplied; {@code status} may not be BLOCKED here (only markBlocked/unblock touch blocked_flag). */
	public record ActivityForm(@NotBlank @Size(max = 200) String activityName,
			@Size(max = 4000) String activityDescription, Long responsibleUserId, LocalDate plannedDate,
			LocalDate baselineDate, LocalDate actualDate, Long dependencyActivityId, @NotNull ActivityStatus status,
			@NotNull Boolean mandatory, @NotNull Boolean riskFlag, @NotNull Boolean issueFlag) {
	}

	public record ActivityView(long id, NpdStage phaseName, String activityName, String activityDescription,
			CompanyType ownerOrganizationType, String ownerCompanyName, Long responsibleUserId, String responsibleUser,
			LocalDate plannedDate, LocalDate baselineDate, LocalDate actualDate, Long dependencyActivityId,
			ActivityStatus status, boolean blockedFlag, boolean riskFlag, boolean issueFlag, boolean mandatoryFlag,
			Set<NpdAction> actions) {
	}

	/** SRS §13.4.2: one row per stage move, oldest first. {@code previousStage} is null for the very first row. */
	public record StageHistoryView(NpdStage stage, NpdStage previousStage, String changedBy, Instant changedAt,
			String remarks) {
	}

	/** The NPD project's detail view: linked RFQ header, activities and stage history. ERGO-only for 3a. */
	public record NpdDetailView(long id, String npdNumber, long rfqId, String rfqNumber, Named customer,
			Named supplier, String partNumber, String revision, String description, String quoteReference,
			NpdStage stage, LocalDate targetSampleDate, LocalDate actualSampleSubmissionDate, Long delayDays,
			Long ownerUserId, String owner, RiskLevel riskLevel, List<ActivityView> activities,
			List<StageHistoryView> stageHistory, Set<NpdAction> actions, Instant createdAt, Instant updatedAt) {
	}

}
