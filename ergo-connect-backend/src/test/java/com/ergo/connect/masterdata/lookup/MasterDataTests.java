package com.ergo.connect.masterdata.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** SRS §10.12: ERGO Admin maintains master lists, ERGO Management views them, everyone reads active options. */
@IntegrationTest
class MasterDataTests {

	private static final AtomicInteger SEQ = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	String asAdmin;

	String asManagement;

	String asErgoRfq;

	String asSupplier;

	@BeforeEach
	void setUp() throws Exception {
		asAdmin = signIn("ERGO", "ERGO_ADMIN");
		asManagement = signIn("ERGO", "ERGO_MANAGEMENT");
		asErgoRfq = signIn("ERGO", "ERGO_RFQ");
		asSupplier = signIn("SUPPLIER", "SUPPLIER_RFQ");
	}

	@Test
	void currenciesAndIncotermsAreSeeded() throws Exception {
		mvc.perform(get("/api/master-data/currencies/options").header(HttpHeaders.AUTHORIZATION, asSupplier))
			.andExpect(jsonPath("$[*].code", hasItems("INR", "USD", "EUR", "RMB")));
		mvc.perform(get("/api/master-data/incoterms/options").header(HttpHeaders.AUTHORIZATION, asSupplier))
			.andExpect(jsonPath("$[*].code", hasItems("EXW", "FOB", "CIF", "DDP")))
			.andExpect(jsonPath("$", hasSize(11)));
	}

	@Test
	void adminMaintainsListsAndEveryChangeIsAudited() throws Exception {
		String code = "mat-" + SEQ.incrementAndGet();
		String created = mvc.perform(json(post("/api/master-data/materials"), """
				{"code": "%s", "name": "Test steel", "fields": {"specification": "IS 2062", "grade": "E250"}}"""
			.formatted(code), asAdmin))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value(code.toUpperCase()))
			.andExpect(jsonPath("$.fields.grade").value("E250"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long id = JsonPath.<Number>read(created, "$.id").longValue();

		mvc.perform(json(post("/api/master-data/materials"), """
				{"code": "%s", "name": "Duplicate"}""".formatted(code), asAdmin))
			.andExpect(status().isConflict());
		mvc.perform(json(put("/api/master-data/materials/{id}", id), """
				{"code": "%s", "name": "Test steel", "active": false,
				 "fields": {"specification": "IS 2062", "grade": "E350"}}""".formatted(code), asAdmin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));

		// inactive entries stay in the admin list but leave the dropdowns
		mvc.perform(get("/api/master-data/materials").header(HttpHeaders.AUTHORIZATION, asAdmin))
			.andExpect(jsonPath("$[*].code", hasItem(code.toUpperCase())));
		mvc.perform(get("/api/master-data/materials/options").header(HttpHeaders.AUTHORIZATION, asErgoRfq))
			.andExpect(jsonPath("$[*].code", not(hasItem(code.toUpperCase()))));

		List<String> trail = jdbc.queryForList("""
				SELECT event_type || ':' || coalesce(field_name, '') || ':' || coalesce(old_value, '') || ':'
				       || coalesce(new_value, '') || ':' || changed_by_role_code
				FROM audit_logs WHERE entity_name = 'Material' AND record_id = ? ORDER BY audit_log_id""", String.class,
				id);
		assertThat(trail).containsExactly("CREATE::::ERGO_ADMIN", "UPDATE:active:true:false:ERGO_ADMIN",
				"UPDATE:grade:E250:E350:ERGO_ADMIN");
	}

	@Test
	void fieldsAreValidatedAgainstTheList() throws Exception {
		mvc.perform(json(post("/api/master-data/processes"), """
				{"code": "P-%d", "name": "No type"}""".formatted(SEQ.incrementAndGet()), asAdmin))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(json(post("/api/master-data/processes"), """
				{"code": "P-%d", "name": "Bad type", "fields": {"processType": "SOMETIMES"}}"""
			.formatted(SEQ.incrementAndGet()), asAdmin))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(json(post("/api/master-data/materials"), """
				{"code": "M-%d", "name": "Unknown field", "fields": {"colour": "red"}}""".formatted(SEQ.incrementAndGet()),
				asAdmin))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(json(post("/api/master-data/processes"), """
				{"code": "P-%d", "name": "Milling", "fields": {"processType": "INHOUSE"}}"""
			.formatted(SEQ.incrementAndGet()), asAdmin))
			.andExpect(status().isCreated());
	}

	@Test
	void onlyErgoAdminMaintainsWhileEveryoneReadsOptions() throws Exception {
		String entry = """
				{"code": "PT-%d", "name": "Net 45"}""".formatted(SEQ.incrementAndGet());

		mvc.perform(get("/api/master-data").header(HttpHeaders.AUTHORIZATION, asManagement))
			.andExpect(jsonPath("$[*].key", hasItems("currencies", "materials", "payment-terms")));
		mvc.perform(get("/api/master-data/payment-terms").header(HttpHeaders.AUTHORIZATION, asManagement))
			.andExpect(status().isOk());
		for (String token : new String[] { asManagement, asErgoRfq, asSupplier }) {
			mvc.perform(json(post("/api/master-data/payment-terms"), entry, token)).andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/master-data/payment-terms").header(HttpHeaders.AUTHORIZATION, asErgoRfq))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/master-data/payment-terms/options").header(HttpHeaders.AUTHORIZATION, asSupplier))
			.andExpect(status().isOk());
		mvc.perform(get("/api/master-data/no-such-list/options").header(HttpHeaders.AUTHORIZATION, asSupplier))
			.andExpect(status().isNotFound());
	}

	/** A fresh company + user with this role, signed in (password set directly for the test). */
	private String signIn(String companyType, String role) throws Exception {
		int n = SEQ.incrementAndGet();
		long company = jdbc.queryForObject("""
				INSERT INTO companies (company_code, company_name, company_type, status)
				VALUES (?, ?, ?, 'ACTIVE') RETURNING company_id""", Long.class, "MD" + n, "MD test " + n, companyType);
		long user = jdbc.queryForObject("""
				INSERT INTO users (company_id, first_name, last_name, email, password_hash, must_change_password,
				                   default_role_code)
				VALUES (?, 'Master', 'Data', ?, ?, false, ?) RETURNING user_id""", Long.class, company,
				"md" + n + "@test.dev", "{noop}Master-data-9", role);
		jdbc.update("INSERT INTO user_role_mappings (user_id, role_code) VALUES (?, ?)", user, role);
		String session = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "md%d@test.dev", "password": "Master-data-9"}""".formatted(n)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return "Bearer " + JsonPath.read(session, "$.accessToken");
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(body);
	}

}
