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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.masterdata.access.CompanyDtos.CompanyProfile;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyRequest;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyResponse;
import com.ergo.connect.masterdata.access.CompanyDtos.ReviewRequest;
import com.ergo.connect.masterdata.access.CompanyService;
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

	private static final String REVIEWERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT')";

	private final CompanyService companies;

	CompanyController(CompanyService companies) {
		this.companies = companies;
	}

	/** Every role; the list is limited to the caller's record scope. */
	@GetMapping
	List<CompanyResponse> list() {
		return companies.list();
	}

	@GetMapping("/{id}")
	CompanyResponse get(@PathVariable long id) {
		return companies.get(id);
	}

	@PostMapping
	@PreAuthorize(CREATORS)
	@ResponseStatus(HttpStatus.CREATED)
	CompanyResponse create(@Valid @RequestBody CompanyRequest body) {
		return companies.create(body);
	}

	@PutMapping("/{id}")
	@PreAuthorize(EDITORS)
	CompanyResponse update(@PathVariable long id, @Valid @RequestBody CompanyProfile body) {
		return companies.update(id, body);
	}

	@PostMapping("/{id}/approve")
	@PreAuthorize(REVIEWERS)
	CompanyResponse approve(@PathVariable long id, @Valid @RequestBody ReviewRequest body) {
		return companies.approve(id, body.comment());
	}

	@PostMapping("/{id}/reject")
	@PreAuthorize(REVIEWERS)
	CompanyResponse reject(@PathVariable long id, @Valid @RequestBody ReviewRequest body) {
		return companies.reject(id, body.comment());
	}

	@PostMapping("/{id}/resubmit")
	@PreAuthorize(CREATORS)
	CompanyResponse resubmit(@PathVariable long id) {
		return companies.resubmit(id);
	}

	@PutMapping("/{id}/active")
	@PreAuthorize("hasRole('ERGO_ADMIN')")
	CompanyResponse setActive(@PathVariable long id, @Valid @RequestBody ActiveRequest body) {
		return companies.setActive(id, body.active());
	}

}
