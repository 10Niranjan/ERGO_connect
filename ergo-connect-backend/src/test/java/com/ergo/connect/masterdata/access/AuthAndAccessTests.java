package com.ergo.connect.masterdata.access;

import static com.ergo.connect.common.security.CompanyType.CUSTOMER;
import static com.ergo.connect.common.security.CompanyType.ERGO;
import static com.ergo.connect.common.security.CompanyType.SUPPLIER;
import static com.ergo.connect.masterdata.access.Accounts.PASSWORD;
import static com.ergo.connect.masterdata.access.Accounts.bearer;
import static com.ergo.connect.masterdata.access.Accounts.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.security.Role;

import jakarta.servlet.http.Cookie;

/** Layer 1 (login, role) and layer 2 (record scope) of the access model, with 2 customers and 2 suppliers. */
@IntegrationTest
class AuthAndAccessTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	CompanyRepository companies;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@Autowired
	JdbcTemplate jdbc;

	Accounts accounts;

	Company ergo;

	Company customerA;

	Company customerB;

	Company supplierX;

	Company supplierY;

	@BeforeEach
	void setUp() {
		accounts = new Accounts(companies, users, encoder, mvc);
		ergo = accounts.company(ERGO);
		customerA = accounts.company(CUSTOMER);
		customerB = accounts.company(CUSTOMER);
		supplierX = accounts.company(SUPPLIER);
		supplierY = accounts.company(SUPPLIER);
	}

	@Test
	void loginStartsInTheDefaultRoleWithAHardenedSessionCookie() throws Exception {
		User user = accounts.user(customerA, Role.CUSTOMER_QUALITY, Role.CUSTOMER_RFQ);

		accounts.login(user.email, PASSWORD)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activeRole").value("CUSTOMER_QUALITY"))
			.andExpect(jsonPath("$.roles[*].code", containsInAnyOrder("CUSTOMER_RFQ", "CUSTOMER_QUALITY")))
			.andExpect(jsonPath("$.user.companyName").value(customerA.name))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/auth")));
	}

	@Test
	void wrongPasswordAndUnknownEmailGiveTheSameAnswer() throws Exception {
		User user = accounts.user(customerA, Role.CUSTOMER_RFQ);

		String wrongPassword = accounts.login(user.email, "not-the-password")
			.andExpect(status().isUnauthorized())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String unknownEmail = accounts.login("nobody@test.dev", "not-the-password")
			.andExpect(status().isUnauthorized())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(wrongPassword).isEqualTo(unknownEmail);
	}

	@Test
	void fiveWrongPasswordsLockTheAccount() throws Exception {
		User user = accounts.user(supplierX, Role.SUPPLIER_RFQ);
		for (int i = 0; i < 5; i++) {
			accounts.login(user.email, "not-the-password").andExpect(status().isUnauthorized());
		}

		accounts.login(user.email, PASSWORD).andExpect(status().isUnauthorized());
	}

	@Test
	void aTemporaryPasswordMustBeChangedBeforeAnythingElse() throws Exception {
		User user = accounts.user(customerA, Role.CUSTOMER_RFQ);
		user.mustChangePassword = true;
		users.save(user);
		MvcResult login = accounts.login(user);
		String token = bearer(login);

		mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
		mvc.perform(json(post("/api/auth/switch-role"), "{\"role\": \"CUSTOMER_RFQ\"}", token))
			.andExpect(status().isForbidden());

		MvcResult changed = mvc
			.perform(json(post("/api/auth/change-password"),
					"{\"currentPassword\": \"%s\", \"newPassword\": \"A-brand-new-pass-42\"}".formatted(PASSWORD),
					token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mustChangePassword").value(false))
			.andReturn();
		mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, bearer(changed))).andExpect(status().isOk());
	}

	@Test
	void usersSwitchOnlyBetweenTheirOwnRolesAndRefreshKeepsTheChoice() throws Exception {
		User user = accounts.user(ergo, Role.ERGO_RFQ, Role.ERGO_NPD);
		MvcResult login = accounts.login(user);

		mvc.perform(json(post("/api/auth/switch-role"), "{\"role\": \"ERGO_NPD\"}", bearer(login)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activeRole").value("ERGO_NPD"));
		mvc.perform(json(post("/api/auth/switch-role"), "{\"role\": \"ERGO_ADMIN\"}", bearer(login)))
			.andExpect(status().isForbidden());

		mvc.perform(post("/api/auth/refresh").cookie(sessionCookie(login)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activeRole").value("ERGO_NPD"));
	}

	@Test
	void logoutEndsTheSession() throws Exception {
		Cookie session = sessionCookie(accounts.login(accounts.user(customerB, Role.CUSTOMER_RFQ)));

		mvc.perform(post("/api/auth/logout").cookie(session)).andExpect(status().isNoContent());

		mvc.perform(post("/api/auth/refresh").cookie(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void deactivatingAUserEndsTheirSessionsAndBlocksLogin() throws Exception {
		String admin = accounts.bearer(accounts.user(ergo, Role.ERGO_ADMIN));
		User user = accounts.user(customerA, Role.CUSTOMER_RFQ);
		Cookie session = sessionCookie(accounts.login(user));

		mvc.perform(json(put("/api/users/{id}/active", user.id), "{\"active\": false}", admin))
			.andExpect(status().isOk());

		mvc.perform(post("/api/auth/refresh").cookie(session)).andExpect(status().isUnauthorized());
		accounts.login(user.email, PASSWORD).andExpect(status().isUnauthorized());
	}

	@Test
	void customersAndSuppliersSeeOnlyTheirOwnCompany() throws Exception {
		User a1 = accounts.user(customerA, Role.CUSTOMER_RFQ);
		User a2 = accounts.user(customerA, Role.CUSTOMER_QUALITY);
		User b1 = accounts.user(customerB, Role.CUSTOMER_RFQ);
		User x1 = accounts.user(supplierX, Role.SUPPLIER_RFQ);
		User y1 = accounts.user(supplierY, Role.SUPPLIER_KAM);
		String asCustomerA = accounts.bearer(a1);
		String asSupplierX = accounts.bearer(x1);

		mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, asCustomerA))
			.andExpect(jsonPath("$[*].id", containsInAnyOrder(a1.id.intValue(), a2.id.intValue())));
		mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, asSupplierX))
			.andExpect(jsonPath("$[*].id", containsInAnyOrder(x1.id.intValue())));

		// Other companies' records don't exist for them: 404, not 403
		for (User other : new User[] { b1, x1, y1 }) {
			mvc.perform(get("/api/users/{id}", other.id).header(HttpHeaders.AUTHORIZATION, asCustomerA))
				.andExpect(status().isNotFound());
		}
		for (User other : new User[] { a1, b1, y1 }) {
			mvc.perform(get("/api/users/{id}", other.id).header(HttpHeaders.AUTHORIZATION, asSupplierX))
				.andExpect(status().isNotFound());
		}
	}

	@Test
	void ergoRolesSeeUsersOfEveryCompany() throws Exception {
		User a1 = accounts.user(customerA, Role.CUSTOMER_RFQ);
		User x1 = accounts.user(supplierX, Role.SUPPLIER_RFQ);

		mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION,
					accounts.bearer(accounts.user(ergo, Role.ERGO_QUALITY))))
			.andExpect(jsonPath("$[*].id", hasItems(a1.id.intValue(), x1.id.intValue())));
	}

	@Test
	void onlyErgoAdminManagesUsers() throws Exception {
		User target = accounts.user(customerA, Role.CUSTOMER_RFQ);
		String newUser = """
				{"companyId": %d, "firstName": "New", "lastName": "User", "email": "new.user@test.dev",
				 "roles": ["CUSTOMER_RFQ"]}""".formatted(customerA.id);

		for (Role role : new Role[] { Role.ERGO_MANAGEMENT, Role.ERGO_RFQ, Role.ERGO_KAM }) {
			assertAdminOnly(accounts.bearer(accounts.user(ergo, role)), target, newUser);
		}
		assertAdminOnly(accounts.bearer(accounts.user(customerA, Role.CUSTOMER_KAM)), target, newUser);
		assertAdminOnly(accounts.bearer(accounts.user(supplierX, Role.SUPPLIER_KAM)), target, newUser);
	}

	private void assertAdminOnly(String token, User target, String newUser) throws Exception {
		mvc.perform(json(post("/api/users"), newUser, token)).andExpect(status().isForbidden());
		mvc.perform(json(put("/api/users/{id}/roles", target.id), "{\"roles\": [\"CUSTOMER_KAM\"]}", token))
			.andExpect(status().isForbidden());
		mvc.perform(json(put("/api/users/{id}/active", target.id), "{\"active\": false}", token))
			.andExpect(status().isForbidden());
		mvc.perform(post("/api/users/{id}/reset-password", target.id).header(HttpHeaders.AUTHORIZATION, token))
			.andExpect(status().isForbidden());
		mvc.perform(json(put("/api/users/{id}/accounts", target.id), "{\"customerIds\": []}", token))
			.andExpect(status().isForbidden());
	}

	@Test
	void rolesMustMatchTheCompanyTypeInTheApiAndTheDatabase() throws Exception {
		String admin = accounts.bearer(accounts.user(ergo, Role.ERGO_ADMIN));
		String body = """
				{"companyId": %d, "firstName": "Wrong", "lastName": "Role", "email": "wrong.role%d@test.dev",
				 "roles": ["%s"]}""";

		mvc.perform(json(post("/api/users"), body.formatted(customerA.id, 1, "SUPPLIER_RFQ"), admin))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(json(post("/api/users"), body.formatted(customerA.id, 2, "ERGO_ADMIN"), admin))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(json(post("/api/users"), body.formatted(customerA.id, 3, "CUSTOMER_MANAGEMENT"), admin))
			.andExpect(status().isUnprocessableContent()); // inactive in MVP

		User customer = accounts.user(customerA, Role.CUSTOMER_RFQ);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO user_role_mappings (user_id, role_code) VALUES (?, 'ERGO_ADMIN')", customer.id))
			.hasMessageContaining("does not match");
	}

	@Test
	void aForgedTokenIsRejected() throws Exception {
		String[] parts = accounts.bearer(accounts.user(customerA, Role.CUSTOMER_RFQ)).substring(7).split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
			.replace("CUSTOMER_RFQ", "ERGO_ADMIN")
			.replace("\"CUSTOMER\"", "\"ERGO\"");
		String forged = parts[0] + "."
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
				+ "." + parts[2];

		mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void adminsCannotLockThemselvesOut() throws Exception {
		User admin = accounts.user(ergo, Role.ERGO_ADMIN);
		String token = accounts.bearer(admin);

		mvc.perform(json(put("/api/users/{id}/active", admin.id), "{\"active\": false}", token))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(json(put("/api/users/{id}/roles", admin.id), "{\"roles\": [\"ERGO_RFQ\"]}", token))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void anAdminCreatedUserStartsWithATemporaryPassword() throws Exception {
		String admin = accounts.bearer(accounts.user(ergo, Role.ERGO_ADMIN));
		MvcResult created = mvc.perform(json(post("/api/users"), """
				{"companyId": %d, "firstName": "Priya", "lastName": "Menon", "email": "Priya.Menon@Test.dev",
				 "roles": ["SUPPLIER_QUALITY", "SUPPLIER_RFQ"], "defaultRole": "SUPPLIER_RFQ"}"""
			.formatted(supplierY.id), admin))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.user.email").value("priya.menon@test.dev"))
			.andReturn();
		String temporaryPassword = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(),
				"$.temporaryPassword");

		accounts.login("priya.menon@test.dev", temporaryPassword)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mustChangePassword").value(true))
			.andExpect(jsonPath("$.activeRole").value("SUPPLIER_RFQ"));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body,
			String bearer) {
		return request.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(body);
	}

}
