package com.ergo.connect.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.ergo.connect.common.security.AccessPolicy;
import com.ergo.connect.common.security.DefaultAccessSheet;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * Module switches (O on, A no final approvals, V read-only, NV hidden), and the behaviours around the permission sheet
 * that Permission Management must guarantee: concurrent saves, revocation, reset on every side, ERGO Admin limits.
 * Every test restores the shipped sheet and switches (tests share one database).
 */
@IntegrationTest
class ModuleSwitchTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AccessPolicy policy;

	TestUsers users;

	Login admin;

	Login ergoRfq;

	Login ergoKam;

	Login customerRfq;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		admin = users.login(users.company("ERGO"), "ERGO_ADMIN");
		ergoRfq = users.login(users.company("ERGO"), "ERGO_RFQ");
		ergoKam = users.login(users.company("ERGO"), "ERGO_KAM");
		customerRfq = users.login(users.company("CUSTOMER"), "CUSTOMER_RFQ");
	}

	@AfterEach
	void restoreShippedSheet() {
		jdbc.update("UPDATE app_modules SET access_cap = 'O'");
		for (Role role : Role.values()) {
			for (Module module : Module.values()) {
				jdbc.update("UPDATE role_module_permissions SET access_level = ? WHERE role_code = ? AND module_code = ?",
						DefaultAccessSheet.code(role, module.name()), role.name(), module.name());
			}
		}
		jdbc.update("UPDATE access_config SET version = version + 1");
		policy.refresh();
	}

	// ---------------------------------------------------------------- the module switch

	@Test
	void aReadOnlySwitchStopsEveryoneCreatingAndApprovingButNobodyLosesView() throws Exception {
		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer())).andExpect(status().isOk());
		// before the switch: ERGO RFQ (O) passes layer 1 on validate (the id does not exist, so not 403)
		assertThat(statusOf(post("/api/rfqs/999999/validate").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"comment\": \"x\"}"))).isNotEqualTo(403);

		moduleSwitch("RFQ", "V", "freeze RFQ for the audit").andExpect(status().isOk())
			.andExpect(jsonPath("$.modules[?(@.code=='RFQ')].cap").value(hasItem("V")))
			.andExpect(jsonPath("$.roles[?(@.code=='ERGO_RFQ')].cells.RFQ.level").value(hasItem("O")))
			.andExpect(jsonPath("$.roles[?(@.code=='ERGO_RFQ')].cells.RFQ.effective").value(hasItem("V")));

		// everyone can still read ...
		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer())).andExpect(status().isOk());
		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, customerRfq.bearer())).andExpect(status().isOk());
		// ... nobody can create or approve
		mvc.perform(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, customerRfq.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"partNumber\": \"PN-1\", \"currencyId\": %d}".formatted(users.currency("INR"))))
			.andExpect(status().isForbidden());
		mvc.perform(post("/api/rfqs/999999/validate").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"comment\": \"x\"}")).andExpect(status().isForbidden());
		// the sidebar and permissions the SPA gets follow the switch
		mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer()))
			.andExpect(jsonPath("$.permissions.RFQ").value("V"))
			.andExpect(jsonPath("$.menu[*].items[*].label").value(hasItem("RFQs")));
	}

	@Test
	void aSwitchOfNoFinalApprovalsKeepsActingButRemovesApproving() throws Exception {
		moduleSwitch("RFQ", "A", "approvals paused").andExpect(status().isOk());
		mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer()))
			.andExpect(jsonPath("$.permissions.RFQ").value("A"));
		mvc.perform(post("/api/rfqs/999999/validate").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"comment\": \"x\"}")).andExpect(status().isForbidden());
		// acting is still allowed (create passes layer 1; it then needs a valid customer, so not 403)
		assertThat(statusOf(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, customerRfq.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"partNumber\": \"PN-1\", \"currencyId\": %d}".formatted(users.currency("INR"))))).isEqualTo(201);
	}

	@Test
	void aHiddenSwitchClosesTheModuleForEveryRoleAndSwitchingBackRestoresTheSheet() throws Exception {
		// a real RFQ of this customer: on its dashboard now, gone once the module is hidden
		mvc.perform(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, customerRfq.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"partNumber\": \"PN-D\", \"currencyId\": %d}".formatted(users.currency("INR"))))
			.andExpect(status().isCreated());
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, customerRfq.bearer()))
			.andExpect(jsonPath("$.rfqItems.length()").value(1));
		moduleSwitch("RFQ", "NV", "RFQ is being migrated").andExpect(status().isOk());
		for (Login who : List.of(ergoRfq, customerRfq, admin)) {
			mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, who.bearer())).andExpect(status().isForbidden());
			mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, who.bearer()))
				.andExpect(jsonPath("$.permissions.RFQ").value("NV"))
				.andExpect(jsonPath("$.menu[*].items[*].label").value(not(hasItem("RFQs"))));
		}
		// the dashboard builds nothing from a hidden module
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, customerRfq.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rfqItems.length()").value(0));

		moduleSwitch("RFQ", "O", "migration finished").andExpect(status().isOk());
		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer())).andExpect(status().isOk());
		mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, customerRfq.bearer()))
			.andExpect(jsonPath("$.permissions.RFQ").value("O")); // exactly what the sheet gave Customer RFQ
	}

	@Test
	void theSwitchRulesAreEnforcedByTheServer() throws Exception {
		long v = version();
		// fixed modules cannot be switched; unknown modules and levels are refused
		moduleSwitch("DOCUMENTS", "NV", "x").andExpect(status().isUnprocessableContent());
		moduleSwitch("USERS", "NV", "x").andExpect(status().isUnprocessableContent());
		moduleSwitch("NOPE", "V", "x").andExpect(status().isUnprocessableContent());
		moduleSwitch("RFQ", "CO", "x").andExpect(status().isUnprocessableContent());
		// a no-op, a missing reason, and a stale version
		moduleSwitch("RFQ", "O", "x").andExpect(status().isUnprocessableContent());
		moduleSwitch("RFQ", "V", " ").andExpect(status().isBadRequest());
		mvc.perform(put("/api/admin/permissions/ERGO/modules/RFQ").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\": %d, \"reason\": \"stale\", \"level\": \"V\"}".formatted(v - 1)))
			.andExpect(status().isConflict());
		assertThat(version()).isEqualTo(v);
		// only ERGO Admin
		for (Login other : List.of(ergoRfq, ergoKam, customerRfq)) {
			mvc.perform(put("/api/admin/permissions/ERGO/modules/RFQ").header(HttpHeaders.AUTHORIZATION, other.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\": %d, \"reason\": \"x\", \"level\": \"V\"}".formatted(v)))
				.andExpect(status().isForbidden());
		}
		assertThat(jdbc.queryForObject("SELECT access_cap FROM app_modules WHERE module_code = 'RFQ'", String.class))
			.isEqualTo("O");
	}

	@Test
	void aSwitchChangeIsAuditedAndShownInTheHistory() throws Exception {
		moduleSwitch("NPD", "V", "NPD read-only during the audit").andExpect(status().isOk());
		mvc.perform(get("/api/admin/permissions/history").param("module", "NPD")
			.header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].role").doesNotExist())
			.andExpect(jsonPath("$[0].module").value("NPD"))
			.andExpect(jsonPath("$[0].oldLevel").value("O"))
			.andExpect(jsonPath("$[0].newLevel").value("V"))
			.andExpect(jsonPath("$[0].reason").value("NPD read-only during the audit"))
			.andExpect(jsonPath("$[0].changedByRole").value("ERGO_ADMIN"));
	}

	// ---------------------------------------------------------------- the rest of the permission guarantees

	@Test
	void twoSimultaneousSavesFromTheSameVersionLetExactlyOneWin() throws Exception {
		long v = version();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Callable<Integer>> saves = List.of(
					() -> statusOf(save("CUSTOMER", v, "first admin", change("CUSTOMER_KAM", "NPD", "A"))),
					() -> statusOf(save("CUSTOMER", v, "second admin", change("CUSTOMER_KAM", "OFP", "A"))));
			List<Integer> results = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(saves)) {
				results.add(f.get());
			}
			assertThat(results).containsExactlyInAnyOrder(200, 409);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(version()).isEqualTo(v + 1); // one save, one bump
	}

	@Test
	void revokingAnApprovalTakesItAwayImmediatelyFromAnAlreadySignedInUser() throws Exception {
		assertThat(statusOf(post("/api/rfqs/999999/validate").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"comment\": \"x\"}"))).isNotEqualTo(403);
		save("ERGO", version(), "ERGO RFQ loses final approval", change("ERGO_RFQ", "RFQ", "A")).andExpect(status().isOk());
		mvc.perform(post("/api/rfqs/999999/validate").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"comment\": \"x\"}")).andExpect(status().isForbidden());
		// the role can still read and act, only the final approval went
		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer())).andExpect(status().isOk());
	}

	@Test
	void resetRestoresTheSignedOffSheetOnEverySideAndLeavesTheSwitchesAlone() throws Exception {
		save("ERGO", version(), "e", change("ERGO_KAM", "QUOTATION", "A"), change("ERGO_OFP", "RFQ", "V")).andExpect(status().isOk());
		save("CUSTOMER", version(), "c", change("CUSTOMER_KAM", "OFP", "A"), change("CUSTOMER_OFP", "NPD", "V"))
			.andExpect(status().isOk());
		save("SUPPLIER", version(), "s", change("SUPPLIER_QUALITY", "RFQ", "A"), change("SUPPLIER_KAM", "OFP", "O"))
			.andExpect(status().isOk());
		moduleSwitch("QUALITY", "V", "keep this switch").andExpect(status().isOk());

		for (String side : List.of("ERGO", "CUSTOMER", "SUPPLIER")) {
			mvc.perform(post("/api/admin/permissions/" + side + "/reset").header(HttpHeaders.AUTHORIZATION, admin.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\": %d, \"reason\": \"reset %s\"}".formatted(version(), side)))
				.andExpect(status().isOk());
		}
		for (Role role : Role.values()) {
			for (Module module : Module.values()) {
				assertThat(jdbc.queryForObject("SELECT access_level FROM role_module_permissions WHERE role_code = ? AND module_code = ?",
						String.class, role.name(), module.name())).as("%s on %s", role, module)
					.isEqualTo(DefaultAccessSheet.code(role, module.name()));
			}
		}
		assertThat(jdbc.queryForObject("SELECT access_cap FROM app_modules WHERE module_code = 'QUALITY'", String.class))
			.isEqualTo("V");
	}

	@Test
	void ergoAdminStaysViewOnlyOnBusinessModulesYetStillManagesAccountsAndPermissions() throws Exception {
		save("ERGO", version(), "x", change("ERGO_ADMIN", "RFQ", "A")).andExpect(status().isUnprocessableContent());
		save("ERGO", version(), "x", change("ERGO_ADMIN", "OFP", "O")).andExpect(status().isUnprocessableContent());
		save("ERGO", version(), "no RFQ for the admin", change("ERGO_ADMIN", "RFQ", "NV")).andExpect(status().isOk()); // NV is allowed
		mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(jsonPath("$.permissions.RFQ").value("NV"))
			.andExpect(jsonPath("$.permissions.USERS").value("O"))
			.andExpect(jsonPath("$.permissions.PERMISSIONS").value("O"))
			.andExpect(jsonPath("$.menu[*].items[*].label").value(hasItems("Users", "Permission Management", "Customers & suppliers")));
		// the admin cannot create business records ...
		mvc.perform(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"partNumber\": \"PN\", \"currencyId\": %d}".formatted(users.currency("INR"))))
			.andExpect(status().isForbidden());
		// ... but still manages accounts
		mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().isOk());
		mvc.perform(get("/api/companies").header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().isOk());
		mvc.perform(get("/api/admin/permissions/ERGO").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roles[*].cells.RFQ.allowed[0]").value(hasItems("NV")))
			.andExpect(jsonPath("$.roles[?(@.code=='ERGO_ADMIN')].cells.RFQ.allowed").value(contains(List.of("NV", "V"))));
	}

	// ---------------------------------------------------------------- helpers

	private long version() throws Exception {
		return JsonPath.<Number>read(mvc.perform(get("/api/admin/permissions/ERGO")
			.header(HttpHeaders.AUTHORIZATION, admin.bearer())).andReturn().getResponse().getContentAsString(), "$.version")
			.longValue();
	}

	private int statusOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse().getStatus();
	}

	private int statusOf(ResultActions actions) {
		return actions.andReturn().getResponse().getStatus();
	}

	private ResultActions moduleSwitch(String module, String level, String reason) throws Exception {
		return mvc.perform(put("/api/admin/permissions/ERGO/modules/" + module)
			.header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\": %d, \"reason\": \"%s\", \"level\": \"%s\"}".formatted(version(), reason, level)));
	}

	private ResultActions save(String side, long version, String reason, String... changes) throws Exception {
		return mvc.perform(put("/api/admin/permissions/" + side).header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\": %d, \"reason\": \"%s\", \"changes\": [%s]}".formatted(version, reason,
					String.join(",", changes))));
	}

	private static String change(String role, String module, String level) {
		return "{\"role\": \"%s\", \"module\": \"%s\", \"level\": \"%s\"}".formatted(role, module, level);
	}

}
