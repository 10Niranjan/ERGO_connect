package com.ergo.connect.admin;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

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
 * Permission Management (ERGO Admin edits the role-by-module sheet): who may, which edits the server refuses, that an
 * edit applies on the very next request, and that no level lets a role reach another company's records or an approval
 * it was not given. Every test restores the shipped sheet afterwards (tests share one database).
 */
@IntegrationTest
class PermissionAdminTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AccessPolicy policy;

	TestUsers users;

	Login admin;

	Login management;

	Login customerKam;

	Login supplierQuality;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		admin = users.login(users.company("ERGO"), "ERGO_ADMIN");
		management = users.login(users.company("ERGO"), "ERGO_MANAGEMENT");
		customerKam = users.login(users.company("CUSTOMER"), "CUSTOMER_KAM");
		supplierQuality = users.login(users.company("SUPPLIER"), "SUPPLIER_QUALITY");
	}

	@AfterEach
	void restoreShippedSheet() {
		for (Role role : Role.values()) {
			for (Module module : Module.values()) {
				jdbc.update("UPDATE role_module_permissions SET access_level = ? WHERE role_code = ? AND module_code = ?",
						DefaultAccessSheet.code(role, module.name()), role.name(), module.name());
			}
		}
		jdbc.update("UPDATE access_config SET version = version + 1");
		policy.refresh();
	}

	// ---------------------------------------------------------------- who may use it

	@Test
	void onlyErgoAdminMayReadOrChangeThePermissions() throws Exception {
		long version = version();
		String save = saveBody(version, "test", change("CUSTOMER_KAM", "RFQ", "V"));
		for (Login other : List.of(management, customerKam, supplierQuality)) {
			mvc.perform(get("/api/admin/permissions/CUSTOMER").header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(status().isForbidden());
			mvc.perform(put("/api/admin/permissions/CUSTOMER").header(HttpHeaders.AUTHORIZATION, other.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(save)).andExpect(status().isForbidden());
			mvc.perform(post("/api/admin/permissions/CUSTOMER/reset").header(HttpHeaders.AUTHORIZATION, other.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\": %d, \"reason\": \"x\"}".formatted(version))).andExpect(status().isForbidden());
			mvc.perform(get("/api/admin/permissions/history").header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/admin/permissions/CUSTOMER")).andExpect(status().isUnauthorized());
		org.assertj.core.api.Assertions.assertThat(version()).isEqualTo(version); // nothing was saved
	}

	@Test
	void theMatrixShowsRolesModulesAndWhatEachCellMayHold() throws Exception {
		mvc.perform(get("/api/admin/permissions/ERGO").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.side").value("ERGO"))
			.andExpect(jsonPath("$.roles.length()").value(7))
			.andExpect(jsonPath("$.modules[?(@.code=='RFQ')].configurable").value(hasItem(true)))
			.andExpect(jsonPath("$.modules[?(@.code=='DOCUMENTS')].configurable").value(hasItem(false)))
			.andExpect(jsonPath("$.roles[?(@.code=='ERGO_ADMIN')].cells.RFQ.level").value(hasItem("V")))
			.andExpect(jsonPath("$.roles[?(@.code=='ERGO_ADMIN')].cells.RFQ.allowed[*]").value(hasItems("NV", "V")))
			.andExpect(jsonPath("$.roles[?(@.code=='ERGO_RFQ')].cells.RFQ.allowed[*]").value(hasItems("NV", "V", "A", "O")))
			.andExpect(jsonPath("$.roles[?(@.code=='ERGO_RFQ')].cells.DOCUMENTS.editable").value(hasItem(false)));
		mvc.perform(get("/api/admin/permissions/SUPPLIER").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(jsonPath("$.roles.length()").value(5))
			.andExpect(jsonPath("$.roles[0].cells.RFQ.allowed[*]").value(hasItems("NV", "V", "A", "O")));
	}

	// ---------------------------------------------------------------- what the server refuses

	@Test
	void theServerRefusesUnsafeOrMeaninglessEdits() throws Exception {
		long v = version();
		// ERGO Admin stays view-only on business modules
		save("ERGO", v, "x", change("ERGO_ADMIN", "RFQ", "A")).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("view-only")));
		save("ERGO", v, "x", change("ERGO_ADMIN", "RFQ", "O")).andExpect(status().isUnprocessableContent());
		// a module that is still fixed in code cannot be edited
		save("ERGO", v, "x", change("ERGO_RFQ", "DOCUMENTS", "NV")).andExpect(status().isUnprocessableContent());
		save("ERGO", v, "x", change("ERGO_RFQ", "NO_SUCH_MODULE", "V")).andExpect(status().isUnprocessableContent());
		// the role must belong to the side being edited
		save("CUSTOMER", v, "x", change("ERGO_RFQ", "RFQ", "V")).andExpect(status().isUnprocessableContent());
		// unknown level, the old internal code, and a no-op
		save("ERGO", v, "x", change("ERGO_RFQ", "RFQ", "CO")).andExpect(status().isUnprocessableContent());
		save("ERGO", v, "x", change("ERGO_RFQ", "RFQ", "O")).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("Nothing to change")));
		// a reason is mandatory
		save("ERGO", v, " ", change("ERGO_RFQ", "RFQ", "V")).andExpect(status().isBadRequest());
		// the last ERGO owner of a module cannot be removed (ERGO Management and ERGO RFQ own RFQ)
		save("ERGO", v, "x", change("ERGO_MANAGEMENT", "RFQ", "A"), change("ERGO_RFQ", "RFQ", "V"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("At least one ERGO role")));
		// all-or-nothing: the valid first change was not applied either
		org.assertj.core.api.Assertions.assertThat(version()).isEqualTo(v);
		org.assertj.core.api.Assertions.assertThat(stored(Role.ERGO_MANAGEMENT, "RFQ")).isEqualTo("O");
	}

	@Test
	void aStaleVersionIsRefusedSoNobodyOverwritesAnotherAdmin() throws Exception {
		long v = version();
		save("CUSTOMER", v, "first", change("CUSTOMER_KAM", "NPD", "A")).andExpect(status().isOk());
		save("CUSTOMER", v, "second", change("CUSTOMER_KAM", "NPD", "NV")).andExpect(status().isConflict());
		org.assertj.core.api.Assertions.assertThat(stored(Role.CUSTOMER_KAM, "NPD")).isEqualTo("A");
	}

	// ---------------------------------------------------------------- an edit applies on the next request

	@Test
	void anEditChangesTheSidebarPermissionsAndTheApiOnTheNextRequestWithTheSameToken() throws Exception {
		// Customer KAM has no Quotation... it does (A); RFQ list is open to it. Take RFQ away, then give it back.
		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, customerKam.bearer())).andExpect(status().isOk());
		mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, customerKam.bearer()))
			.andExpect(jsonPath("$.permissions.RFQ").value("A"))
			.andExpect(jsonPath("$.menu[*].items[*].label").value(hasItem("RFQs")));

		save("CUSTOMER", version(), "Customer KAM must not see RFQs for now", change("CUSTOMER_KAM", "RFQ", "NV"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roles[?(@.code=='CUSTOMER_KAM')].cells.RFQ.level").value(hasItem("NV")));

		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, customerKam.bearer()))
			.andExpect(status().isForbidden())
			.andExpect(header().exists("X-Permissions-Version"));
		mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, customerKam.bearer()))
			.andExpect(jsonPath("$.permissions.RFQ").value("NV"))
			.andExpect(jsonPath("$.menu[*].items[*].label").value(not(hasItem("RFQs"))));

		save("CUSTOMER", version(), "Restore", change("CUSTOMER_KAM", "RFQ", "V")).andExpect(status().isOk());
		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, customerKam.bearer())).andExpect(status().isOk());
		// View only: creating an RFQ is refused
		mvc.perform(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, customerKam.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currencyId\": %d, \"items\": [{\"partNumber\": \"PN-1\"}]}".formatted(users.currency("INR")))).andExpect(status().isForbidden());
		save("CUSTOMER", version(), "Allow", change("CUSTOMER_KAM", "RFQ", "A")).andExpect(status().isOk());
		mvc.perform(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, customerKam.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currencyId\": %d, \"items\": [{\"partNumber\": \"PN-1\"}]}".formatted(users.currency("INR")))).andExpect(status().isCreated());
	}

	@Test
	void theSessionResponseAndTheMenuCarryThePermissionsOfTheActiveRole() throws Exception {
		// the shipped sidebar, per side
		mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(jsonPath("$.permissions.PERMISSIONS").value("O"))
			.andExpect(jsonPath("$.permissions.RFQ").value("V"))
			.andExpect(jsonPath("$.menu[*].items[*].label").value(hasItems("Dashboard", "RFQs", "Users", "Permission Management")));
		mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, supplierQuality.bearer()))
			.andExpect(jsonPath("$.menu[*].items[*].label").value(hasItems("Assigned RFQs", "NPD Tasks", "Orders", "Inspections")))
			.andExpect(jsonPath("$.menu[*].items[*].label").value(not(hasItem("Users"))))
			.andExpect(jsonPath("$.menu[*].items[*].label").value(not(hasItem("Permission Management"))))
			.andExpect(jsonPath("$.menu[*].items[*].label").value(not(hasItem("RFQs"))))
			.andExpect(jsonPath("$.menu[*].items[*].path").value(not(hasItem("/admin/permissions"))));
		mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, management.bearer()))
			.andExpect(jsonPath("$.menu[*].items[*].label").value(not(hasItem("Permission Management"))))
			.andExpect(jsonPath("$.menu[*].items[*].label").value(hasItem("Master data")));
	}

	// ---------------------------------------------------------------- no level widens scope or hands out approvals

	@Test
	void aSupplierGivenOwnerStillGivesNoErgoApprovalAndSeesNoCustomerOnlyRoute() throws Exception {
		save("SUPPLIER", version(), "pilot", change("SUPPLIER_QUALITY", "QUALITY", "O")).andExpect(status().isOk());
		mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, supplierQuality.bearer()))
			.andExpect(jsonPath("$.permissions.QUALITY").value("O"));
		// ERGO's final approvals are ERGO-side endpoints: a supplier Owner is refused (403 before any record lookup)
		mvc.perform(post("/api/quality/ncrs/1/close").header(HttpHeaders.AUTHORIZATION, supplierQuality.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\": \"x\"}")).andExpect(status().isForbidden());
		// and it can open the Quality list, as before
		mvc.perform(get("/api/quality/ncrs").header(HttpHeaders.AUTHORIZATION, supplierQuality.bearer()))
			.andExpect(status().isOk());
	}

	@Test
	void atEveryLevelAnotherCompanysCustomerNeverReadsOrActsOnARecordItDoesNotOwn() throws Exception {
		long ergo = users.company("ERGO");
		Login ergoRfq = users.login(ergo, "ERGO_RFQ");
		long customerA = users.company("CUSTOMER");
		Login buyerA = users.login(customerA, "CUSTOMER_RFQ");
		long rfqId = JsonPath.<Number>read(mvc.perform(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, buyerA.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(completeRfq()))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id").longValue();
		mvc.perform(post("/api/rfqs/{id}/submit", rfqId).header(HttpHeaders.AUTHORIZATION, buyerA.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());

		for (String level : List.of("NV", "V", "A", "O")) {
			save("CUSTOMER", version(), "level " + level, change("CUSTOMER_KAM", "RFQ", level));
			String bearer = customerKam.bearer(); // another customer company's user
			// it never sees company A's RFQ in its list, and never opens it
			mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, bearer))
				.andExpect(result -> {
					if (result.getResponse().getStatus() == 200) {
						org.assertj.core.api.Assertions
							.assertThat(JsonPath.<List<Number>>read(result.getResponse().getContentAsString(), "$[*].id"))
							.as("level %s list", level)
							.doesNotContain((Number) (int) rfqId, (Number) rfqId);
					}
				});
			int open = mvc.perform(get("/api/rfqs/{id}", rfqId).header(HttpHeaders.AUTHORIZATION, bearer)).andReturn()
				.getResponse().getStatus();
			org.assertj.core.api.Assertions.assertThat(open).as("level %s open", level).isIn(403, 404);
			// and no level makes it an ERGO reviewer
			for (String action : List.of("start-review", "request-clarification")) {
				int acted = mvc.perform(post("/api/rfqs/{id}/" + action, rfqId).header(HttpHeaders.AUTHORIZATION, bearer)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"comment\": \"x\"}")).andReturn().getResponse().getStatus();
				org.assertj.core.api.Assertions.assertThat(acted).as("level %s %s", level, action).isIn(403, 404);
			}
		}
		// the owning company's own view is unaffected by any of that
		mvc.perform(get("/api/rfqs/{id}", rfqId).header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isOk());
		org.assertj.core.api.Assertions.assertThat(ergoRfq).isNotNull();
	}

	@Test
	void aRoleWithNoAccessToAModuleGetsNothingOfItOnTheDashboard() throws Exception {
		save("CUSTOMER", version(), "no NPD for customer KAM", change("CUSTOMER_KAM", "NPD", "NV"), change("CUSTOMER_KAM", "OFP", "NV"))
			.andExpect(status().isOk());
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, customerKam.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.npdItems.length()").value(0))
			.andExpect(jsonPath("$.orderItems.length()").value(0));
	}

	@Test
	void aSupplierOwnerOpensNoErgoScreenAndOnlyEverListsItsOwnOrders() throws Exception {
		save("SUPPLIER", version(), "pilot", change("SUPPLIER_QUALITY", "NPD", "O"), change("SUPPLIER_QUALITY", "OFP", "O"))
			.andExpect(status().isOk());
		// the ERGO project list and ERGO workflow stay ERGO-only
		mvc.perform(get("/api/npd").header(HttpHeaders.AUTHORIZATION, supplierQuality.bearer()))
			.andExpect(status().isForbidden());
		mvc.perform(post("/api/ofp/orders").header(HttpHeaders.AUTHORIZATION, supplierQuality.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().is4xxClientError());
		// a fresh supplier company owns no orders: it lists none (never another company's)
		mvc.perform(get("/api/ofp/orders").header(HttpHeaders.AUTHORIZATION, supplierQuality.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void aKeyAccountManagerSetToNoAccessOnNpdIsCutOff() throws Exception {
		Login kam = users.login(users.company("ERGO"), "ERGO_KAM");
		mvc.perform(get("/api/npd").header(HttpHeaders.AUTHORIZATION, kam.bearer())).andExpect(status().isOk());
		save("ERGO", version(), "no NPD for KAM", change("ERGO_KAM", "NPD", "NV")).andExpect(status().isOk());
		mvc.perform(get("/api/npd").header(HttpHeaders.AUTHORIZATION, kam.bearer())).andExpect(status().isForbidden());
		mvc.perform(get("/api/npd/1").header(HttpHeaders.AUTHORIZATION, kam.bearer())).andExpect(status().isForbidden());
	}

	@Test
	void theDatabaseItselfRefusesToGiveErgoAdminMoreThanViewOnABusinessModule() {
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> jdbc.update(
					"UPDATE role_module_permissions SET access_level = 'O' WHERE role_code = 'ERGO_ADMIN' AND module_code = 'RFQ'"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	// ---------------------------------------------------------------- audit and reset

	@Test
	void everyChangedCellIsAuditedWithTheReasonAndResetRestoresTheShippedSheet() throws Exception {
		save("SUPPLIER", version(), "Pilot with supplier quality", change("SUPPLIER_QUALITY", "RFQ", "A"),
				change("SUPPLIER_KAM", "OFP", "V")).andExpect(status().isOk());

		mvc.perform(get("/api/admin/permissions/history").param("role", "SUPPLIER_QUALITY")
			.header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].role").value("SUPPLIER_QUALITY"))
			.andExpect(jsonPath("$[0].module").value("RFQ"))
			.andExpect(jsonPath("$[0].oldLevel").value("V"))
			.andExpect(jsonPath("$[0].newLevel").value("A"))
			.andExpect(jsonPath("$[0].reason").value("Pilot with supplier quality"))
			.andExpect(jsonPath("$[0].changedByRole").value("ERGO_ADMIN"));

		mvc.perform(post("/api/admin/permissions/SUPPLIER/reset").header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\": %d, \"reason\": \"Back to the signed-off sheet\"}".formatted(version())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roles[?(@.code=='SUPPLIER_QUALITY')].cells.RFQ.level").value(hasItem("V")))
			.andExpect(jsonPath("$.roles[?(@.code=='SUPPLIER_KAM')].cells.OFP.level").value(hasItem("A")));
	}

	// ---------------------------------------------------------------- helpers

	private long version() throws Exception {
		return JsonPath.<Number>read(mvc.perform(get("/api/admin/permissions/ERGO")
			.header(HttpHeaders.AUTHORIZATION, admin.bearer())).andReturn().getResponse().getContentAsString(), "$.version")
			.longValue();
	}

	private ResultActions save(String side, long version, String reason, String... changes) throws Exception {
		return mvc.perform(put("/api/admin/permissions/" + side).header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(saveBody(version, reason, changes)));
	}

	private static String saveBody(long version, String reason, String... changes) {
		return "{\"expectedVersion\": %d, \"reason\": \"%s\", \"changes\": [%s]}".formatted(version, reason,
				String.join(",", changes));
	}

	private static String change(String role, String module, String level) {
		return "{\"role\": \"%s\", \"module\": \"%s\", \"level\": \"%s\"}".formatted(role, module, level);
	}

	private String completeRfq() {
		long material = users.masterEntry("materials", "material");
		long process = users.masterEntry("processes", "process");
		return """
				{"targetQuoteDate": "%2$s", "currencyId": %3$d, "customerRemarks": "Urgent",
				 "items": [{"partNumber": "PN-ISO", "revision": "A", "description": "Flange, machined", "eau": 5000,
				  "materialId": %1$d, "targetProductionPiecePrice": 42.50,
				  "processSteps": [{"processId": %4$d, "stepName": "Step 1 forging"}]}]}"""
			.formatted(material, LocalDate.now().plusDays(14), users.currency("INR"), process);
	}

	/** The stored level straight from the database (not through the cached policy). */
	private String stored(Role role, String module) {
		return jdbc.queryForObject("SELECT access_level FROM role_module_permissions WHERE role_code = ? AND module_code = ?",
				String.class, role.name(), module);
	}

}
