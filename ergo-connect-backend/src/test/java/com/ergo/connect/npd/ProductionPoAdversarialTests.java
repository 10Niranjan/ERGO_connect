package com.ergo.connect.npd;

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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Adversarial coverage of the production PO lifecycle (flow doc step 11, ARCHITECTURE.md section 8 entries of 2026-10-24).
 * Negative and boundary cases first. Concurrency is in ProductionPoConcurrencyTests.
 */
@IntegrationTest
class ProductionPoAdversarialTests extends ProductionPoSupport {

	private static final String PO = "/api/ofp/production-pos";

	// ================================================================ 1. illegal transitions

	@Test
	void resubmitIsRefusedUnlessReturned() throws Exception {
		long po = raise(ofpA, sampleA());
		int rows = auditRows(po);
		// SUBMITTED
		ok(409, code("PUT", PO + "/{id}", ofpA, edit(), po), "resubmit while SUBMITTED");
		assertThat(poStatus(po)).isEqualTo("SUBMITTED");
		assertThat(jdbc.queryForObject("SELECT quantity FROM production_pos WHERE production_po_id = ?", Integer.class, po)).isEqualTo(1000);
		// ACCEPTED
		call(post(PO + "/{id}/create-project", po), kamA, assign()).andExpect(status().isCreated());
		int afterAccept = auditRows(po);
		ok(409, code("PUT", PO + "/{id}", ofpA, edit(), po), "resubmit while ACCEPTED");
		assertThat(poStatus(po)).isEqualTo("ACCEPTED");
		assertThat(jdbc.queryForObject("SELECT quantity FROM production_pos WHERE production_po_id = ?", Integer.class, po)).isEqualTo(1000);
		assertThat(auditRows(po)).as("refused resubmits leave no audit row").isEqualTo(afterAccept);
		assertThat(rows).isLessThanOrEqualTo(afterAccept);
	}

	@Test
	void returnAndCreateProjectAreRefusedOnceTheOrderIsReturnedOrAccepted() throws Exception {
		long po = raise(ofpA, sampleA());
		call(post(PO + "/{id}/return", po), kamA, comment("Fix the quantity")).andExpect(status().isOk());
		int rows = auditRows(po);
		long notes = count("SELECT count(*) FROM notifications WHERE linked_module = 'production-po' AND linked_record_id = ?", po);

		ok(409, code("POST", PO + "/{id}/return", kamA, comment("again"), po), "return while RETURNED");
		ok(409, code("POST", PO + "/{id}/create-project", kamA, assign(), po), "create-project while RETURNED");
		ok(409, code("POST", PO + "/{id}/create-project", ergoMgmt, assign(), po), "mgmt create-project while RETURNED");
		assertThat(projectsOf(po)).isZero();

		// resubmitted, accepted
		call(put(PO + "/{id}", po), ofpA, edit()).andExpect(status().isOk());
		call(post(PO + "/{id}/create-project", po), kamA, assign()).andExpect(status().isCreated());
		ok(409, code("POST", PO + "/{id}/create-project", kamA, assign(), po), "create-project twice");
		ok(409, code("POST", PO + "/{id}/create-project", ergoMgmt, assign(), po), "create-project twice (mgmt)");
		ok(409, code("POST", PO + "/{id}/return", kamA, comment("too late"), po), "return after ACCEPTED");
		assertThat(projectsOf(po)).as("exactly one OFP project").isEqualTo(1);
		assertThat(poStatus(po)).isEqualTo("ACCEPTED");
		assertThat(count("SELECT count(*) FROM npd_projects WHERE source_project_id = (SELECT source_project_id FROM production_pos WHERE production_po_id = ?)", po))
			.isEqualTo(1);
		assertThat(rows).isPositive();
		assertThat(heard(ofpA, "PRODUCTION_PO_RETURNED", po)).as("refused returns send nothing").isEqualTo(1);
		assertThat(notes).isPositive();
	}

	@Test
	void raiseForTheWrongSourceIsRefusedAndCreatesNothing() throws Exception {
		long sample = sampleA();
		long before = pos();
		ok(404, code("POST", PO, ofpA, form(999_999_999L)), "nonexistent source");
		ok(404, code("POST", PO, ofpA, form(Long.MAX_VALUE)), "huge source id");
		ok(400, code("POST", PO, ofpA, form(sample).replace("\"sourceProjectId\": " + sample, "\"sourceProjectId\": null")), "null source");
		ok(400, code("POST", PO, ofpA, form(sample).replace("\"sourceProjectId\": " + sample + ",", "")), "missing source");
		ok(400, code("POST", PO, ofpA, form(sample).replace("\"sourceProjectId\": " + sample, "\"sourceProjectId\": \"abc\"")), "text source");

		// the OFP project of an accepted order is not a sample project: no production order continues it
		long po = raise(ofpA, sample);
		call(post(PO + "/{id}/create-project", po), kamA, assign()).andExpect(status().isCreated());
		long production = jdbc.queryForObject("SELECT npd_project_id FROM npd_projects WHERE production_po_id = ?", Long.class, po);
		ok(404, code("POST", PO, ofpA, form(production)), "production project as source");
		// another company's approved sample: not found, nothing created
		long other = sampleB();
		ok(404, code("POST", PO, ofpA, form(other)), "other customer's sample");
		ok(404, code("POST", PO, ofpB, form(sample)), "other customer raising for A's sample");
		assertThat(pos()).isEqualTo(before + 1);
	}

	@Test
	void anOpenOrderBlocksASecondOneInEveryOpenStateButNotAfterAcceptance() throws Exception {
		long sample = sampleA();
		long po1 = raise(ofpA, sample);
		ok(409, code("POST", PO, ofpA, form(sample)), "second raise while SUBMITTED");
		ok(409, code("POST", PO, ofpA2, form(sample)), "second raise by a colleague while SUBMITTED");
		call(post(PO + "/{id}/return", po1), kamA, comment("Fix")).andExpect(status().isOk());
		ok(409, code("POST", PO, ofpA, form(sample)), "second raise while RETURNED");
		assertThat(count("SELECT count(*) FROM production_pos WHERE source_project_id = ?", sample)).isEqualTo(1);

		call(put(PO + "/{id}", po1), ofpA, edit()).andExpect(status().isOk());
		call(post(PO + "/{id}/create-project", po1), kamA, assign()).andExpect(status().isCreated());
		// V38 comment: "a repeat order follows once the earlier one was accepted"
		MvcResult second = call(post(PO), ofpA, form(sample)).andExpect(status().isCreated()).andReturn();
		long po2 = JsonPath.<Number>read(second.getResponse().getContentAsString(), "$.id").longValue();
		String n1 = jdbc.queryForObject("SELECT production_po_number FROM production_pos WHERE production_po_id = ?", String.class, po1);
		String n2 = jdbc.queryForObject("SELECT production_po_number FROM production_pos WHERE production_po_id = ?", String.class, po2);
		assertThat(n2).isNotEqualTo(n1);
		ok(409, code("POST", PO, ofpA, form(sample)), "third raise while the repeat order is open");
	}

	// ================================================================ 2. mandatory comment

	@Test
	void aReturnWithoutARealCommentIsRefused() throws Exception {
		long po = raise(ofpA, sampleA());
		int rows = auditRows(po);
		List<String> bodies = new ArrayList<>(List.of("{}", "{\"comment\": null}", comment(""), comment("   "), comment("\\t\\n \\r"),
				"[]", "\"text\"", "{\"comment\": \"" + "x".repeat(2001) + "\"}"));
		for (String body : bodies) {
			ok(400, code("POST", PO + "/{id}/return", kamA, body, po), "return with " + abbreviate(body));
		}
		ok(400, code("POST", PO + "/{id}/return", kamA, null, po), "return with no body");
		ok(400, code("POST", PO + "/{id}/return", kamA, "{not json", po), "return with malformed json");
		assertThat(poStatus(po)).isEqualTo("SUBMITTED");
		assertThat(auditRows(po)).isEqualTo(rows);
		assertThat(heard(ofpA, "PRODUCTION_PO_RETURNED", po)).isZero();

		// boundary: exactly 2000 passes
		ok(200, code("POST", PO + "/{id}/return", kamA, comment("x".repeat(2000)), po), "2000 chars");
		assertThat(jdbc.queryForObject("SELECT length(return_comment) FROM production_pos WHERE production_po_id = ?", Integer.class, po)).isEqualTo(2000);
	}

	@Test
	void aCommentOfOnlyInvisibleCharactersDoesNotCountAsAReason() throws Exception {
		long po = raise(ofpA, sampleA());
		// no-break space, zero-width space, ideographic space: blank to a person, not to String.trim()
		for (String blank : List.of("\\u00a0", "\\u200b", "\\u3000", "\\u00a0\\u200b\\u00a0")) {
			ok(400, code("POST", PO + "/{id}/return", kamA, comment(blank), po), "return with comment " + blank);
		}
		assertThat(poStatus(po)).isEqualTo("SUBMITTED");
	}

	@Test
	void theReturnCommentIsTrimmedStoredAndAuditedAsTheReason() throws Exception {
		long po = raise(ofpA, sampleA());
		call(post(PO + "/{id}/return", po), kamA, comment("  Quantity does not match the lot  ")).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT return_comment FROM production_pos WHERE production_po_id = ?", String.class, po))
			.isEqualTo("Quantity does not match the lot");
		Map<String, Object> row = audit(po).stream().filter(r -> "status".equals(r.get("field_name"))).reduce((a, b) -> b).orElseThrow();
		assertThat(row.get("old_value")).isEqualTo("SUBMITTED");
		assertThat(row.get("new_value")).isEqualTo("RETURNED");
		assertThat(row.get("action_reason")).isEqualTo("Quantity does not match the lot");
		assertThat(row.get("changed_by_user_id")).isEqualTo(kamA.userId());
		assertThat(row.get("changed_by_role_code")).isEqualTo("ERGO_KAM");
		assertThat(row.get("changed_by_company_id")).isEqualTo(ergoCo);
	}

	// ================================================================ 3. role x action

	@Test
	void everyRoleAgainstEveryProductionOrderAction() throws Exception {
		Map<String, Login> by = new LinkedHashMap<>();
		for (String r : List.of("CUSTOMER_RFQ", "CUSTOMER_NPD", "CUSTOMER_QUALITY", "CUSTOMER_KAM")) {
			by.put(r, users.login(customer, r));
		}
		for (String r : List.of("ERGO_ADMIN", "ERGO_RFQ", "ERGO_NPD", "ERGO_OFP", "ERGO_QUALITY")) {
			by.put(r, users.login(ergoCo, r));
		}
		for (String r : List.of("SUPPLIER_RFQ", "SUPPLIER_NPD", "SUPPLIER_OFP", "SUPPLIER_QUALITY", "SUPPLIER_KAM")) {
			by.put(r, users.login(supplierX, r));
		}
		by.put("ERGO_MANAGEMENT", ergoMgmt);
		by.put("ERGO_KAM", kamA);
		by.put("CUSTOMER_OFP", ofpA);

		long sample = sampleA();
		// raise: only CUSTOMER_OFP
		for (var e : by.entrySet()) {
			if (!e.getKey().equals("CUSTOMER_OFP")) {
				ok(403, code("POST", PO, e.getValue(), form(sample)), "raise as " + e.getKey());
			}
		}
		assertThat(count("SELECT count(*) FROM production_pos WHERE source_project_id = ?", sample)).isZero();
		long po = raise(ofpA, sample);

		// reads: the OFP column of the sheet, narrowed to own company / assigned account
		List<String> readers = List.of("ERGO_ADMIN", "ERGO_MANAGEMENT", "ERGO_KAM", "ERGO_RFQ", "ERGO_NPD", "ERGO_OFP", "ERGO_QUALITY",
				"CUSTOMER_OFP", "CUSTOMER_QUALITY", "CUSTOMER_KAM");
		List<String> ergoReaders = List.of("ERGO_ADMIN", "ERGO_MANAGEMENT", "ERGO_KAM", "ERGO_RFQ", "ERGO_NPD", "ERGO_OFP", "ERGO_QUALITY");
		for (var e : by.entrySet()) {
			boolean read = readers.contains(e.getKey());
			ok(read ? 200 : 403, code("GET", PO + "/{id}", e.getValue(), null, po), "get as " + e.getKey());
			ok(read ? 200 : 403, code("GET", PO, e.getValue(), null), "list as " + e.getKey());
			ok(ergoReaders.contains(e.getKey()) ? 200 : 403, code("GET", PO + "/{id}/history", e.getValue(), null, po), "history as " + e.getKey());
			boolean desk = e.getKey().equals("ERGO_KAM") || e.getKey().equals("ERGO_MANAGEMENT");
			boolean assigneesRead = desk || e.getKey().equals("ERGO_OFP") || e.getKey().equals("ERGO_QUALITY");
			int assignees = code("GET", PO + "/assignees", e.getValue(), null);
			if (desk) {
				ok(200, assignees, "assignees as " + e.getKey());
			}
			else {
				ok(403, assignees, "assignees as " + e.getKey() + (assigneesRead ? " (acts in OFP but does not decide)" : ""));
			}
			if (!e.getKey().equals("CUSTOMER_OFP")) {
				ok(403, code("GET", PO + "/eligible-projects", e.getValue(), null), "eligible-projects as " + e.getKey());
			}
		}

		// decide: only the KAM of the account and Management
		for (var e : by.entrySet()) {
			if (e.getKey().equals("ERGO_KAM") || e.getKey().equals("ERGO_MANAGEMENT")) {
				continue;
			}
			ok(403, code("POST", PO + "/{id}/return", e.getValue(), comment("no"), po), "return as " + e.getKey());
			ok(403, code("POST", PO + "/{id}/create-project", e.getValue(), assign(), po), "create-project as " + e.getKey());
		}
		assertThat(poStatus(po)).isEqualTo("SUBMITTED");
		assertThat(projectsOf(po)).isZero();

		call(post(PO + "/{id}/return", po), kamA, comment("Please fix")).andExpect(status().isOk());
		// correct: only Customer OFP of the company
		for (var e : by.entrySet()) {
			if (!e.getKey().equals("CUSTOMER_OFP")) {
				ok(403, code("PUT", PO + "/{id}", e.getValue(), edit(), po), "resubmit as " + e.getKey());
			}
		}
		assertThat(poStatus(po)).isEqualTo("RETURNED");
		ok(200, code("PUT", PO + "/{id}", ofpA2, edit(), po), "a Customer OFP colleague may correct");
		assertThat(poStatus(po)).isEqualTo("SUBMITTED");
	}

	// ---------------------------------------------------------------- assignee validation

	@Test
	void theAssigneesMustBeRealActiveUsersOfTheRightRoleAndTheOrderStaysOpenOtherwise() throws Exception {
		long po = raise(ofpA, sampleA());
		int rows = auditRows(po);
		Login inactive = users.login(ergoCo, "ERGO_OFP");
		jdbc.update("UPDATE users SET active = false WHERE user_id = ?", inactive.userId());
		Login customerWithOfpName = ofpA;

		Map<String, String> bad = new LinkedHashMap<>();
		bad.put("wrong role (ERGO NPD as OFP)", assign(ergoNpd.userId(), ergoQuality.userId()));
		bad.put("wrong role (ERGO Quality as OFP)", assign(ergoQuality.userId(), ergoQuality.userId()));
		bad.put("swapped slots", assign(ergoQuality.userId(), ergoOfp.userId()));
		bad.put("ERGO OFP as quality", assign(ergoOfp.userId(), ergoOfp.userId()));
		bad.put("management as OFP", assign(ergoMgmt.userId(), ergoQuality.userId()));
		bad.put("customer user", assign(customerWithOfpName.userId(), ergoQuality.userId()));
		bad.put("supplier user", assign(ergoOfp.userId(), ofpSup.userId()));
		bad.put("inactive OFP", assign(inactive.userId(), ergoQuality.userId()));
		bad.put("nonexistent OFP", assign(987_654_321L, ergoQuality.userId()));
		bad.put("nonexistent quality", assign(ergoOfp.userId(), 987_654_321L));
		bad.put("zero id", assign(0, ergoQuality.userId()));
		bad.put("negative id", assign(ergoOfp.userId(), -5));
		bad.put("huge id", assign(Long.MAX_VALUE, ergoQuality.userId()));
		for (var e : bad.entrySet()) {
			int s = code("POST", PO + "/{id}/create-project", kamA, e.getValue(), po);
			assertThat(s).as(e.getKey()).isEqualTo(422);
		}
		ok(400, code("POST", PO + "/{id}/create-project", kamA, "{\"ergoOfpUserId\": null, \"ergoQualityUserId\": 1}", po), "null OFP id");
		ok(400, code("POST", PO + "/{id}/create-project", kamA, "{\"ergoQualityUserId\": 1}", po), "missing OFP id");
		ok(400, code("POST", PO + "/{id}/create-project", kamA, "{\"ergoOfpUserId\": \"x\", \"ergoQualityUserId\": 1}", po), "text id");
		ok(400, code("POST", PO + "/{id}/create-project", kamA, null, po), "no body");
		ok(400, code("POST", PO + "/{id}/create-project", kamA,
				"{\"ergoOfpUserId\": %d, \"ergoQualityUserId\": %d, \"comment\": \"%s\"}".formatted(ergoOfp.userId(), ergoQuality.userId(), "x".repeat(2001)), po), "comment 2001");
		assertThat(poStatus(po)).isEqualTo("SUBMITTED");
		assertThat(projectsOf(po)).isZero();
		assertThat(auditRows(po)).isEqualTo(rows);

		// after all the refusals the order is still decidable
		ok(201, code("POST", PO + "/{id}/create-project", kamA, assign(), po), "valid assignees");
		assertThat(poStatus(po)).isEqualTo("ACCEPTED");
	}

	@Test
	void anAssigneeFromAnotherCompanyEvenWithAnErgoRoleIsRefused() throws Exception {
		long po = raise(ofpA, sampleA());
		Login odd;
		try {
			odd = users.login(customer, "ERGO_OFP"); // only reachable by corrupt data, but then the check must still hold
		}
		catch (Throwable e) {
			Assumptions.abort("the database/login refuses a customer user with an ERGO role: " + e);
			return;
		}
		int s = code("POST", PO + "/{id}/create-project", kamA, assign(odd.userId(), ergoQuality.userId()), po);
		assertThat(s).as("assignee of a customer company holding ERGO_OFP").isEqualTo(422);
		assertThat(projectsOf(po)).isZero();
	}

	@Test
	void assigneesListHoldsOnlyActiveErgoUsersOfTheRightRoleWithoutContactData() throws Exception {
		Login inactive = users.login(ergoCo, "ERGO_QUALITY");
		jdbc.update("UPDATE users SET active = false WHERE user_id = ?", inactive.userId());
		String json = text(get(PO + "/assignees"), kamA, null);
		List<Integer> ofp = JsonPath.read(json, "$.ofp[*].id");
		List<Integer> quality = JsonPath.read(json, "$.quality[*].id");
		assertThat(ofp).contains((int) ergoOfp.userId()).doesNotContain((int) ergoNpd.userId(), (int) kamA.userId(), (int) ofpSup.userId(), (int) ofpA.userId());
		assertThat(quality).contains((int) ergoQuality.userId()).doesNotContain((int) inactive.userId(), (int) ergoOfp.userId());
		assertThat(json).doesNotContain("@test.dev").doesNotContain("password").doesNotContain("email");
	}

	// ================================================================ 4. record scope

	@Test
	void anotherCompanyAnotherKamAndAnUnassignedKamNeverSeeOrTouchTheOrder() throws Exception {
		long po = raise(ofpA, sampleA());
		call(post(PO + "/{id}/return", po), kamA, comment("Fix")).andExpect(status().isOk()); // RETURNED: every action is addressable
		int rows = auditRows(po);
		Login strangerCustomerKam = users.login(customerB, "CUSTOMER_KAM");

		for (Login stranger : List.of(ofpB, qualB, strangerCustomerKam, kamB, kamNone)) {
			ok(404, code("GET", PO + "/{id}", stranger, null, po), "get");
			ok(404, code("GET", PO + "/{id}/history", stranger, null, po), "history");
		}
		ok(404, code("PUT", PO + "/{id}", ofpB, edit(), po), "resubmit by another customer");
		for (Login kam : List.of(kamB, kamNone)) {
			ok(404, code("POST", PO + "/{id}/return", kam, comment("x"), po), "return by a KAM of another account");
			ok(404, code("POST", PO + "/{id}/create-project", kam, assign(), po), "create-project by a KAM of another account");
		}
		for (Login kam : List.of(kamB, kamNone)) {
			assertThat(text(get(PO + "?size=100"), kam, null)).as("a KAM's list").doesNotContain("\"id\":" + po + ",");
		}
		assertThat(text(get(PO + "?size=100"), ofpB, null)).doesNotContain("\"id\":" + po + ",");
		assertThat(poStatus(po)).isEqualTo("RETURNED");
		assertThat(auditRows(po)).isEqualTo(rows);
		// bad ids
		ok(404, code("GET", PO + "/{id}", kamA, null, 987_654_321L), "unknown id");
		ok(404, code("GET", PO + "/{id}/history", ergoMgmt, null, 987_654_321L), "unknown id history");
		ok(404, code("POST", PO + "/{id}/return", kamA, comment("x"), 987_654_321L), "unknown id return");
		ok(404, code("POST", PO + "/{id}/create-project", kamA, assign(), -1L), "negative id");
		ok(400, code("GET", PO + "/{id}", kamA, null, "abc"), "text id");
		ok(404, code("PUT", PO + "/{id}", ofpA, edit(), 987_654_321L), "unknown id resubmit");
	}

	@Test
	void listsHoldOnlyTheCallersOwnScopeAndOneKamCannotSeeAnotherAccount() throws Exception {
		long poA = raise(ofpA, sampleA());
		long poB = raise(ofpB, sampleB());
		assertThat(ids(text(get(PO + "?size=100"), ofpA, null))).contains(poA).doesNotContain(poB);
		assertThat(ids(text(get(PO + "?size=100"), ofpB, null))).contains(poB).doesNotContain(poA);
		assertThat(ids(text(get(PO + "?size=100"), kamA, null))).contains(poA).doesNotContain(poB);
		assertThat(ids(text(get(PO + "?size=100"), kamB, null))).contains(poB).doesNotContain(poA);
		assertThat(ids(text(get(PO + "?size=100"), kamNone, null))).isEmpty();
		assertThat(ids(text(get(PO + "?size=100"), ergoMgmt, null))).contains(poA, poB);
		assertThat(ids(text(get(PO + "?size=100"), ergoOfp, null))).contains(poA, poB);
		// a KAM gets a second account: the list follows at once
		users.assignAccounts(kamNone.userId(), customer);
		assertThat(ids(text(get(PO + "?size=100"), kamNone, null))).containsExactly(poA);

		// Customer A's colleagues of another role in the company read the order, B's staff do not know A's project
		call(get(PO + "/eligible-projects"), ofpB, null).andExpect(status().isOk());
		String eligibleB = text(get(PO + "/eligible-projects"), ofpB, null);
		long sampleOfA = jdbc.queryForObject("SELECT source_project_id FROM production_pos WHERE production_po_id = ?", Long.class, poA);
		assertThat(eligibleB).doesNotContain("\"projectId\":" + sampleOfA + ",");
		// the other customer's order is reachable by id only through the other customer's own people
		ok(404, code("GET", PO + "/{id}", ofpA, null, poB), "A reading B's order");
	}

	@Test
	void theSupplierOfTheProjectAndAnotherSupplierAreRefusedEverywhere() throws Exception {
		long po = raise(ofpA, sampleA());
		Login stranger = users.login(supplierY, "SUPPLIER_OFP");
		for (Login s : List.of(ofpSup, npdSup, qualSup, sellerX, stranger)) {
			ok(403, code("GET", PO + "/{id}", s, null, po), "get");
			ok(403, code("GET", PO, s, null), "list");
			ok(403, code("GET", PO + "/{id}/history", s, null, po), "history");
			ok(403, code("GET", PO + "/assignees", s, null), "assignees");
			ok(403, code("GET", PO + "/eligible-projects", s, null), "eligible");
			ok(403, code("POST", PO, s, form(1), po), "raise");
			ok(403, code("PUT", PO + "/{id}", s, edit(), po), "resubmit");
			ok(403, code("POST", PO + "/{id}/return", s, comment("x"), po), "return");
			ok(403, code("POST", PO + "/{id}/create-project", s, assign(), po), "create");
		}
	}

	// ================================================================ 6. validation and boundaries

	@Test
	void theRaiseFormRefusesBadQuantitiesDatesAndStrings() throws Exception {
		long sample = sampleA();
		long before = pos();
		String d = LocalDate.now().plusDays(10).toString();
		Map<String, String> bad = new LinkedHashMap<>();
		bad.put("quantity 0", form(sample, 0, d));
		bad.put("quantity -1", form(sample, -1, d));
		bad.put("quantity null", form(sample, "null", d));
		bad.put("quantity text", form(sample, "\"many\"", d));
		bad.put("quantity beyond int", form(sample, "2147483648", d));
		bad.put("quantity huge", form(sample, "99999999999999999999", d));
		bad.put("quantity 1e3 as text", form(sample, "\"1e3\"", d));
		bad.put("date yesterday", form(sample, 5, LocalDate.now().minusDays(1).toString()));
		bad.put("date null", form(sample, 5, "x").replace("\"requiredDate\": \"x\"", "\"requiredDate\": null"));
		bad.put("date missing", form(sample, 5, "x").replace(", \"requiredDate\": \"x\"", ""));
		bad.put("date bad format", form(sample, 5, "31/12/2030"));
		bad.put("date impossible", form(sample, 5, "2030-02-30"));
		bad.put("po number blank", form(sample, 5, d).replaceAll("CPO-\\d+", "   "));
		bad.put("po number missing", form(sample, 5, d).replaceAll("\"customerPoNumber\": \"CPO-\\d+\",", ""));
		bad.put("po number 61 chars", form(sample, 5, d).replaceAll("CPO-\\d+", "P".repeat(61)));
		bad.put("remarks 2001 chars", form(sample, 5, d).replace("First lot", "r".repeat(2001)));
		for (var e : bad.entrySet()) {
			int s = code("POST", PO, ofpA, e.getValue());
			assertThat(s).as(e.getKey()).isEqualTo(400);
		}
		ok(400, code("POST", PO, ofpA, null), "no body");
		ok(400, code("POST", PO, ofpA, "{oops"), "malformed json");
		ok(400, code("POST", PO, ofpA, "[]"), "array body");
		assertThat(pos()).as("nothing was created by any refused form").isEqualTo(before);
		assertThat(count("SELECT count(*) FROM production_pos WHERE source_project_id = ?", sample)).isZero();
	}

	@Test
	void boundaryValuesThatAreValidAreAcceptedAndStoredAsGiven() throws Exception {
		long sample = sampleA();
		// quantity 1, today as the required date, a 60-char PO number, 2000-char remarks, padded PO number trimmed
		String body = """
				{"sourceProjectId": %d, "customerPoNumber": "  %s  ", "quantity": 1, "requiredDate": "%s", "remarks": "%s"}"""
			.formatted(sample, "P".repeat(60), LocalDate.now(), "r".repeat(2000)).replace("\"  ", "\"").replace("  \"", "\"");
		MvcResult r = call(post(PO), ofpA, body).andExpect(status().isCreated()).andReturn();
		long po = JsonPath.<Number>read(r.getResponse().getContentAsString(), "$.id").longValue();
		assertThat(jdbc.queryForObject("SELECT customer_po_number FROM production_pos WHERE production_po_id = ?", String.class, po)).isEqualTo("P".repeat(60));
		assertThat(jdbc.queryForObject("SELECT quantity FROM production_pos WHERE production_po_id = ?", Integer.class, po)).isEqualTo(1);
		// maximum quantity
		call(post(PO + "/{id}/return", po), kamA, comment("again")).andExpect(status().isOk());
		call(put(PO + "/{id}", po), ofpA, edit(Integer.MAX_VALUE, LocalDate.now().toString())).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT quantity FROM production_pos WHERE production_po_id = ?", Integer.class, po)).isEqualTo(Integer.MAX_VALUE);
	}

	@Test
	void aFractionalQuantityIsRefusedNotSilentlyTruncated() throws Exception {
		long sample = sampleA();
		String d = LocalDate.now().plusDays(10).toString();
		for (String q : List.of("1.5", "1000.9", "0.4", "2.0000001")) {
			int s = code("POST", PO, ofpA, form(sample, q, d));
			assertThat(s).as("raise with quantity " + q).isEqualTo(400);
		}
		assertThat(count("SELECT count(*) FROM production_pos WHERE source_project_id = ?", sample)).isZero();
		long po = raise(ofpA, sample);
		call(post(PO + "/{id}/return", po), kamA, comment("Fix")).andExpect(status().isOk());
		ok(400, code("PUT", PO + "/{id}", ofpA, edit("1000.9", d), po), "correction with quantity 1000.9");
		assertThat(poStatus(po)).isEqualTo("RETURNED");
	}

	@Test
	void theCorrectionFormObeysTheSameRulesAndCannotChangeThePartOrTheOwner() throws Exception {
		long sample = sampleA();
		long po = raise(ofpA, sample);
		call(post(PO + "/{id}/return", po), kamA, comment("Fix")).andExpect(status().isOk());
		String d = LocalDate.now().plusDays(5).toString();
		for (String bad : List.of(edit(0, d), edit(-3, d), edit("null", d), edit(5, "2020-01-01"), edit(5, "nope"),
				"{}", "{\"customerPoNumber\": \"\", \"quantity\": 5, \"requiredDate\": \"" + d + "\"}")) {
			ok(400, code("PUT", PO + "/{id}", ofpA, bad, po), "correction " + abbreviate(bad));
		}
		ok(400, code("PUT", PO + "/{id}", ofpA, null, po), "correction without body");
		assertThat(poStatus(po)).isEqualTo("RETURNED");

		// mass assignment: extra fields are ignored, not applied
		long other = sampleB();
		String tampered = """
				{"customerPoNumber": "CPO-T", "quantity": 7, "requiredDate": "%s", "status": "ACCEPTED", "customerCompanyId": %d,
				 "sourceProjectId": %d, "partNumber": "HACK", "productionPoNumber": "PPO-HACK", "returnComment": "none"}"""
			.formatted(d, customerB, other);
		ok(200, code("PUT", PO + "/{id}", ofpA, tampered, po), "tampered correction");
		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM production_pos WHERE production_po_id = ?", po);
		assertThat(row.get("status")).isEqualTo("SUBMITTED");
		assertThat(row.get("customer_company_id")).isEqualTo(customer);
		assertThat(row.get("source_project_id")).isEqualTo(sample);
		assertThat(row.get("part_number")).isNotEqualTo("HACK");
		assertThat(row.get("production_po_number").toString()).startsWith("PPO-20").doesNotContain("HACK");
		assertThat(row.get("return_comment")).as("the new version answers the last return").isNull();

		// same on the raise form
		long sample2 = sampleA();
		String raiseTampered = form(sample2).replace("}", ", \"status\": \"ACCEPTED\", \"customerCompanyId\": " + customerB + ", \"partNumber\": \"HACK\"}");
		MvcResult res = call(post(PO), ofpA, raiseTampered).andExpect(status().isCreated()).andReturn();
		long po2 = JsonPath.<Number>read(res.getResponse().getContentAsString(), "$.id").longValue();
		assertThat(poStatus(po2)).isEqualTo("SUBMITTED");
		assertThat(jdbc.queryForObject("SELECT customer_company_id FROM production_pos WHERE production_po_id = ?", Long.class, po2)).isEqualTo(customer);
		assertThat(jdbc.queryForObject("SELECT part_number FROM production_pos WHERE production_po_id = ?", String.class, po2)).isNotEqualTo("HACK");
	}

	@Test
	void numbersAreUniqueIncreasingAndInTheDocumentedFormat() throws Exception {
		List<String> numbers = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			long po = raise(ofpA, sampleA());
			numbers.add(jdbc.queryForObject("SELECT production_po_number FROM production_pos WHERE production_po_id = ?", String.class, po));
		}
		assertThat(numbers).doesNotHaveDuplicates();
		int year = LocalDate.now().getYear();
		long previous = -1;
		for (String n : numbers) {
			assertThat(n).matches("PPO-" + year + "-\\d{4,}"); // PPO-yyyy-nnnn
			long seq = Long.parseLong(n.substring(n.lastIndexOf('-') + 1));
			assertThat(seq).isGreaterThan(previous);
			previous = seq;
		}
		// a refused raise (409 / 422) must not hand out a visible number; the number the view returns is the stored one
		long po = jdbc.queryForObject("SELECT production_po_id FROM production_pos WHERE production_po_number = ?", Long.class, numbers.get(0));
		String json = text(get(PO + "/{id}", po), ofpA, null);
		assertThat((String) JsonPath.read(json, "$.productionPoNumber")).isEqualTo(numbers.get(0));
	}

	@Test
	void aMissingBodyOrWrongContentTypeIsAClientErrorNotAServerError() throws Exception {
		long sample = sampleA();
		int s = send(post(PO).contentType(MediaType.TEXT_PLAIN).content("hello"), ofpA, null);
		assertThat(s).isIn(400, 415);
		assertThat(count("SELECT count(*) FROM production_pos WHERE source_project_id = ?", sample)).isZero();
	}

	// ================================================================ 7. audit and history

	@Test
	void everyWriteIsAuditedWithWhoRoleCompanyOldNewAndReasonAndRefusalsLeaveNothing() throws Exception {
		long sample = sampleA();
		long po = raise(ofpA, sample);
		List<Map<String, Object>> created = audit(po);
		assertThat(created).as("raise is audited").isNotEmpty();
		assertThat(created).allSatisfy(r -> {
			assertThat(r.get("changed_by_user_id")).isEqualTo(ofpA.userId());
			assertThat(r.get("changed_by_role_code")).isEqualTo("CUSTOMER_OFP");
			assertThat(r.get("changed_by_company_id")).isEqualTo(customer);
		});

		// refusals: nothing added
		int rows = auditRows(po);
		code("POST", PO + "/{id}/return", ofpA, comment("x"), po);
		code("POST", PO + "/{id}/return", ergoOfp, comment("x"), po);
		code("POST", PO + "/{id}/return", kamB, comment("x"), po);
		code("POST", PO + "/{id}/return", kamA, "{}", po);
		code("POST", PO + "/{id}/create-project", ergoQuality, assign(), po);
		code("POST", PO + "/{id}/create-project", kamA, assign(ergoNpd.userId(), ergoQuality.userId()), po);
		code("PUT", PO + "/{id}", ofpA, edit(), po);
		code("POST", PO, ofpA, form(sample));
		assertThat(auditRows(po)).isEqualTo(rows);

		call(post(PO + "/{id}/return", po), kamA, comment("Quantity wrong")).andExpect(status().isOk());
		call(put(PO + "/{id}", po), ofpA2, edit(800, LocalDate.now().plusDays(70).toString())).andExpect(status().isOk());
		call(post(PO + "/{id}/create-project", po), ergoMgmt, assign()).andExpect(status().isCreated());

		List<Map<String, Object>> all = audit(po);
		List<Map<String, Object>> statusRows = all.stream().filter(r -> "status".equals(r.get("field_name"))).toList();
		List<String> transitions = statusRows.stream().map(r -> r.get("old_value") + ">" + r.get("new_value")).toList();
		assertThat(transitions).containsSubsequence("SUBMITTED>RETURNED", "RETURNED>SUBMITTED", "SUBMITTED>ACCEPTED");
		Map<String, Object> accepted = statusRows.getLast();
		assertThat(accepted.get("changed_by_user_id")).isEqualTo(ergoMgmt.userId());
		assertThat(accepted.get("changed_by_role_code")).isEqualTo("ERGO_MANAGEMENT");
		Map<String, Object> resub = statusRows.stream().filter(r -> "RETURNED".equals(r.get("old_value")) && "SUBMITTED".equals(r.get("new_value"))).findFirst().orElseThrow();
		assertThat(resub.get("changed_by_user_id")).isEqualTo(ofpA2.userId());
		assertThat(resub.get("changed_by_role_code")).isEqualTo("CUSTOMER_OFP");
		assertThat(resub.get("changed_by_company_id")).isEqualTo(customer);
		Map<String, Object> qty = all.stream().filter(r -> "quantity".equals(r.get("field_name"))).reduce((a, b) -> b).orElseThrow();
		assertThat(qty.get("old_value")).isEqualTo("1000");
		assertThat(qty.get("new_value")).isEqualTo("800");
		// the return reason sits on the return row only and is not leaked onto the later accept row
		assertThat(accepted.get("action_reason")).isNotEqualTo("Quantity wrong");
		// the audit trail is append-only
		org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.update("DELETE FROM audit_logs WHERE record_id = ? AND entity_name = 'ProductionPo'", po));
	}

	@Test
	void historyIsErgoOnlyAndCarriesTheWholeTrail() throws Exception {
		long po = raise(ofpA, sampleA());
		call(post(PO + "/{id}/return", po), kamA, comment("Fix the date")).andExpect(status().isOk());
		for (Login ergo : List.of(kamA, ergoMgmt, ergoOfp, ergoNpd, ergoQuality)) {
			String json = text(get(PO + "/{id}/history", po), ergo, null);
			assertThat((List<?>) JsonPath.read(json, "$[?(@.field == 'status')]")).hasSizeGreaterThanOrEqualTo(1);
			assertThat((List<?>) JsonPath.read(json, "$[*]")).hasSizeGreaterThanOrEqualTo(2); // the raise and the return
			assertThat(json).contains("Fix the date");
		}
		// the customer's own people, even the approver who raised it, are not given the ERGO trail
		for (Login c : List.of(ofpA, ofpA2, qualA, custKamA)) {
			String body = text(get(PO + "/{id}/history", po), c, null);
			ok(403, code("GET", PO + "/{id}/history", c, null, po), "customer history");
			assertThat(body).doesNotContain("Fix the date");
		}
		// no supplier data or ERGO-internal ids in the PO view for the customer
		String view = text(get(PO + "/{id}", po), ofpA, null);
		assertThat(view).doesNotContain(String.valueOf("Test SUPPLIER")).doesNotContain("revision\":\"").doesNotContain("description\":\"")
			.doesNotContain("createdBy").doesNotContain("rowVersion");
		String name = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?", String.class, supplierX);
		assertThat(view).doesNotContain(name);
		String ergoView = text(get(PO + "/{id}", po), kamA, null);
		assertThat(ergoView).doesNotContain(name).doesNotContain("rowVersion");
	}

	// ================================================================ 8. error shape

	@Test
	void refusalsAreProblemDetailsWithoutInternals() throws Exception {
		long sample = sampleA();
		long po = raise(ofpA, sample);
		List<MvcResult> results = new ArrayList<>();
		results.add(call(post(PO), ofpA, form(sample)).andReturn()); // 409 open order
		results.add(call(post(PO), ofpB, form(sample)).andReturn()); // 404
		results.add(call(post(PO), kamA, form(sample)).andReturn()); // 403
		results.add(call(post(PO), ofpA, form(sample, 0, "2030-01-01")).andReturn()); // 400
		results.add(call(post(PO), ofpA, "{broken").andReturn()); // 400 unreadable
		results.add(call(post(PO + "/{id}/return", po), kamA, "{}").andReturn()); // 400
		results.add(call(post(PO + "/{id}/create-project", po), kamA, assign(ergoNpd.userId(), 1)).andReturn()); // 422
		results.add(call(put(PO + "/{id}", po), ofpA, edit()).andReturn()); // 409
		results.add(call(get(PO + "/{id}", po), kamB, null).andReturn()); // 404
		results.add(call(get(PO + "/{id}", po), ofpSup, null).andReturn()); // 403
		results.add(call(get(PO + "/{id}", "abc"), kamA, null).andReturn()); // 400 type mismatch
		results.add(call(get(PO + "/{id}", po), ofpA, null).andReturn()); // control: 200
		for (MvcResult r : results) {
			int s = r.getResponse().getStatus();
			String body = r.getResponse().getContentAsString();
			if (s >= 400) {
				assertThat(s).isLessThan(500);
				assertThat(r.getResponse().getContentType()).as(body).startsWith("application/problem+json");
				assertThat((Integer) JsonPath.read(body, "$.status")).isEqualTo(s);
				assertThat((String) JsonPath.read(body, "$.title")).isNotBlank();
			}
			assertThat(body).doesNotContain("Exception").doesNotContain("org.springframework").doesNotContain("com.ergo")
				.doesNotContain("org.hibernate").doesNotContain("SQL").doesNotContain("ux_production_pos").doesNotContain("production_pos")
				.doesNotContain("\tat ").doesNotContain("stackTrace");
		}
	}

	// ================================================================ 9. module switch smoke

	@Test
	void withOfpReadOnlyNothingChangesAndWithOfpHiddenNothingShows() throws Exception {
		long sample = sampleA();
		long returned = raise(ofpA, sample);
		call(post(PO + "/{id}/return", returned), kamA, comment("Fix")).andExpect(status().isOk());
		long other = raise(ofpB, sampleB());
		int rowsReturned = auditRows(returned);
		int rowsOther = auditRows(other);
		int total = pos();

		cap("OFP", "V");
		ok(403, code("PUT", PO + "/{id}", ofpA, edit(), returned), "resubmit at V");
		ok(403, code("POST", PO + "/{id}/return", kamB, comment("x"), other), "return at V");
		ok(403, code("POST", PO + "/{id}/create-project", kamB, assign(), other), "create at V");
		ok(403, code("POST", PO + "/{id}/create-project", ergoMgmt, assign(), other), "mgmt create at V");
		ok(403, code("POST", PO, ofpA, form(sample)), "raise at V");
		ok(200, code("GET", PO + "/{id}", kamB, null, other), "read at V");
		assertThat(text(get(PO + "/{id}", other), kamB, null)).contains("\"actions\":[]");
		assertThat(text(get(PO + "/{id}", returned), ofpA, null)).contains("\"actions\":[]");
		assertThat(poStatus(returned)).isEqualTo("RETURNED");
		assertThat(poStatus(other)).isEqualTo("SUBMITTED");
		assertThat(projectsOf(other)).isZero();
		assertThat(auditRows(returned)).isEqualTo(rowsReturned);
		assertThat(auditRows(other)).isEqualTo(rowsOther);
		assertThat(pos()).isEqualTo(total);

		cap("OFP", "NV");
		for (Login who : List.of(kamA, kamB, ergoMgmt, ofpA, ofpB)) {
			int s = code("GET", PO + "/{id}", who, null, other);
			assertThat(s).as("read at NV").isIn(403, 404);
			s = code("GET", PO + "/{id}/history", who, null, other);
			assertThat(s).as("history at NV").isIn(403, 404);
			String list = text(get(PO), who, null);
			assertThat(list).as("list at NV").doesNotContain("PPO-");
			ok(403, code("POST", PO, who, form(sample)), "raise at NV");
		}
		ok(403, code("PUT", PO + "/{id}", ofpA, edit(), returned), "resubmit at NV");
		ok(403, code("POST", PO + "/{id}/return", kamB, comment("x"), other), "return at NV");
		ok(403, code("POST", PO + "/{id}/create-project", kamB, assign(), other), "create at NV");
		assertThat(text(get(PO + "/eligible-projects"), ofpA, null)).doesNotContain("projectId");
		assertThat(poStatus(returned)).isEqualTo("RETURNED");
		assertThat(poStatus(other)).isEqualTo("SUBMITTED");
		assertThat(auditRows(returned)).isEqualTo(rowsReturned);
		assertThat(pos()).isEqualTo(total);
	}

	@Test
	void withNpdHiddenNobodyCanStartAProductionOrderAndNothingIsCreated() throws Exception {
		long sample = sampleA();
		int total = pos();
		cap("NPD", "NV");
		ok(409, code("POST", PO, ofpA, form(sample)), "raise at NPD NV");
		assertThat(text(get(PO + "/eligible-projects"), ofpA, null)).doesNotContain("projectId");
		cap("NPD", "V");
		assertThat(pos()).isEqualTo(total);
	}

	// ================================================================ 10. notifications

	@Test
	void notificationsGoToTheIntendedPeopleOnlyAndCarryNoOrderDetails() throws Exception {
		long sample = sampleA();
		Login mgmt2 = users.login(ergoCo, "ERGO_MANAGEMENT");
		Login ergoRfq2 = users.login(ergoCo, "ERGO_RFQ");
		long po = raise(ofpA, sample);

		assertThat(heard(kamA, "PRODUCTION_PO_SUBMITTED", po)).as("KAM of the account").isEqualTo(1);
		assertThat(heard(ergoMgmt, "PRODUCTION_PO_SUBMITTED", po)).isEqualTo(1);
		assertThat(heard(mgmt2, "PRODUCTION_PO_SUBMITTED", po)).isEqualTo(1);
		for (Login nobody : List.of(kamB, kamNone, ergoOfp, ergoQuality, ergoNpd, ergoRfq2, ofpA, ofpA2, qualA, custKamA, ofpB, npdB,
				ofpSup, npdSup, qualSup, sellerX)) {
			assertThat(heardAnything(nobody, po)).as("user %d must not hear of the order".formatted(nobody.userId())).isZero();
		}

		call(post(PO + "/{id}/return", po), kamA, comment("SECRET-REASON please fix")).andExpect(status().isOk());
		assertThat(heard(ofpA, "PRODUCTION_PO_RETURNED", po)).isEqualTo(1);
		assertThat(heard(ofpA2, "PRODUCTION_PO_RETURNED", po)).as("every Customer OFP of the company").isEqualTo(1);
		for (Login nobody : List.of(kamA, kamB, kamNone, ergoMgmt, ergoOfp, ergoQuality, qualA, custKamA, npdA, ofpB, qualB,
				ofpSup, npdSup, qualSup, sellerX)) {
			assertThat(heard(nobody, "PRODUCTION_PO_RETURNED", po)).as("user %d must not hear of the return".formatted(nobody.userId())).isZero();
		}

		call(put(PO + "/{id}", po), ofpA, edit()).andExpect(status().isOk());
		assertThat(heard(kamA, "PRODUCTION_PO_SUBMITTED", po)).as("a correction notifies again").isEqualTo(2);
		assertThat(heard(kamB, "PRODUCTION_PO_SUBMITTED", po)).isZero();

		// refused actions send nothing
		long all = count("SELECT count(*) FROM notifications WHERE linked_module = 'production-po' AND linked_record_id = ?", po);
		code("POST", PO + "/{id}/return", ofpA, comment("x"), po);
		code("POST", PO + "/{id}/return", kamA, "{}", po);
		code("PUT", PO + "/{id}", ofpA, edit(), po);
		code("POST", PO + "/{id}/create-project", kamA, assign(ergoNpd.userId(), 1), po);
		assertThat(count("SELECT count(*) FROM notifications WHERE linked_module = 'production-po' AND linked_record_id = ?", po)).isEqualTo(all);

		// payload: the part and the link only
		for (Map<String, Object> n : jdbc.queryForList("SELECT title, message, action_url FROM notifications WHERE linked_module = 'production-po' AND linked_record_id = ?", po)) {
			String text = n.get("title") + " " + n.get("message");
			assertThat(text).doesNotContain("SECRET-REASON").doesNotContain("CPO-").doesNotContain("First lot").doesNotContain("1000").doesNotContain("800")
				.doesNotContainIgnoringCase("margin").doesNotContainIgnoringCase("target");
			assertThat(n.get("action_url")).isEqualTo("/ofp/production-pos/" + po);
		}
		// the notification is deliverable to no outsider: the inbox of the other KAM / customer stays empty of it
		assertThat(text(get("/api/notifications"), kamB, null)).doesNotContain("PPO-");
		assertThat(text(get("/api/notifications"), ofpB, null)).doesNotContain("PPO-");
	}

	@Test
	void acceptingNotifiesOnlyTheTwoAssignees() throws Exception {
		long po = raise(ofpA, sampleA());
		call(post(PO + "/{id}/create-project", po), kamA, assign()).andExpect(status().isCreated());
		long project = jdbc.queryForObject("SELECT npd_project_id FROM npd_projects WHERE production_po_id = ?", Long.class, po);
		for (Login assignee : List.of(ergoOfp, ergoQuality)) {
			assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_kind = 'NPD_PROJECT_ASSIGNED' AND linked_record_id = ?", assignee.userId(), project)).isEqualTo(1);
		}
		for (Login other : List.of(ergoNpd, ofpA, kamB, ofpSup, npdSup)) {
			assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_kind = 'NPD_PROJECT_ASSIGNED' AND linked_record_id = ?", other.userId(), project)).isZero();
		}
		assertThat(jdbc.queryForObject("SELECT owner_user_id FROM npd_projects WHERE npd_project_id = ?", Long.class, project)).isEqualTo(ergoOfp.userId());
		assertThat(jdbc.queryForObject("SELECT quality_owner_user_id FROM npd_projects WHERE npd_project_id = ?", Long.class, project)).isEqualTo(ergoQuality.userId());
		assertThat(jdbc.queryForObject("SELECT customer_company_id FROM npd_projects WHERE npd_project_id = ?", Long.class, project)).isEqualTo(customer);
	}

	// ---------------------------------------------------------------- helpers

	private static List<Long> ids(String json) {
		List<Number> ids = JsonPath.read(json, "$.items[*].id");
		return ids.stream().map(Number::longValue).toList();
	}

	private static String abbreviate(String s) {
		return s.length() > 60 ? s.substring(0, 60) + "..." : s;
	}

}
