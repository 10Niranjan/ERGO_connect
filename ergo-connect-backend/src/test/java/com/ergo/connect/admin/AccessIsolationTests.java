package com.ergo.connect.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.ergo.connect.common.security.AccessPolicy;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.DefaultAccessSheet;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * Organisation-level isolation, proven against the live permission sheet: whatever level (NV, V, A, O) ERGO Admin gives
 * a customer or supplier role on RFQ and OFP, a user of ANOTHER company never lists, opens, changes or sees on its
 * dashboard a record that company owns, and never gets an ERGO action. ERGO roles reaching every company's records is
 * by design (internal staff, SRS section 8.2) and is asserted as such.
 */
@IntegrationTest
class AccessIsolationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AccessPolicy policy;

	TestUsers users;

	Login admin;

	Login ergoOfp;

	Login ergoRfq;

	Login buyerA;

	long orderId;

	String orderNumber;

	long rfqId;

	String rfqNumber;

	/** Every customer and supplier role, signed in under a company that owns neither the RFQ nor the order. */
	final Map<Role, Login> outsiders = new LinkedHashMap<>();

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		long customerA = users.company("CUSTOMER");
		long customerB = users.company("CUSTOMER");
		long supplierA = users.company("SUPPLIER");
		long supplierB = users.company("SUPPLIER");
		admin = users.login(ergo, "ERGO_ADMIN");
		ergoOfp = users.login(ergo, "ERGO_OFP");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		for (Role role : Role.values()) {
			if (role == Role.CUSTOMER_MANAGEMENT) {
				continue; // inactive in the MVP (SRS 9.2): no user can hold or sign in with it
			}
			if (role.companyType() == CompanyType.CUSTOMER) {
				outsiders.put(role, users.login(customerB, role.name()));
			}
			else if (role.companyType() == CompanyType.SUPPLIER) {
				outsiders.put(role, users.login(supplierB, role.name()));
			}
		}

		String order = mvc.perform(post("/api/ofp/orders").header(HttpHeaders.AUTHORIZATION, ergoOfp.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"customerCompanyId": %d, "supplierCompanyId": %d, "partNumber": "PN-ISO", "revision": "A",
					 "orderedQuantity": 1000, "dueDate": "%s"}""".formatted(customerA, supplierA, LocalDate.now().plusDays(30))))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		orderId = JsonPath.<Number>read(order, "$.id").longValue();
		orderNumber = JsonPath.read(order, "$.orderNumber");

		String rfq = mvc.perform(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, buyerA.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"targetQuoteDate": "%2$s", "currencyId": %3$d, "customerRemarks": "x",
					 "items": [{"partNumber": "PN-ISO", "revision": "A", "description": "Flange", "eau": 5000, "materialId": %1$d,
					  "targetProductionPiecePrice": 42.50, "processSteps": [{"processId": %4$d, "stepName": "Step 1"}]}]}"""
				.formatted(users.masterEntry("materials", "material"), LocalDate.now().plusDays(14), users.currency("INR"),
						users.masterEntry("processes", "process"))))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		rfqId = JsonPath.<Number>read(rfq, "$.id").longValue();
		rfqNumber = JsonPath.read(rfq, "$.number");
		mvc.perform(post("/api/rfqs/{id}/submit", rfqId).header(HttpHeaders.AUTHORIZATION, buyerA.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
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

	@Test
	void noLevelOnRfqOrOfpLetsAnotherCompanyReachTheRecordsOfTheOwner() throws Exception {
		List<String> leaks = new ArrayList<>();
		for (String level : List.of("NV", "V", "A", "O")) {
			setEveryExternalRole(level);
			for (Map.Entry<Role, Login> e : outsiders.entrySet()) {
				String who = e.getKey() + "@" + level;
				String bearer = e.getValue().bearer();
				// lists never contain the other company's order or RFQ
				listMustNotContain(leaks, who, "/api/ofp/orders", bearer, orderId);
				listMustNotContain(leaks, who, "/api/rfqs", bearer, rfqId);
				// opening is refused (or hidden)
				for (String path : List.of("/api/ofp/orders/" + orderId, "/api/ofp/orders/" + orderId + "/history",
						"/api/rfqs/" + rfqId, "/api/rfqs/" + rfqId + "/history")) {
					int s = statusOf(get(path).header(HttpHeaders.AUTHORIZATION, bearer));
					if (s != 403 && s != 404) {
						leaks.add(who + " GET " + path + " -> " + s);
					}
				}
				// writing and every ERGO workflow action is refused
				for (String path : List.of("/api/ofp/orders/" + orderId + "/advance", "/api/ofp/orders/" + orderId + "/hold",
						"/api/ofp/orders/" + orderId + "/close", "/api/rfqs/" + rfqId + "/submit",
						"/api/rfqs/" + rfqId + "/start-review", "/api/rfqs/" + rfqId + "/request-clarification",
						"/api/rfqs/" + rfqId + "/close", "/api/rfqs/" + rfqId + "/cancel")) {
					int s = statusOf(post(path).header(HttpHeaders.AUTHORIZATION, bearer)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\": \"x\", \"comment\": \"x\"}"));
					if (s != 403 && s != 404) {
						leaks.add(who + " POST " + path + " -> " + s);
					}
				}
				// nothing about the other company's records on the dashboard either
				MockHttpServletResponse dash = mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, bearer))
					.andReturn().getResponse();
				if (dash.getStatus() == 200 && (dash.getContentAsString().contains(orderNumber)
						|| dash.getContentAsString().contains(rfqNumber))) {
					leaks.add(who + " dashboard shows the other company's record");
				}
			}
		}
		assertThat(leaks).as("cross-company leaks").isEmpty();
	}

	@Test
	void theOwnersAndErgoStillReachTheirRecordsSoTheTestIsNotVacuous() throws Exception {
		// ERGO roles reach every company's records by design
		mvc.perform(get("/api/ofp/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, ergoOfp.bearer()))
			.andExpect(status().isOk());
		mvc.perform(get("/api/rfqs/{id}", rfqId).header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer()))
			.andExpect(status().isOk());
		// the owning customer reads its own RFQ; at NV it does not
		mvc.perform(get("/api/rfqs/{id}", rfqId).header(HttpHeaders.AUTHORIZATION, buyerA.bearer())).andExpect(status().isOk());
		save("CUSTOMER", "CUSTOMER_RFQ", "RFQ", "NV");
		mvc.perform(get("/api/rfqs/{id}", rfqId).header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- helpers

	private void listMustNotContain(List<String> leaks, String who, String path, String bearer, long id) throws Exception {
		MockHttpServletResponse r = mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse();
		if (r.getStatus() == 200) {
			JsonPath.read(r.getContentAsString(), "$.items"); // a definite path: fails loudly if the page shape changes again
			List<Number> ids = JsonPath.read(r.getContentAsString(), "$.items[*].id");
			if (ids.stream().anyMatch(n -> n.longValue() == id)) {
				leaks.add(who + " GET " + path + " lists the other company's record " + id);
			}
		}
		else if (r.getStatus() != 403) {
			leaks.add(who + " GET " + path + " -> " + r.getStatus());
		}
	}

	private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse().getStatus();
	}

	/** One save per side: every customer / supplier role gets this level on RFQ and OFP. */
	private void setEveryExternalRole(String level) throws Exception {
		for (CompanyType side : List.of(CompanyType.CUSTOMER, CompanyType.SUPPLIER)) {
			List<String> changes = new ArrayList<>();
			for (Role role : Role.values()) {
				if (role.companyType() == side) {
					for (String module : List.of("RFQ", "OFP")) {
						changes.add("{\"role\": \"%s\", \"module\": \"%s\", \"level\": \"%s\"}".formatted(role, module, level));
					}
				}
			}
			String body = "{\"expectedVersion\": %d, \"reason\": \"isolation test %s\", \"changes\": [%s]}"
				.formatted(version(), level, String.join(",", changes));
			int s = statusOf(put("/api/admin/permissions/" + side).header(HttpHeaders.AUTHORIZATION, admin.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body));
			// 422 only when every cell already had this level (nothing to change), which is fine here
			assertThat(s).as("save %s %s", side, level).isIn(200, 422);
		}
	}

	private void save(String side, String role, String module, String level) throws Exception {
		mvc.perform(put("/api/admin/permissions/" + side).header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\": %d, \"reason\": \"x\", \"changes\": [{\"role\": \"%s\", \"module\": \"%s\", \"level\": \"%s\"}]}"
				.formatted(version(), role, module, level)))
			.andExpect(status().isOk());
	}

	private long version() throws Exception {
		return JsonPath.<Number>read(mvc.perform(get("/api/admin/permissions/ERGO")
			.header(HttpHeaders.AUTHORIZATION, admin.bearer())).andReturn().getResponse().getContentAsString(), "$.version")
			.longValue();
	}

}
