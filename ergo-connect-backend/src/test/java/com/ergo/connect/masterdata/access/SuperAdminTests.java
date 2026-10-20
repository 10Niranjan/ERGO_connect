package com.ergo.connect.masterdata.access;

import static com.ergo.connect.common.security.CompanyType.CUSTOMER;
import static com.ergo.connect.common.security.CompanyType.ERGO;
import static com.ergo.connect.common.security.CompanyType.SUPPLIER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.security.Role;

/**
 * Super Admin (the ERGO_ADMIN role, docs/PERMISSIONS.md §10): only an account of the ERGO company can hold it, and only a
 * Super Admin can hand it out - through the API, and in the database whatever path touches the data.
 */
@IntegrationTest
class SuperAdminTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	CompanyRepository companies;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@Autowired
	OtpCodeStore otpCodes;

	@Autowired
	JdbcTemplate jdbc;

	Accounts accounts;

	Company ergo;

	Company customer;

	Company supplier;

	@BeforeEach
	void setUp() {
		accounts = new Accounts(companies, users, encoder, mvc, otpCodes);
		ergo = accounts.company(ERGO);
		customer = accounts.company(CUSTOMER);
		supplier = accounts.company(SUPPLIER);
	}

	@Test
	void onlyASuperAdminCanHandOutTheSuperAdminRole_everyOtherRoleIsRefused() throws Exception {
		User target = accounts.user(ergo, Role.ERGO_RFQ);
		String body = "{\"roles\": [\"ERGO_RFQ\", \"ERGO_ADMIN\"]}";
		for (Role role : new Role[] { Role.ERGO_MANAGEMENT, Role.ERGO_KAM, Role.ERGO_RFQ, Role.ERGO_NPD, Role.ERGO_OFP,
				Role.ERGO_QUALITY }) {
			String token = accounts.bearer(accounts.user(ergo, role));
			mvc.perform(json(put("/api/users/{id}/roles", target.id), body, token)).andExpect(status().isForbidden());
			mvc.perform(json(post("/api/users"), newUser(ergo, role.name()), token)).andExpect(status().isForbidden());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_role_mappings WHERE user_id = ? AND role_code = 'ERGO_ADMIN'",
				Integer.class, target.id)).isZero();

		// a Super Admin can (and the new admin gets the mandatory second factor)
		String admin = accounts.bearer(accounts.user(ergo, Role.ERGO_ADMIN));
		mvc.perform(json(put("/api/users/{id}/roles", target.id), body, admin)).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT two_factor_enabled FROM users WHERE user_id = ?", Boolean.class, target.id))
			.isTrue();
	}

	@Test
	void customerAndSupplierAccountsCanNeverHoldItNotThroughTheApiNorTheDatabase() throws Exception {
		String admin = accounts.bearer(accounts.user(ergo, Role.ERGO_ADMIN));
		User customerUser = accounts.user(customer, Role.CUSTOMER_RFQ);
		User supplierUser = accounts.user(supplier, Role.SUPPLIER_RFQ);

		for (Company company : new Company[] { customer, supplier }) {
			mvc.perform(json(post("/api/users"), newUser(company, "ERGO_ADMIN"), admin))
				.andExpect(status().isUnprocessableContent());
		}
		mvc.perform(json(put("/api/users/{id}/roles", customerUser.id), "{\"roles\": [\"ERGO_ADMIN\"]}", admin))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(json(put("/api/users/{id}/roles", supplierUser.id), "{\"roles\": [\"CUSTOMER_RFQ\", \"ERGO_ADMIN\"]}",
				admin))
			.andExpect(status().isUnprocessableContent());

		assertThatThrownBy(() -> jdbc.update("INSERT INTO user_role_mappings (user_id, role_code) VALUES (?, 'ERGO_ADMIN')",
				supplierUser.id)).hasMessageContaining("does not match");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_role_mappings WHERE role_code = 'ERGO_ADMIN' AND user_id IN (?, ?)",
				Integer.class, customerUser.id, supplierUser.id)).isZero();
	}

	@Test
	void aSuperAdminCannotBeMovedToAnotherCompanyAndTheErgoCompanyCannotChangeType() {
		User admin = accounts.user(ergo, Role.ERGO_ADMIN);

		assertThatThrownBy(() -> jdbc.update("UPDATE users SET company_id = ? WHERE user_id = ?", customer.id, admin.id))
			.hasMessageContaining("do not match");
		assertThatThrownBy(() -> jdbc.update("UPDATE companies SET company_type = 'CUSTOMER' WHERE company_id = ?", ergo.id))
			.hasMessageContaining("do not match");
		assertThat(jdbc.queryForObject("SELECT company_id FROM users WHERE user_id = ?", Long.class, admin.id))
			.isEqualTo(ergo.id);
	}

	private static String newUser(Company company, String role) {
		return """
				{"companyId": %d, "firstName": "New", "lastName": "Person", "email": "new.%d.%s@test.dev",
				 "roles": ["%s"]}""".formatted(company.id, System.nanoTime(), role.toLowerCase(), role);
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body, String bearer) {
		return request.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(body);
	}

}
