package com.ergo.connect.npd.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.common.audit.AuditLogService.AuditEntry;
import com.ergo.connect.npd.domain.NpdDtos.NpdView;
import com.ergo.connect.npd.domain.SamplePoDtos.Assignees;
import com.ergo.connect.npd.domain.SamplePoDtos.CreateProjectForm;
import com.ergo.connect.npd.domain.SamplePoDtos.EligiblePart;
import com.ergo.connect.npd.domain.SamplePoDtos.ReturnForm;
import com.ergo.connect.npd.domain.SamplePoDtos.SamplePoEditForm;
import com.ergo.connect.npd.domain.SamplePoDtos.SamplePoForm;
import com.ergo.connect.npd.domain.SamplePoDtos.SamplePoPage;
import com.ergo.connect.npd.domain.SamplePoDtos.SamplePoView;
import com.ergo.connect.npd.domain.SamplePoService;

import jakarta.validation.Valid;

/**
 * Flow doc steps 6-7: the customer's Sample PO and the KAM's decision on it. Layer 1 (which roles may attempt what) is
 * here, from the role sheet; layer 2 (whose Sample PO, in which state) is SamplePoService. Suppliers have no access.
 */
@RestController
@RequestMapping("/api/npd/sample-pos")
class SamplePoController {

	/** Everyone who may open NPD; the service narrows to the caller's own company / assigned accounts, and a supplier
	 * is refused here. */
	private static final String VIEWERS = "@access.view('NPD', 'ERGO') or @access.view('NPD', 'CUSTOMER')";

	/** The customer-side final-approval role of NPD (Customer NPD) raises and corrects a Sample PO. */
	private static final String RAISERS = "@access.approve('NPD', 'CUSTOMER')";

	/** ERGO roles that act in NPD; the service allows only the KAM and Management to decide. */
	private static final String DESK = "@access.act('NPD', 'ERGO')";

	private final SamplePoService samplePos;

	SamplePoController(SamplePoService samplePos) {
		this.samplePos = samplePos;
	}

	@GetMapping
	@PreAuthorize(VIEWERS)
	SamplePoPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
			@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
		return samplePos.list(page, size, status, q);
	}

	/** The customer's approved parts that still need a Sample PO. */
	@GetMapping("/eligible-parts")
	@PreAuthorize(RAISERS)
	List<EligiblePart> eligibleParts() {
		return samplePos.eligibleParts();
	}

	/** The ERGO NPD and ERGO Quality users the KAM can assign a project to. */
	@GetMapping("/assignees")
	@PreAuthorize(DESK)
	Assignees assignees() {
		return samplePos.assignees();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEWERS)
	SamplePoView get(@PathVariable long id) {
		return samplePos.get(id);
	}

	@GetMapping("/{id}/history")
	@PreAuthorize(VIEWERS)
	List<AuditEntry> history(@PathVariable long id) {
		return samplePos.history(id);
	}

	@PostMapping
	@PreAuthorize(RAISERS)
	@ResponseStatus(HttpStatus.CREATED)
	SamplePoView raise(@Valid @RequestBody SamplePoForm body) {
		return samplePos.raise(body);
	}

	/** The customer corrects a returned Sample PO and sends it again. */
	@PutMapping("/{id}")
	@PreAuthorize(RAISERS)
	SamplePoView resubmit(@PathVariable long id, @Valid @RequestBody SamplePoEditForm body) {
		return samplePos.resubmit(id, body);
	}

	@PostMapping("/{id}/return")
	@PreAuthorize(DESK)
	SamplePoView returnToCustomer(@PathVariable long id, @Valid @RequestBody ReturnForm body) {
		return samplePos.returnToCustomer(id, body.comment());
	}

	/** Accepting the Sample PO creates the sample development project. */
	@PostMapping("/{id}/create-project")
	@PreAuthorize(DESK)
	@ResponseStatus(HttpStatus.CREATED)
	NpdView createProject(@PathVariable long id, @Valid @RequestBody CreateProjectForm body) {
		return samplePos.createProject(id, body);
	}

}
