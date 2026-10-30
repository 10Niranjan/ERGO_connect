package com.ergo.connect.masterdata.access;

import static com.ergo.connect.common.security.CompanyType.CUSTOMER;
import static com.ergo.connect.common.security.CompanyType.ERGO;
import static com.ergo.connect.common.security.CompanyType.SUPPLIER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.security.Role;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Account creation and deletion (client decisions 2026-10-21): ERGO Admin creates every kind of account, ERGO RFQ
 * creates supplier accounts only, nobody creates accounts in a company that is not yet approved, and ERGO Admin
 * deletes (soft) accounts.
 */
@IntegrationTest
class AccountManagementTests {

	private static final AtomicInteger SEQ = new AtomicInteger();

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

	String asAdmin;

	String asRfq;

	String asManagement;

	@BeforeEach
	void setUp() throws Exception {
		accounts = new Accounts(companies, users, encoder, mvc, otpCodes);
		ergo = accounts.company(ERGO);
		customer = accounts.company(CUSTOMER);
		supplier = accounts.company(SUPPLIER);
		asAdmin = accounts.bearer(accounts.user(ergo, Role.ERGO_ADMIN));
		asRfq = accounts.bearer(accounts.user(ergo, Role.ERGO_RFQ));
		asManagement = accounts.bearer(accounts.user(ergo, Role.ERGO_MANAGEMENT));
	}

	@Test
	void ergoAdminCreatesErgoCustomerAndSupplierAccounts() throws Exception {
		createUser(asAdmin, ergo, "ERGO_NPD").andExpect(status().isCreated());
		createUser(asAdmin, customer, "CUSTOMER_RFQ").andExpect(status().isCreated());
		createUser(asAdmin, supplier, "SUPPLIER_RFQ").andExpect(status().isCreated());
	}

	@Test
	void ergoRfqCreatesSupplierAccountsOnly() throws Exception {
		createUser(asRfq, supplier, "SUPPLIER_RFQ", "SUPPLIER_QUALITY").andExpect(status().isCreated())
			.andExpect(jsonPath("$.user.companyType").value("SUPPLIER"))
			.andExpect(jsonPath("$.temporaryPassword").isNotEmpty());

		// ERGO employees and customer accounts are ERGO Admin's alone
		createUser(asRfq, ergo, "ERGO_NPD").andExpect(status().isForbidden());
		createUser(asRfq, ergo, "ERGO_ADMIN").andExpect(status().isForbidden());
		createUser(asRfq, customer, "CUSTOMER_RFQ").andExpect(status().isForbidden());
		// and only with supplier roles
		createUser(asRfq, supplier, "ERGO_ADMIN").andExpect(status().isUnprocessableContent());
		createUser(asRfq, supplier, "CUSTOMER_RFQ").andExpect(status().isUnprocessableContent());
		createUser(asRfq, supplier, "ERGO_NPD").andExpect(status().isUnprocessableContent());
	}

	@Test
	void everyOtherRoleStillCannotCreateAccounts() throws Exception {
		for (Role role : new Role[] { Role.ERGO_MANAGEMENT, Role.ERGO_KAM, Role.ERGO_NPD, Role.ERGO_OFP,
				Role.ERGO_QUALITY }) {
			createUser(accounts.bearer(accounts.user(ergo, role)), supplier, "SUPPLIER_RFQ")
				.andExpect(status().isForbidden());
		}
		createUser(accounts.bearer(accounts.user(supplier, Role.SUPPLIER_KAM)), supplier, "SUPPLIER_RFQ")
			.andExpect(status().isForbidden());
		createUser(accounts.bearer(accounts.user(customer, Role.CUSTOMER_KAM)), customer, "CUSTOMER_RFQ")
			.andExpect(status().isForbidden());
	}

	@Test
	void ergoRfqCannotManageAnAccountAfterCreatingIt() throws Exception {
		User created = accounts.user(supplier, Role.SUPPLIER_RFQ);
		mvc.perform(json(put("/api/users/{id}/roles", created.id), "{\"roles\": [\"SUPPLIER_KAM\"]}", asRfq))
			.andExpect(status().isForbidden());
		mvc.perform(json(put("/api/users/{id}/active", created.id), "{\"active\": false}", asRfq))
			.andExpect(status().isForbidden());
		mvc.perform(post("/api/users/{id}/reset-password", created.id).header(HttpHeaders.AUTHORIZATION, asRfq))
			.andExpect(status().isForbidden());
		mvc.perform(deleteUser(created.id, asRfq))
			.andExpect(status().isForbidden());
	}

	@Test
	void noAccountsInACompanyThatIsNotApproved() throws Exception {
		long pending = JsonPath.<Number>read(mvc.perform(json(post("/api/companies"),
				"{\"code\": \"PEND-%d\", \"type\": \"SUPPLIER\", \"profile\": {\"name\": \"Pending Co\"}}"
					.formatted(SEQ.incrementAndGet()), asRfq))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.id").longValue();

		for (String token : new String[] { asAdmin, asRfq }) {
			mvc.perform(json(post("/api/users"), body(pending, "SUPPLIER_RFQ"), token))
				.andExpect(status().isUnprocessableContent());
		}
		mvc.perform(json(post("/api/companies/{id}/approve", pending), "{}", asManagement)).andExpect(status().isOk());
		mvc.perform(json(post("/api/users"), body(pending, "SUPPLIER_RFQ"), asRfq)).andExpect(status().isCreated());
	}

	@Test
	void deletingAnAccountHidesItEndsAccessAndKeepsTheAuditTrail() throws Exception {
		User target = accounts.user(supplier, Role.SUPPLIER_RFQ);
		MvcResult login = accounts.login(target);
		Cookie session = Accounts.sessionCookie(login);
		String accessToken = Accounts.bearer(login);
		mvc.perform(get("/api/companies").header(HttpHeaders.AUTHORIZATION, accessToken)).andExpect(status().isOk());

		mvc.perform(deleteUser(target.id, asAdmin)).andExpect(status().isNoContent());

		// gone for everyone: lists, details, further changes
		mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, asAdmin))
			.andExpect(jsonPath("$[*].id", not(hasItem(target.id.intValue()))));
		mvc.perform(get("/api/users/{id}", target.id).header(HttpHeaders.AUTHORIZATION, asAdmin))
			.andExpect(status().isNotFound());
		mvc.perform(json(put("/api/users/{id}/active", target.id), "{\"active\": true}", asAdmin))
			.andExpect(status().isNotFound());
		mvc.perform(deleteUser(target.id, asAdmin))
			.andExpect(status().isNotFound());
		// no access any more: not even with the access token already issued, and the session cannot be refreshed
		mvc.perform(get("/api/companies").header(HttpHeaders.AUTHORIZATION, accessToken))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/refresh").cookie(session)).andExpect(status().isUnauthorized());
		accounts.login(target.email, Accounts.PASSWORD).andExpect(status().isUnauthorized());

		// the row and its history stay
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE user_id = ? AND deleted_at IS NOT NULL",
				Long.class, target.id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_logs
				WHERE entity_name = 'User' AND record_id = ? AND event_type = 'USER_DELETED'""", Long.class,
				target.id)).isEqualTo(1);
		// and the reason is stored with it
		assertThat(jdbc.queryForObject("""
				SELECT action_reason FROM audit_logs
				WHERE entity_name = 'User' AND record_id = ? AND event_type = 'USER_DELETED'""", String.class,
				target.id)).isEqualTo("Left the company");
	}

	@Test
	void deletingNeedsAReason() throws Exception {
		User target = accounts.user(supplier, Role.SUPPLIER_RFQ);
		for (String body : new String[] { "{}", "{\"reason\": \"  \"}" }) {
			mvc.perform(json(post("/api/users/{id}/delete", target.id), body, asAdmin)).andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/users/{id}", target.id).header(HttpHeaders.AUTHORIZATION, asAdmin))
			.andExpect(status().isOk());
	}

	@Test
	void deactivatingAUserAlsoEndsTheirIssuedAccessToken() throws Exception {
		User target = accounts.user(supplier, Role.SUPPLIER_RFQ);
		String accessToken = accounts.bearer(target);
		mvc.perform(get("/api/companies").header(HttpHeaders.AUTHORIZATION, accessToken)).andExpect(status().isOk());
		mvc.perform(json(put("/api/users/{id}/active", target.id), "{\"active\": false}", asAdmin))
			.andExpect(status().isOk());
		mvc.perform(get("/api/companies").header(HttpHeaders.AUTHORIZATION, accessToken))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void userActionsAreListedForErgoAdminOnlyAndNeverForYourself() throws Exception {
		User other = accounts.user(supplier, Role.SUPPLIER_RFQ);
		User admin = accounts.user(ergo, Role.ERGO_ADMIN);
		String adminToken = accounts.bearer(admin);
		mvc.perform(get("/api/users/{id}", other.id).header(HttpHeaders.AUTHORIZATION, adminToken))
			.andExpect(jsonPath("$.actions", containsInAnyOrder("DELETE", "DEACTIVATE", "LOGIN_AS")));
		mvc.perform(get("/api/users/{id}", admin.id).header(HttpHeaders.AUTHORIZATION, adminToken))
			.andExpect(jsonPath("$.actions", empty()));
		mvc.perform(get("/api/users/{id}", other.id).header(HttpHeaders.AUTHORIZATION, asRfq))
			.andExpect(jsonPath("$.actions", empty()));
		mvc.perform(json(put("/api/users/{id}/active", other.id), "{\"active\": false}", adminToken));
		mvc.perform(get("/api/users/{id}", other.id).header(HttpHeaders.AUTHORIZATION, adminToken))
			.andExpect(jsonPath("$.actions", containsInAnyOrder("DELETE", "ACTIVATE")));
	}

	@Test
	void onlyErgoAdminDeletesAndNobodyDeletesThemselves() throws Exception {
		User target = accounts.user(customer, Role.CUSTOMER_RFQ);
		for (Role role : new Role[] { Role.ERGO_MANAGEMENT, Role.ERGO_RFQ, Role.ERGO_KAM }) {
			mvc.perform(deleteUser(target.id, accounts.bearer(accounts.user(ergo, role))))
				.andExpect(status().isForbidden());
		}
		mvc.perform(deleteUser(target.id, accounts.bearer(accounts.user(customer, Role.CUSTOMER_KAM))))
			.andExpect(status().isForbidden());

		User admin = accounts.user(ergo, Role.ERGO_ADMIN);
		mvc.perform(deleteUser(admin.id, accounts.bearer(admin)))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(get("/api/users/{id}", target.id).header(HttpHeaders.AUTHORIZATION, asAdmin))
			.andExpect(status().isOk());
	}

	private static MockHttpServletRequestBuilder deleteUser(long id, String token) {
		return json(post("/api/users/{id}/delete", id), "{\"reason\": \"Left the company\"}", token);
	}

	private ResultActions createUser(String token, Company company, String... roles) throws Exception {
		return mvc.perform(json(post("/api/users"), body(company.id, roles), token));
	}

	private static String body(long companyId, String... roles) {
		int n = SEQ.incrementAndGet();
		String list = String.join("\", \"", roles);
		return """
				{"companyId": %d, "firstName": "New", "lastName": "Account", "email": "new.account%d@test.dev",
				 "roles": ["%s"]}""".formatted(companyId, n, list);
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(body);
	}

}
