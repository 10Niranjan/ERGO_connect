package com.ergo.connect.admin;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/** Per-side theme: Super Admin only writes, users read only their own side's published theme, bad colours refused. */
@IntegrationTest
class ThemeAdminTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	Login admin;

	Login management;

	Login customer;

	Login supplier;

	@BeforeEach
	void setUp() throws Exception {
		TestUsers users = new TestUsers(jdbc, mvc);
		admin = users.login(users.company("ERGO"), "ERGO_ADMIN");
		management = users.login(users.company("ERGO"), "ERGO_MANAGEMENT");
		customer = users.login(users.company("CUSTOMER"), "CUSTOMER_KAM");
		supplier = users.login(users.company("SUPPLIER"), "SUPPLIER_QUALITY");
	}

	@AfterEach
	void clean() {
		jdbc.update("UPDATE theme_settings SET draft_config = NULL, published_config = NULL, version = 0");
	}

	private static String mode(String primary) {
		return """
				{"primary":"%s","headerBg":"#ffffff","headerText":"#101828","navBg":"#0b1020","navText":"#e5e7eb",
				 "navActiveBg":"#1f2a44","navActiveText":"#ffffff"}""".formatted(primary);
	}

	private static String config(String lightPrimary) {
		return "{\"light\":%s,\"dark\":%s}".formatted(mode(lightPrimary), mode("#6690f5"));
	}

	private static String draft(long version, String lightPrimary) {
		return "{\"expectedVersion\":%d,\"config\":%s}".formatted(version, config(lightPrimary));
	}

	private long version(String side) throws Exception {
		String body = mvc.perform(get("/api/admin/themes/" + side).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.version")).longValue();
	}

	@Test
	void onlySuperAdminMayReadOrWriteThemes() throws Exception {
		for (Login other : new Login[] { management, customer, supplier }) {
			mvc.perform(get("/api/admin/themes/SUPPLIER").header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(status().isForbidden());
			mvc.perform(put("/api/admin/themes/SUPPLIER/draft").header(HttpHeaders.AUTHORIZATION, other.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(draft(0, "#112233"))).andExpect(status().isForbidden());
			mvc.perform(post("/api/admin/themes/SUPPLIER/publish").header(HttpHeaders.AUTHORIZATION, other.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}")).andExpect(status().isForbidden());
			mvc.perform(post("/api/admin/themes/SUPPLIER/reset").header(HttpHeaders.AUTHORIZATION, other.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}")).andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/admin/themes/SUPPLIER")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/theme")).andExpect(status().isUnauthorized());
	}

	@Test
	void superAdminRoleHeldButNotActiveIsRefused() throws Exception {
		TestUsers users = new TestUsers(jdbc, mvc);
		Login dual = users.login(users.company("ERGO"), "ERGO_MANAGEMENT", "ERGO_ADMIN"); // signs in as management
		mvc.perform(put("/api/admin/themes/SUPPLIER/draft").header(HttpHeaders.AUTHORIZATION, dual.bearer())
			.contentType(MediaType.APPLICATION_JSON).content(draft(0, "#112233"))).andExpect(status().isForbidden());
		org.assertj.core.api.Assertions.assertThat(version("SUPPLIER")).isZero();
	}

	@Test
	void draftIsPrivateUntilPublishedAndEachSideSeesOnlyItsOwn() throws Exception {
		mvc.perform(put("/api/admin/themes/SUPPLIER/draft").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content(draft(0, "#112233")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.draft.light.primary").value("#112233"))
			.andExpect(jsonPath("$.published").value(nullValue()))
			.andExpect(jsonPath("$.version").value(1));
		// a draft is invisible to the supplier
		mvc.perform(get("/api/theme").header(HttpHeaders.AUTHORIZATION, supplier.bearer()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.config").value(nullValue()));

		mvc.perform(post("/api/admin/themes/SUPPLIER/publish").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.draft").value(nullValue()));

		mvc.perform(get("/api/theme").header(HttpHeaders.AUTHORIZATION, supplier.bearer()))
			.andExpect(jsonPath("$.config.light.primary").value("#112233"));
		// other sides are untouched
		mvc.perform(get("/api/theme").header(HttpHeaders.AUTHORIZATION, customer.bearer()))
			.andExpect(jsonPath("$.config").value(nullValue()));
		mvc.perform(get("/api/theme").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(jsonPath("$.config").value(nullValue()));
	}

	@Test
	void badColoursAreRefusedAndNothingIsSaved() throws Exception {
		for (String bad : new String[] { "red", "#12345", "#gggggg", "url(javascript:alert(1))", "#112233; x:y" }) {
			mvc.perform(put("/api/admin/themes/ERGO/draft").header(HttpHeaders.AUTHORIZATION, admin.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(draft(0, bad))).andExpect(status().isBadRequest());
		}
		mvc.perform(put("/api/admin/themes/ERGO/draft").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\":0,\"config\":{\"light\":%s}}".formatted(mode("#112233"))))
			.andExpect(status().isBadRequest()); // dark palette missing
		org.assertj.core.api.Assertions.assertThat(version("ERGO")).isZero();
	}

	@Test
	void publishNeedsADraftAndStaleVersionsAreRefused() throws Exception {
		mvc.perform(post("/api/admin/themes/CUSTOMER/publish").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}")).andExpect(status().isUnprocessableEntity());
		mvc.perform(put("/api/admin/themes/CUSTOMER/draft").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content(draft(5, "#112233"))).andExpect(status().isConflict());
	}

	@Test
	void staleVersionIsRefusedOnPublishAndReset() throws Exception {
		mvc.perform(put("/api/admin/themes/CUSTOMER/draft").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content(draft(0, "#112233"))).andExpect(status().isOk());
		for (String action : new String[] { "publish", "reset" }) {
			mvc.perform(post("/api/admin/themes/CUSTOMER/" + action).header(HttpHeaders.AUTHORIZATION, admin.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}")).andExpect(status().isConflict());
		}
		org.assertj.core.api.Assertions.assertThat(version("CUSTOMER")).isEqualTo(1); // neither changed anything
	}

	@Test
	void corruptStoredThemeFallsBackToTheDefaultInsteadOfBreakingEveryone() throws Exception {
		jdbc.update("UPDATE theme_settings SET published_config = ? WHERE side = 'SUPPLIER'", "{not json");
		mvc.perform(get("/api/theme").header(HttpHeaders.AUTHORIZATION, supplier.bearer()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.config").value(nullValue()));
		jdbc.update("UPDATE theme_settings SET published_config = ? WHERE side = 'SUPPLIER'",
				"{\"light\":%s,\"dark\":%s}".formatted(mode("red;}body{display:none"), mode("#6690f5")));
		mvc.perform(get("/api/theme").header(HttpHeaders.AUTHORIZATION, supplier.bearer()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.config").value(nullValue()));
	}

	@Test
	void resetAuditsTheDiscardedDraftToo() throws Exception {
		mvc.perform(put("/api/admin/themes/ERGO/draft").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content(draft(0, "#112233"))).andExpect(status().isOk());
		mvc.perform(post("/api/admin/themes/ERGO/reset").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")).andExpect(status().isOk());
		Integer draftRows = jdbc.queryForObject("""
				SELECT count(*) FROM audit_logs WHERE entity_name = 'ThemeSettings' AND record_id = 1
				AND event_type = 'THEME_RESET' AND field_name = 'draft_config' AND old_value IS NOT NULL""", Integer.class);
		org.assertj.core.api.Assertions.assertThat(draftRows).isEqualTo(1);
	}

	@Test
	void resetReturnsToTheDefaultAndEveryChangeIsAudited() throws Exception {
		int before = customerThemeAuditRows();
		mvc.perform(put("/api/admin/themes/CUSTOMER/draft").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content(draft(0, "#112233"))).andExpect(status().isOk());
		mvc.perform(post("/api/admin/themes/CUSTOMER/publish").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")).andExpect(status().isOk());
		mvc.perform(post("/api/admin/themes/CUSTOMER/reset").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}")).andExpect(status().isOk());
		mvc.perform(get("/api/theme").header(HttpHeaders.AUTHORIZATION, customer.bearer()))
			.andExpect(jsonPath("$.config").value(nullValue()));
		int audited = customerThemeAuditRows() - before; // audit_logs is append-only, so count only this test's rows
		org.assertj.core.api.Assertions.assertThat(audited).isEqualTo(3);
	}

	private int customerThemeAuditRows() {
		return jdbc.queryForObject("""
				SELECT count(*) FROM audit_logs WHERE entity_name = 'ThemeSettings' AND record_id = 2
				AND event_type IN ('THEME_DRAFT_SAVED', 'THEME_PUBLISHED', 'THEME_RESET')""", Integer.class);
	}

}
