package com.ergo.connect.masterdata.access;

import static com.ergo.connect.common.security.CompanyType.CUSTOMER;
import static com.ergo.connect.common.security.CompanyType.ERGO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.security.Role;
import com.jayway.jsonpath.JsonPath;

/** SRS §15: who changed what, when, in which role, from which company, and never secrets. */
@IntegrationTest
class AuditTrailTests {

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

	@Autowired
	TransactionTemplate tx;

	Accounts accounts;

	Company ergo;

	Company customer;

	User admin;

	String adminToken;

	@BeforeEach
	void setUp() throws Exception {
		accounts = new Accounts(companies, users, encoder, mvc, otpCodes);
		ergo = accounts.company(ERGO);
		customer = accounts.company(CUSTOMER);
		admin = accounts.user(ergo, Role.ERGO_ADMIN);
		adminToken = accounts.bearer(admin);
	}

	@Test
	void userAdministrationIsAuditedWithActorRoleCompanyAndChannel() throws Exception {
		String created = mvc.perform(post("/api/users").header(HttpHeaders.AUTHORIZATION, adminToken)
			.header("X-Ergo-Client", "web")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"companyId": %d, "firstName": "Audit", "lastName": "Me", "email": "audit.me@test.dev",
					 "roles": ["CUSTOMER_RFQ"]}""".formatted(customer.id)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long userId = JsonPath.<Number>read(created, "$.user.id").longValue();
		mvc.perform(put("/api/users/{id}", userId).header(HttpHeaders.AUTHORIZATION, adminToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"firstName": "Audit", "lastName": "Me", "email": "audit.me@test.dev", "mobile": "+91 98450 00000"}"""))
			.andExpect(status().isOk());

		List<Map<String, Object>> rows = auditRows(userId);
		assertThat(rows).extracting(r -> r.get("event_type") + ":" + r.get("field_name") + ":" + r.get("new_value"))
			.containsExactly("CREATE:null:null", "ROLE_ADDED:roles:CUSTOMER_RFQ", "UPDATE:mobile:+91 98450 00000");
		assertThat(rows).allSatisfy(r -> {
			assertThat(((Number) r.get("changed_by_user_id")).longValue()).isEqualTo(admin.id);
			assertThat(r.get("changed_by_role_code")).isEqualTo("ERGO_ADMIN");
			assertThat(((Number) r.get("changed_by_company_id")).longValue()).isEqualTo(ergo.id);
		});
		assertThat(rows.get(0).get("source_channel")).isEqualTo("UI");
		assertThat(rows.get(2).get("source_channel")).isEqualTo("API");
	}

	@Test
	void passwordChangesAreAuditedButNeverTheirValues() throws Exception {
		User user = accounts.user(customer, Role.CUSTOMER_RFQ);
		accounts.login(user); // updates last-login and counters: noise that must stay out of the trail

		mvc.perform(post("/api/users/{id}/reset-password", user.id).header(HttpHeaders.AUTHORIZATION, adminToken))
			.andExpect(status().isOk());

		List<Map<String, Object>> rows = auditRows(user.id);
		assertThat(rows).extracting(r -> r.get("field_name") + ":" + r.get("old_value") + ":" + r.get("new_value"))
			.contains("passwordHash:***:***", "mustChangePassword:false:true")
			.noneMatch(r -> r.contains("{bcrypt}") || r.startsWith("lastLoginAt") || r.startsWith("failedLoginCount"));
	}

	@Test
	void auditRowsRollBackWithTheirTransaction() {
		long[] id = new long[1];
		tx.executeWithoutResult(status -> {
			id[0] = accounts.company(CUSTOMER).id;
			status.setRollbackOnly();
		});

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_logs WHERE entity_name = 'Company' AND record_id = ?", Long.class, id[0]))
			.isZero();
	}

	@Test
	void rolesTableMatchesTheRoleEnum() {
		Map<String, String> table = jdbc.query("SELECT role_code, role_type FROM roles", rs -> {
			Map<String, String> m = new java.util.HashMap<>();
			while (rs.next()) {
				m.put(rs.getString(1), rs.getString(2));
			}
			return m;
		});

		assertThat(table).isEqualTo(Arrays.stream(Role.values())
			.collect(Collectors.toMap(Role::name, r -> r.companyType().name())));
	}

	private List<Map<String, Object>> auditRows(long userId) {
		return jdbc.queryForList("""
				SELECT event_type, field_name, old_value, new_value, changed_by_user_id, changed_by_role_code,
				       changed_by_company_id, source_channel
				FROM audit_logs WHERE entity_name = 'User' AND record_id = ? ORDER BY audit_log_id""", userId);
	}

}
