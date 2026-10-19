package com.ergo.connect.npd.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.npd.domain.NpdDtos.ActivityForm;
import com.ergo.connect.npd.domain.NpdDtos.ActivityProgressForm;
import com.ergo.connect.npd.domain.NpdDtos.ApprovalDecisionForm;
import com.ergo.connect.npd.domain.NpdDtos.ApprovalForm;
import com.ergo.connect.npd.domain.NpdDtos.CommentForm;
import com.ergo.connect.npd.domain.NpdDtos.ConversionQueueRow;
import com.ergo.connect.npd.domain.NpdDtos.IssueRiskForm;
import com.ergo.connect.npd.domain.NpdDtos.IssueStatusForm;
import com.ergo.connect.npd.domain.NpdDtos.NpdListRow;
import com.ergo.connect.npd.domain.NpdDtos.NpdOwnProjectRow;
import com.ergo.connect.npd.domain.NpdDtos.NpdProjectForm;
import com.ergo.connect.npd.domain.NpdDtos.NpdView;
import com.ergo.connect.npd.domain.NpdDtos.PromptChecklistForm;
import com.ergo.connect.npd.domain.NpdDtos.PromptResponseForm;
import com.ergo.connect.common.audit.AuditLogService.AuditEntry;
import com.ergo.connect.npd.domain.NpdService;

import jakarta.validation.Valid;

/**
 * SRS §10.6 NPD Management, §18 NPD APIs, §14.5 workflow. Layer 1 (which roles may attempt what) is here; layer 2
 * (which projects, in which stage, whose own activity/issue/approval) is NpdService. The conversion queue, the
 * ERGO project list and convert stay ERGO-only/ERGO-scoped; {@code /mine} is the separate, narrower entry point
 * {@code list()} would otherwise never provide 3b's customer/supplier roles.
 */
@RestController
@RequestMapping("/api/npd")
class NpdController {

	// The role sheet (ModuleAccess), NPD column; NpdService applies the record scope and the per-action rules.

	/** The ERGO project list and conversion queue. */
	private static final String VIEWERS = "@access.view('NPD', 'ERGO')";

	/** Everyone with any NPD access: the ERGO roles plus the customer and supplier roles that reach their own projects. */
	private static final String DETAIL_VIEWERS = "@access.view('NPD')";

	/** ERGO roles that create / edit / submit in NPD ("A" and up). */
	private static final String EDITORS = "@access.act('NPD', 'ERGO')";

	/** ERGO's owning roles ("O"): convert an RFQ, close a stage. */
	private static final String OWNERS = "@access.approve('NPD', 'ERGO')";

	/** EDITORS plus the owning company's own activity/issue/checklist actions (the service confines each to its own
	 * company). */
	private static final String OWN_ACTIVITY_EDITORS = "@access.act('NPD')";

	/** Customer/supplier NPD-touching roles only (not ERGO, which already has {@code list()}). */
	private static final String OWN_PROJECT_VIEWERS = "@access.view('NPD', 'CUSTOMER') or @access.view('NPD', 'SUPPLIER')";

	/** Final approval ("O" / "CO"): ERGO's owners decide ERGO-owned gates, the customer's CO the customer ones; no
	 * supplier role ever decides. NpdService matches the side to the approval's own type. */
	private static final String APPROVAL_DECIDERS = "@access.approve('NPD', 'ERGO') or @access.approve('NPD', 'CUSTOMER')";

	private final NpdService npd;

	NpdController(NpdService npd) {
		this.npd = npd;
	}

	@GetMapping("/conversion-queue")
	@PreAuthorize(VIEWERS)
	List<ConversionQueueRow> conversionQueue() {
		return npd.conversionQueue();
	}

	@PostMapping("/convert/{rfqId}")
	@PreAuthorize(OWNERS)
	@ResponseStatus(HttpStatus.CREATED)
	NpdView convert(@PathVariable long rfqId, @Valid @RequestBody CommentForm body) {
		return npd.convert(rfqId, body.comment());
	}

	@GetMapping
	@PreAuthorize(VIEWERS)
	List<NpdListRow> list() {
		return npd.list();
	}

	/** SRS §12.15's customer "NPD" / supplier "NPD Tasks" nav entry: how 3b reaches a project's own detail page. */
	@GetMapping("/mine")
	@PreAuthorize(OWN_PROJECT_VIEWERS)
	List<NpdOwnProjectRow> myProjects() {
		return npd.myProjects();
	}

	@GetMapping("/{id}")
	@PreAuthorize(DETAIL_VIEWERS)
	NpdView get(@PathVariable long id) {
		return npd.get(id);
	}

	@GetMapping("/{id}/history")
	@PreAuthorize(DETAIL_VIEWERS)
	List<AuditEntry> history(@PathVariable long id) {
		return npd.history(id);
	}

	@PutMapping("/{id}")
	@PreAuthorize(EDITORS)
	NpdView updateProject(@PathVariable long id, @Valid @RequestBody NpdProjectForm body) {
		return npd.updateProject(id, body);
	}

	@PostMapping("/{id}/close-stage")
	@PreAuthorize(OWNERS)
	NpdView closeStage(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return npd.closeStage(id, body.comment());
	}

	@PostMapping("/{id}/activities")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	NpdView addActivity(@PathVariable long id, @Valid @RequestBody ActivityForm body) {
		return npd.addActivity(id, body);
	}

	@PutMapping("/activities/{activityId}")
	@PreAuthorize(EDITORS)
	NpdView updateActivity(@PathVariable long activityId, @Valid @RequestBody ActivityForm body) {
		return npd.updateActivity(activityId, body);
	}

	/** The owning company's own progress update (SRS §17.4); the service confines it to the caller's own activity. */
	@PostMapping("/activities/{activityId}/progress")
	@PreAuthorize(OWN_ACTIVITY_EDITORS)
	NpdView updateOwnActivity(@PathVariable long activityId, @Valid @RequestBody ActivityProgressForm body) {
		return npd.updateOwnActivity(activityId, body);
	}

	@PostMapping("/activities/{activityId}/block")
	@PreAuthorize(OWN_ACTIVITY_EDITORS)
	NpdView markBlocked(@PathVariable long activityId, @Valid @RequestBody CommentForm body) {
		return npd.markBlocked(activityId, body.comment());
	}

	@PostMapping("/activities/{activityId}/unblock")
	@PreAuthorize(OWN_ACTIVITY_EDITORS)
	NpdView unblock(@PathVariable long activityId, @Valid @RequestBody CommentForm body) {
		return npd.unblock(activityId, body.comment());
	}

	@PostMapping("/{id}/prompt-checklists")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	NpdView addPromptChecklist(@PathVariable long id, @Valid @RequestBody PromptChecklistForm body) {
		return npd.addPromptChecklist(id, body);
	}

	@PutMapping("/prompt-checklists/{checklistId}")
	@PreAuthorize(EDITORS)
	NpdView updatePromptChecklist(@PathVariable long checklistId, @Valid @RequestBody PromptChecklistForm body) {
		return npd.updatePromptChecklist(checklistId, body);
	}

	@PostMapping("/prompt-checklists/{checklistId}/response")
	@PreAuthorize(OWN_ACTIVITY_EDITORS)
	NpdView respondToPrompt(@PathVariable long checklistId, @Valid @RequestBody PromptResponseForm body) {
		return npd.respondToPrompt(checklistId, body);
	}

	@PostMapping("/{id}/issues")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	NpdView raiseIssue(@PathVariable long id, @Valid @RequestBody IssueRiskForm body) {
		return npd.raiseIssue(id, body);
	}

	@PutMapping("/issues/{issueId}/status")
	@PreAuthorize(OWN_ACTIVITY_EDITORS)
	NpdView updateIssueStatus(@PathVariable long issueId, @Valid @RequestBody IssueStatusForm body) {
		return npd.updateIssueStatus(issueId, body);
	}

	@PostMapping("/{id}/approvals")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	NpdView requestApproval(@PathVariable long id, @Valid @RequestBody ApprovalForm body) {
		return npd.requestApproval(id, body);
	}

	@PostMapping("/approvals/{approvalId}/decide")
	@PreAuthorize(APPROVAL_DECIDERS)
	NpdView decideApproval(@PathVariable long approvalId, @Valid @RequestBody ApprovalDecisionForm body) {
		return npd.decideApproval(approvalId, body);
	}

}
