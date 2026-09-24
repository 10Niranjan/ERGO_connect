package com.ergo.connect.masterdata.access;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyRequest;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyResponse;

/**
 * Company records for the ERGO Admin. ponytail: ERGO-only for now; the SRS §10.2/10.3 customer and supplier
 * management rules (KAM and RFQ edit rights, external users editing their own profile) come in Phase 1b.
 */
@Service
@Transactional
public class CompanyService {

	private final CompanyRepository companies;

	CompanyService(CompanyRepository companies) {
		this.companies = companies;
	}

	@Transactional(readOnly = true)
	public List<CompanyResponse> list() {
		return companies.findAllByOrderByNameAsc().stream().map(CompanyService::toResponse).toList();
	}

	public CompanyResponse create(CompanyRequest request) {
		if (request.type() == CompanyType.ERGO) {
			throw ApiException.unprocessable("The ERGO company is created at system setup and cannot be added here");
		}
		if (companies.existsByCodeIgnoreCase(request.code())) {
			throw ApiException.conflict("A company with this code already exists");
		}
		Company company = new Company();
		company.code = request.code();
		company.type = request.type();
		apply(company, request);
		return toResponse(companies.save(company));
	}

	public CompanyResponse update(long id, CompanyRequest request) {
		Company company = companies.findById(id).orElseThrow(() -> ApiException.notFound("Company"));
		if (!company.code.equals(request.code()) || company.type != request.type()) {
			throw ApiException.unprocessable("Company code and type cannot be changed");
		}
		if (company.type == CompanyType.ERGO && Boolean.FALSE.equals(request.active())) {
			throw ApiException.unprocessable("The ERGO company cannot be deactivated");
		}
		apply(company, request);
		return toResponse(company);
	}

	private static void apply(Company company, CompanyRequest request) {
		company.name = request.name().trim();
		company.addressLine1 = request.addressLine1();
		company.addressLine2 = request.addressLine2();
		company.city = request.city();
		company.state = request.state();
		company.country = request.country();
		company.postalCode = request.postalCode();
		company.taxId = request.taxId();
		company.website = request.website();
		company.primaryContactName = request.primaryContactName();
		company.primaryContactEmail = request.primaryContactEmail();
		company.primaryContactPhone = request.primaryContactPhone();
		if (request.active() != null) {
			company.active = request.active();
		}
	}

	private static CompanyResponse toResponse(Company c) {
		return new CompanyResponse(c.id, c.code, c.name, c.type, c.addressLine1, c.addressLine2, c.city, c.state,
				c.country, c.postalCode, c.taxId, c.website, c.primaryContactName, c.primaryContactEmail,
				c.primaryContactPhone, c.active);
	}

}
