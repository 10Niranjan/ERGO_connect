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
import com.ergo.connect.npd.domain.ProductionPoDtos.Assignees;
import com.ergo.connect.npd.domain.ProductionPoDtos.CreateProjectForm;
import com.ergo.connect.npd.domain.ProductionPoDtos.EligibleProject;
import com.ergo.connect.npd.domain.ProductionPoDtos.ProductionPoEditForm;
import com.ergo.connect.npd.domain.ProductionPoDtos.ProductionPoForm;
import com.ergo.connect.npd.domain.ProductionPoDtos.ProductionPoPage;
import com.ergo.connect.npd.domain.ProductionPoDtos.ProductionPoView;
import com.ergo.connect.npd.domain.ProductionPoDtos.ReturnForm;
import com.ergo.connect.npd.domain.ProductionPoService;

import jakarta.validation.Valid;

/**
 * Flow doc step 11: the customer's production order, submitted under OFP, and the KAM's decision on it. Layer 1 (which
 * roles may attempt what) is here, from the OFP column of the role sheet; layer 2 (whose order, in which state) is
 * ProductionPoService. Suppliers have no access.
 */
@RestController
@RequestMapping("/api/ofp/production-pos")
class ProductionPoController {

	/** Everyone who may open OFP on the ERGO or customer side; the service narrows to the caller's own company / assigned
	 * accounts, and a supplier is refused here. */
	private static final String VIEWERS = "@access.view('OFP', 'ERGO') or @access.view('OFP', 'CUSTOMER')";

	/** The customer-side final-approval role of OFP (Customer OFP) releases and corrects a production order. */
	private static final String RAISERS = "@access.approve('OFP', 'CUSTOMER')";

	/** ERGO roles that act in OFP; the service allows only the KAM and Management to decide. */
	private static final String DESK = "@access.act('OFP', 'ERGO')";

	private final ProductionPoService productionPos;

	ProductionPoController(ProductionPoService productionPos) {
		this.productionPos = productionPos;
	}

	@GetMapping
	@PreAuthorize(VIEWERS)
	ProductionPoPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
			@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
		return productionPos.list(page, size, status, q);
	}

	/** The customer's approved sample projects that still need a production order. */
	@GetMapping("/eligible-projects")
	@PreAuthorize(RAISERS)
	List<EligibleProject> eligibleProjects() {
		return productionPos.eligibleProjects();
	}

	/** The ERGO OFP and ERGO Quality users the KAM can assign an OFP project to. */
	@GetMapping("/assignees")
	@PreAuthorize(DESK)
	Assignees assignees() {
		return productionPos.assignees();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEWERS)
	ProductionPoView get(@PathVariable long id) {
		return productionPos.get(id);
	}

	@GetMapping("/{id}/history")
	@PreAuthorize(VIEWERS)
	List<AuditEntry> history(@PathVariable long id) {
		return productionPos.history(id);
	}

	@PostMapping
	@PreAuthorize(RAISERS)
	@ResponseStatus(HttpStatus.CREATED)
	ProductionPoView raise(@Valid @RequestBody ProductionPoForm body) {
		return productionPos.raise(body);
	}

	/** The customer corrects a returned production order and sends it again. */
	@PutMapping("/{id}")
	@PreAuthorize(RAISERS)
	ProductionPoView resubmit(@PathVariable long id, @Valid @RequestBody ProductionPoEditForm body) {
		return productionPos.resubmit(id, body);
	}

	@PostMapping("/{id}/return")
	@PreAuthorize(DESK)
	ProductionPoView returnToCustomer(@PathVariable long id, @Valid @RequestBody ReturnForm body) {
		return productionPos.returnToCustomer(id, body.comment());
	}

	/** Accepting the production order creates the OFP project. */
	@PostMapping("/{id}/create-project")
	@PreAuthorize(DESK)
	@ResponseStatus(HttpStatus.CREATED)
	NpdView createProject(@PathVariable long id, @Valid @RequestBody CreateProjectForm body) {
		return productionPos.createProject(id, body);
	}

}
