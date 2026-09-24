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

import com.ergo.connect.masterdata.access.CompanyDtos.CompanyRequest;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyResponse;
import com.ergo.connect.masterdata.access.CompanyService;

import jakarta.validation.Valid;

/** Company records. Phase 1a: ERGO Admin manages, ERGO Management views (SRS §10.2/10.3 V + A rows). */
@RestController
@RequestMapping("/api/companies")
class CompanyController {

	private final CompanyService companies;

	CompanyController(CompanyService companies) {
		this.companies = companies;
	}

	@GetMapping
	@PreAuthorize("hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT')")
	List<CompanyResponse> list() {
		return companies.list();
	}

	@PostMapping
	@PreAuthorize("hasRole('ERGO_ADMIN')")
	@ResponseStatus(HttpStatus.CREATED)
	CompanyResponse create(@Valid @RequestBody CompanyRequest body) {
		return companies.create(body);
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasRole('ERGO_ADMIN')")
	CompanyResponse update(@PathVariable long id, @Valid @RequestBody CompanyRequest body) {
		return companies.update(id, body);
	}

}
