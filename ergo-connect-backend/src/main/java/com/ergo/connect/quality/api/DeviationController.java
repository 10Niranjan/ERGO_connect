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

import com.ergo.connect.quality.domain.DeviationDtos.DeviationDetail;
import com.ergo.connect.quality.domain.DeviationDtos.DeviationForm;
import com.ergo.connect.quality.domain.DeviationDtos.DeviationSummary;
import com.ergo.connect.quality.domain.DeviationDtos.DeviationUpdateForm;
import com.ergo.connect.quality.domain.DeviationService;
import com.ergo.connect.quality.domain.QualityDtos.ReasonForm;

import jakarta.validation.Valid;

/**
 * SRS §10.8 Deviations, §14.7 workflow, Phase 5c. Layer 1 (which roles may attempt what) is here; layer 2 (which
 * deviations, in which status, and whose own company) is {@code DeviationService} - same split as {@link
 * NcrController}.
 */
@RestController
@RequestMapping("/api/quality/deviations")
class DeviationController {

	/** SRS §10.8 View: every role except the three *_RFQ roles (X) - same set as {@link NcrController}. */
	private static final String VIEWERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_KAM', 'ERGO_NPD', "
			+ "'ERGO_OFP', 'ERGO_QUALITY', 'CUSTOMER_NPD', 'CUSTOMER_OFP', 'CUSTOMER_QUALITY', 'CUSTOMER_KAM', "
			+ "'CUSTOMER_MANAGEMENT', 'SUPPLIER_NPD', 'SUPPLIER_OFP', 'SUPPLIER_QUALITY', 'SUPPLIER_KAM')";

	/** SRS §10.8 Create/Edit/Submit: ERGO Admin/Quality, Customer Quality and Supplier Quality. */
	private static final String EDITORS = "hasAnyRole('ERGO_ADMIN', 'ERGO_QUALITY', 'CUSTOMER_QUALITY', 'SUPPLIER_QUALITY')";

	/** REQUESTED -&gt; UNDER_REVIEW and APPROVED/EXPIRED -&gt; CLOSED: ERGO Admin/Quality only. */
	private static final String ERGO_EDITORS = "hasAnyRole('ERGO_ADMIN', 'ERGO_QUALITY')";

	/** SRS §10.8 Approve, its own authority separate from edit ("A*" footnote): ERGO Management may decide the
	 * ERGO_QUALITY row despite having no create/edit/submit right; Customer Quality may decide only its own
	 * CUSTOMER_QUALITY row (both narrowed to "my own row only" inside {@code DeviationService}). Supplier Quality
	 * never approves. */
	private static final String APPROVERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_QUALITY', 'CUSTOMER_QUALITY')";

	private final DeviationService deviations;

	DeviationController(DeviationService deviations) {
		this.deviations = deviations;
	}

	@GetMapping
	@PreAuthorize(VIEWERS)
	List<? extends DeviationSummary> list() {
		return deviations.list();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEWERS)
	DeviationDetail get(@PathVariable long id) {
		return deviations.get(id);
	}

	@PostMapping
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	DeviationDetail create(@Valid @RequestBody DeviationForm body) {
		return deviations.create(body);
	}

	@PutMapping("/{id}")
	@PreAuthorize(EDITORS)
	DeviationDetail edit(@PathVariable long id, @Valid @RequestBody DeviationUpdateForm body) {
		return deviations.edit(id, body);
	}

	/** SRS §14.7: REQUESTED -&gt; UNDER_REVIEW. */
	@PostMapping("/{id}/start-review")
	@PreAuthorize(ERGO_EDITORS)
	DeviationDetail startReview(@PathVariable long id) {
		return deviations.startReview(id);
	}

	/** Decides the caller's own approval row (ERGO_QUALITY or CUSTOMER_QUALITY - {@code DeviationService} rejects any
	 * attempt to decide the other one). */
	@PostMapping("/{id}/approve")
	@PreAuthorize(APPROVERS)
	DeviationDetail approve(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return deviations.approve(id, body);
	}

	/** Reason required. */
	@PostMapping("/{id}/reject")
	@PreAuthorize(APPROVERS)
	DeviationDetail reject(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return deviations.reject(id, body);
	}

	/** Reason required. */
	@PostMapping("/{id}/send-back")
	@PreAuthorize(APPROVERS)
	DeviationDetail sendBack(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return deviations.sendBack(id, body);
	}

	/** SRS §14.7: APPROVED/EXPIRED -&gt; CLOSED. */
	@PostMapping("/{id}/close")
	@PreAuthorize(ERGO_EDITORS)
	DeviationDetail close(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return deviations.close(id, body);
	}

}
