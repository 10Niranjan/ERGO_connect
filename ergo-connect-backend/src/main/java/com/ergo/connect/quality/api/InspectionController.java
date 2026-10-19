package com.ergo.connect.quality.api;

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

import com.ergo.connect.quality.domain.InspectionService;
import com.ergo.connect.quality.domain.QualityDtos.InspectionDetail;
import com.ergo.connect.quality.domain.QualityDtos.InspectionForm;
import com.ergo.connect.quality.domain.QualityDtos.InspectionSummary;
import com.ergo.connect.quality.domain.QualityDtos.InspectionUpdateForm;
import com.ergo.connect.quality.domain.QualityDtos.ReasonForm;

import jakarta.validation.Valid;

/**
 * SRS §10.8 Quality Management (inspections), §14.7 workflow. Layer 1 (which roles may attempt what) is here; layer
 * 2 (which inspections, in which status, and whose own company) is {@code InspectionService}.
 */
@RestController
@RequestMapping("/api/quality/inspections")
class InspectionController {

	// The role sheet (ModuleAccess), Quality column; the service applies the record scope and per-action rules.
	private static final String VIEWERS = "@access.view('QUALITY')";

	/** SRS §10.8 Create/Edit/Submit: ERGO Admin/Quality and Supplier Quality only (see {@code InspectionService}'s
	 * class comment for why Customer Quality is not here despite the matrix's literal C/E/S). */
	private static final String EDITORS = "@access.act('QUALITY', 'ERGO') or @access.act('QUALITY', 'SUPPLIER')";

	/** SRS §10.8 Approve, its own authority separate from edit (CLAUDE.md non-negotiable): includes ERGO Management,
	 * who has no create/edit/submit right at all. */
	private static final String APPROVERS = "@access.approve('QUALITY', 'ERGO')";

	private final InspectionService inspections;

	InspectionController(InspectionService inspections) {
		this.inspections = inspections;
	}

	@GetMapping
	@PreAuthorize(VIEWERS)
	List<? extends InspectionSummary> list() {
		return inspections.list();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEWERS)
	InspectionDetail get(@PathVariable long id) {
		return inspections.get(id);
	}

	@PostMapping
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	InspectionDetail create(@Valid @RequestBody InspectionForm body) {
		return inspections.create(body);
	}

	@PutMapping("/{id}")
	@PreAuthorize(EDITORS)
	InspectionDetail edit(@PathVariable long id, @Valid @RequestBody InspectionUpdateForm body) {
		return inspections.edit(id, body);
	}

	/** SRS §14.7: OPEN -> IN_INSPECTION, recording the raw checked/rejected quantities and defects. */
	@PostMapping("/{id}/submit")
	@PreAuthorize(EDITORS)
	InspectionDetail submit(@PathVariable long id, @Valid @RequestBody InspectionUpdateForm body) {
		return inspections.submit(id, body);
	}

	/** SRS §14.7: IN_INSPECTION -> PASSED. */
	@PostMapping("/{id}/pass")
	@PreAuthorize(APPROVERS)
	InspectionDetail pass(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return inspections.pass(id, body);
	}

	/** SRS §14.7: IN_INSPECTION -> REJECTED. Reason required (CLAUDE.md non-negotiable). */
	@PostMapping("/{id}/reject")
	@PreAuthorize(APPROVERS)
	InspectionDetail reject(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return inspections.reject(id, body);
	}

	/** SRS §14.7: IN_INSPECTION -> RELEASED_WITH_DEVIATION. Reason required. */
	@PostMapping("/{id}/release-with-deviation")
	@PreAuthorize(APPROVERS)
	InspectionDetail releaseWithDeviation(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return inspections.releaseWithDeviation(id, body);
	}

	/** (PASSED | REJECTED | RELEASED_WITH_DEVIATION) -> CLOSED. */
	@PostMapping("/{id}/close")
	@PreAuthorize(APPROVERS)
	InspectionDetail close(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return inspections.close(id, body);
	}

	/** CLOSED -> OPEN, the only reopen path. Reason required (CLAUDE.md non-negotiable). */
	@PostMapping("/{id}/reopen")
	@PreAuthorize(APPROVERS)
	InspectionDetail reopen(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return inspections.reopen(id, body);
	}

}
