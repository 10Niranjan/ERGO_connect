package com.ergo.connect.masterdata.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyProfile;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyRequest;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyView;
import com.ergo.connect.masterdata.access.CompanyDtos.ReviewRequest;
import com.ergo.connect.masterdata.access.CompanyService;
import com.ergo.connect.masterdata.access.CompanyStatus;
import com.ergo.connect.masterdata.access.UserDtos.ActiveRequest;

import jakarta.validation.Valid;

/**
 * SRS §10.2 Customer Management and §10.3 Supplier Management. Layer 1 (which roles may attempt an action) is here;
 * layer 2 (which companies, in which status) is in CompanyService.
 */
@RestController
@RequestMapping("/api/companies")
class CompanyController {

	private static final String CREATORS = "hasAnyRole('ERGO_ADMIN', 'ERGO_RFQ', 'ERGO_KAM')";

	private static final String EDITORS = "hasAnyRole('ERGO_ADMIN', 'ERGO_RFQ', 'ERGO_KAM', 'CUSTOMER_RFQ', "
			+ "'CUSTOMER_KAM', 'CUSTOMER_MANAGEMENT', 'SUPPLIER_RFQ', 'SUPPLIER_KAM')";

	/** ERGO Management alone approves companies (client question 11, 2026-11-19). */
	private static final String REVIEWERS = "hasRole('ERGO_MANAGEMENT')";

	private final CompanyService companies;

	CompanyController(CompanyService companies) {
		this.companies = companies;
	}

	/**
	 * Every role; the list is limited to the caller's record scope. {@code type} / {@code status} narrow it: the
	 * approved supplier list is {@code ?type=SUPPLIER&status=ACTIVE}.
	 */
	@GetMapping
	List<CompanyView> list(@RequestParam(required = false) CompanyType type,
			@RequestParam(required = false) CompanyStatus status) {
		return companies.list(type, status);
	}

	@GetMapping("/{id}")
	CompanyView get(@PathVariable long id) {
		return companies.get(id);
	}

	@PostMapping
	@PreAuthorize(CREATORS)
	@ResponseStatus(HttpStatus.CREATED)
	CompanyView create(@Valid @RequestBody CompanyRequest body) {
		return companies.create(body);
	}

	@PutMapping("/{id}")
	@PreAuthorize(EDITORS)
	CompanyView update(@PathVariable long id, @Valid @RequestBody CompanyProfile body) {
		return companies.update(id, body);
	}

	@PostMapping("/{id}/approve")
	@PreAuthorize(REVIEWERS)
	CompanyView approve(@PathVariable long id, @Valid @RequestBody ReviewRequest body) {
		return companies.approve(id, body.comment());
	}

	@PostMapping("/{id}/reject")
	@PreAuthorize(REVIEWERS)
	CompanyView reject(@PathVariable long id, @Valid @RequestBody ReviewRequest body) {
		return companies.reject(id, body.comment());
	}

	/** A draft, a company sent back for more information or a rejected one goes (again) to ERGO Management. */
	@PostMapping("/{id}/send-for-approval")
	@PreAuthorize(CREATORS)
	CompanyView sendForApproval(@PathVariable long id) {
		return companies.sendForApproval(id);
	}

	@PostMapping("/{id}/request-info")
	@PreAuthorize(REVIEWERS)
	CompanyView requestInfo(@PathVariable long id, @Valid @RequestBody ReviewRequest body) {
		return companies.requestInfo(id, body.comment());
	}

	@PutMapping("/{id}/active")
	@PreAuthorize("hasRole('ERGO_ADMIN')")
	CompanyView setActive(@PathVariable long id, @Valid @RequestBody ActiveRequest body) {
		return companies.setActive(id, body.active());
	}

}
