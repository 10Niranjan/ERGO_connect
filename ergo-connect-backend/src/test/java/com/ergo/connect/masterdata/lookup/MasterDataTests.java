package com.ergo.connect.masterdata.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
import com.ergo.connect.TestUsers;
import com.ergo.connect.masterdata.api.MasterDataApi;
import com.jayway.jsonpath.JsonPath;

/** SRS §10.12: ERGO Admin maintains master lists, ERGO Management views them, everyone reads active options. */
@IntegrationTest
class MasterDataTests {

	private static final AtomicInteger SEQ = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MasterDataApi masterDataApi;

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

	@Test
	void exchangeRatesApplyFromTheirDateInBothDirections() throws Exception {
		// fresh currencies: rates are global and other tests convert with the seeded ones
		TestUsers data = new TestUsers(jdbc, mvc);
		long x = data.masterEntry("currencies", "currency");
		long y = data.masterEntry("currencies", "currency");
		String rate = """
				{"fromCurrencyId": %d, "toCurrencyId": %d, "rateDate": "%s", "rate": %s, "source": "Test bank"}""";

		for (String token : new String[] { asManagement, asErgoRfq, asSupplier }) {
			mvc.perform(json(post("/api/master-data/exchange-rates"), rate.formatted(x, y, "2026-01-10", "2"), token))
				.andExpect(status().isForbidden());
		}
		mvc.perform(json(post("/api/master-data/exchange-rates"), rate.formatted(x, x, "2026-01-10", "1"), asAdmin))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(json(post("/api/master-data/exchange-rates"), rate.formatted(x, y, "2026-01-10", "-2"), asAdmin))
			.andExpect(status().isBadRequest());
		mvc.perform(json(post("/api/master-data/exchange-rates"), rate.formatted(x, y, "2026-01-10", "2"), asAdmin))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.rate").value(2));
		mvc.perform(json(post("/api/master-data/exchange-rates"), rate.formatted(x, y, "2026-01-10", "3"), asAdmin))
			.andExpect(status().isConflict());
		long later = JsonPath.<Number>read(mvc
			.perform(json(post("/api/master-data/exchange-rates"), rate.formatted(x, y, "2026-02-01", "2.5"), asAdmin))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.id").longValue();

		assertThat(masterDataApi.rate(x, y, LocalDate.parse("2025-12-31"))).isEmpty();
		assertThat(masterDataApi.rate(x, y, LocalDate.parse("2026-01-20"))).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("2"));
		assertThat(masterDataApi.rate(x, y, LocalDate.parse("2026-03-01"))).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("2.5"));
		assertThat(masterDataApi.rate(y, x, LocalDate.parse("2026-03-01"))).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("0.4"));
		assertThat(masterDataApi.rate(x, x, LocalDate.parse("2026-03-01"))).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("1"));

		// the newest rate wins, whichever way round it was entered
		mvc.perform(json(post("/api/master-data/exchange-rates"), rate.formatted(y, x, "2026-02-15", "0.5"), asAdmin))
			.andExpect(status().isCreated());
		assertThat(masterDataApi.rate(x, y, LocalDate.parse("2026-03-01"))).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("2"));

		mvc.perform(get("/api/master-data/exchange-rates").header(HttpHeaders.AUTHORIZATION, asManagement))
			.andExpect(status().isOk());
		mvc.perform(get("/api/master-data/exchange-rates").header(HttpHeaders.AUTHORIZATION, asErgoRfq))
			.andExpect(status().isForbidden());
		mvc.perform(delete("/api/master-data/exchange-rates/{id}", later).header(HttpHeaders.AUTHORIZATION, asManagement))
			.andExpect(status().isForbidden());
		mvc.perform(delete("/api/master-data/exchange-rates/{id}", later).header(HttpHeaders.AUTHORIZATION, asAdmin))
			.andExpect(status().isNoContent());
		assertThat(jdbc.queryForList("""
				SELECT event_type || ':' || coalesce(old_value, '') FROM audit_logs
				WHERE entity_name = 'ExchangeRate' AND record_id = ? ORDER BY audit_log_id""", String.class, later))
			.containsExactly("CREATE:", "DELETE:1 %s = 2.5 %s from 2026-02-01".formatted(code(x), code(y)));
	}

	private String code(long currencyId) {
		return jdbc.queryForObject("SELECT currency_code FROM currencies WHERE currency_id = ?", String.class, currencyId);
	}

	/** A fresh company + user with this role, signed in. */
	private String signIn(String companyType, String role) throws Exception {
		TestUsers users = new TestUsers(jdbc, mvc);
		return users.login(users.company(companyType), role).bearer();
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(body);
	}

}
