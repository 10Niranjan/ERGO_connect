package com.ergo.connect.npd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestNpd;
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.ergo.connect.common.security.AccessPolicy;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.DefaultAccessSheet;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.npd.api.NpdApi;
import com.jayway.jsonpath.JsonPath;

/**
 * Flow doc steps 6-7: the customer's Sample PO, the KAM's decision on it, the project it creates, and the release of the
 * plan to the supplier. Record scope both ways (a customer never reaches another customer's Sample PO, a supplier
 * never reaches a Sample PO or a project whose plan is still being built), the role rules, every state of the Sample PO,
 * the audit trail, notifications and module switches.
 */
@IntegrationTest
class SamplePoTests {

	private static final AtomicInteger SEQ = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AccessPolicy policy;

	@Autowired
	NpdApi npdApi;

	TestUsers users;

	long ergo;

	long customerA;

	long customerB;

	long supplier;

	long supplierB;

	long inr;

	Login ergoRfq;

	Login ergoManagement;

	Login ergoNpd;

	Login ergoNpd2;

	Login ergoQuality;

	Login ergoOfp;

	Login kamA;

	Login kamB;

	Login buyerA;

	Login buyerB;

	Login npdCustomerA;

	Login npdCustomerB;

	Login qualityCustomerA;

	Login seller;

	Login npdSupplier;

	Login qualitySupplier;

	Login ofpSupplier;

	Login npdSupplierB;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		ergo = users.company("ERGO");
		customerA = users.company("CUSTOMER");
		customerB = users.company("CUSTOMER");
		supplier = users.company("SUPPLIER");
		supplierB = users.company("SUPPLIER");
		inr = users.currency("INR");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		ergoManagement = users.login(ergo, "ERGO_MANAGEMENT");
		ergoNpd = users.login(ergo, "ERGO_NPD");
		ergoNpd2 = users.login(ergo, "ERGO_NPD");
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		ergoOfp = users.login(ergo, "ERGO_OFP");
		kamA = users.login(ergo, "ERGO_KAM");
		users.assignAccounts(kamA.userId(), customerA);
		kamB = users.login(ergo, "ERGO_KAM");
		users.assignAccounts(kamB.userId(), customerB);
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		buyerB = users.login(customerB, "CUSTOMER_RFQ");
		npdCustomerA = users.login(customerA, "CUSTOMER_NPD");
		npdCustomerB = users.login(customerB, "CUSTOMER_NPD");
		qualityCustomerA = users.login(customerA, "CUSTOMER_QUALITY");
		seller = users.login(supplier, "SUPPLIER_RFQ");
		npdSupplier = users.login(supplier, "SUPPLIER_NPD");
		qualitySupplier = users.login(supplier, "SUPPLIER_QUALITY");
		ofpSupplier = users.login(supplier, "SUPPLIER_OFP");
		npdSupplierB = users.login(supplierB, "SUPPLIER_NPD");
	}

	@AfterEach
	void restoreShippedSheet() {
		jdbc.update("UPDATE app_modules SET access_cap = 'O'");
		for (Role role : Role.values()) {
			jdbc.update("UPDATE role_module_permissions SET access_level = ? WHERE role_code = ? AND module_code = 'NPD'",
					DefaultAccessSheet.code(role, "NPD"), role.name());
		}
		jdbc.update("UPDATE access_config SET version = version + 1");
		policy.refresh();
	}

	// ---------------------------------------------------------------- raising

	@Test
	void onlyCustomerNpdRaisesASamplePoAndItIsSubmittedToTheKam() throws Exception {
		long part = approvedPart(buyerA, "PN-RAISE");
		String form = TestNpd.samplePoForm(part);

		for (Login other : List.of(buyerA, qualityCustomerA, seller, npdSupplier, ergoManagement, kamA,
				ergoNpd, ergoQuality, ergoRfq)) {
			call(post("/api/npd/sample-pos"), other, form).andExpect(status().isForbidden());
		}
		assertThat(count("SELECT count(*) FROM sample_pos WHERE rfq_item_id = ?", part)).isZero();

		String supplierName = nameOf(supplier);
		String json = call(post("/api/npd/sample-pos"), npdCustomerA, form).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.partNumber").value("PN-RAISE"))
			.andExpect(jsonPath("$.customerName").value(nameOf(customerA)))
			.andExpect(jsonPath("$.quantity").value(25))
			.andExpect(jsonPath("$.npdProjectId").doesNotExist())
			.andExpect(jsonPath("$.actions", empty()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat((String) JsonPath.read(json, "$.samplePoNumber")).matches("SPO-\\d{4}-\\d{5}");
		assertThat(json).doesNotContain(supplierName);
		// the customer's own view carries no ERGO-only part data
		assertThat(json).doesNotContain("\"description\":\"Bracket\"");
	}

	@Test
	void eligiblePartsAreTheCallersOwnApprovedPartsAndShrinkOnceASamplePoIsRaised() throws Exception {
		long partA1 = approvedPart(buyerA, "PN-E1");
		long partA2 = approvedPart(buyerA, "PN-E2");
		long partB = approvedPart(buyerB, "PN-E3");
		long notApproved = unapprovedPart(buyerA, "PN-E4");

		call(get("/api/npd/sample-pos/eligible-parts"), npdCustomerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$[*].rfqItemId", hasItem((int) partA1)))
			.andExpect(jsonPath("$[*].rfqItemId", hasItem((int) partA2)))
			.andExpect(jsonPath("$[*].rfqItemId", not(hasItem((int) partB))))
			.andExpect(jsonPath("$[*].rfqItemId", not(hasItem((int) notApproved))))
			.andExpect(jsonPath("$[?(@.rfqItemId == %d)].actions[0]".formatted(partA1)).value(hasItem("RAISE")));
		call(get("/api/npd/sample-pos/eligible-parts"), npdCustomerB, null)
			.andExpect(jsonPath("$[*].rfqItemId", hasItem((int) partB)))
			.andExpect(jsonPath("$[*].rfqItemId", not(hasItem((int) partA1))));

		TestNpd.raise(mvc, npdCustomerA, partA1);
		call(get("/api/npd/sample-pos/eligible-parts"), npdCustomerA, null)
			.andExpect(jsonPath("$[*].rfqItemId", not(hasItem((int) partA1))))
			.andExpect(jsonPath("$[*].rfqItemId", hasItem((int) partA2)));

		// only the raising role has the list at all
		for (Login other : List.of(buyerA, qualityCustomerA, seller, ergoManagement, kamA)) {
			call(get("/api/npd/sample-pos/eligible-parts"), other, null).andExpect(status().isForbidden());
		}
	}

	@Test
	void anotherCustomerNeverRaisesSeesOrChangesASamplePoItDoesNotOwn() throws Exception {
		long partA = approvedPart(buyerA, "PN-ISO");
		long po = TestNpd.raise(mvc, npdCustomerA, partA);

		call(post("/api/npd/sample-pos"), npdCustomerB, TestNpd.samplePoForm(partA)).andExpect(status().isNotFound());
		call(get("/api/npd/sample-pos/{id}", po), npdCustomerB, null).andExpect(status().isNotFound());
		call(get("/api/npd/sample-pos/{id}", po), buyerB, null).andExpect(status().isNotFound());
		call(put("/api/npd/sample-pos/{id}", po), npdCustomerB, editForm()).andExpect(status().isNotFound());
		call(get("/api/npd/sample-pos"), npdCustomerB, null).andExpect(jsonPath("$[*].id", not(hasItem((int) po))));
		call(get("/api/npd/sample-pos/{id}/history", po), npdCustomerB, null).andExpect(status().isNotFound());
		// a customer decides nothing
		call(post("/api/npd/sample-pos/{id}/return", po), npdCustomerB, "{\"comment\": \"no\"}")
			.andExpect(status().isForbidden());
		call(post("/api/npd/sample-pos/{id}/create-project", po), npdCustomerA, assign(ergoNpd, ergoQuality))
			.andExpect(status().isForbidden());
		assertThat(poStatus(po)).isEqualTo("SUBMITTED");
	}

	@Test
	void aPartThatIsNotApprovedUnknownOrAlreadyHasASamplePoIsRefused() throws Exception {
		long approved = approvedPart(buyerA, "PN-REF");
		long notApproved = unapprovedPart(buyerA, "PN-REF2");
		long partB = approvedPart(buyerB, "PN-REF3");

		call(post("/api/npd/sample-pos"), npdCustomerA, TestNpd.samplePoForm(notApproved))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("no approved quotation")));
		call(post("/api/npd/sample-pos"), npdCustomerA, TestNpd.samplePoForm(999_999_999L)).andExpect(status().isNotFound());
		call(post("/api/npd/sample-pos"), npdCustomerA, TestNpd.samplePoForm(partB)).andExpect(status().isNotFound());

		TestNpd.raise(mvc, npdCustomerA, approved);
		call(post("/api/npd/sample-pos"), npdCustomerA, TestNpd.samplePoForm(approved)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail", containsString("already exists")));
		assertThat(count("SELECT count(*) FROM sample_pos WHERE rfq_item_id = ?", approved)).isEqualTo(1);
	}

	@Test
	void aPartThatAlreadyHasAnNpdProjectCannotGetASamplePoAndIsNotOffered() throws Exception {
		long part = approvedPart(buyerA, "PN-OLD");
		long rfq = jdbc.queryForObject("SELECT rfq_id FROM rfq_items WHERE rfq_item_id = ?", Long.class, part);
		// a project that predates Sample POs: created straight in the database, no Sample PO behind it
		jdbc.update("""
				INSERT INTO npd_projects (npd_number, source_rfq_id, source_rfq_item_id, customer_company_id,
				                          supplier_company_id, part_number, stage, created_by_user_id)
				VALUES (?, ?, ?, ?, ?, 'PN-OLD', 'PLANNING', ?)""", "SP" + (800_000 + SEQ.incrementAndGet()), rfq, part,
				customerA, supplier, ergoManagement.userId());

		call(post("/api/npd/sample-pos"), npdCustomerA, TestNpd.samplePoForm(part)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail", containsString("already has an NPD project")));
		call(get("/api/npd/sample-pos/eligible-parts"), npdCustomerA, null)
			.andExpect(jsonPath("$[*].rfqItemId", not(hasItem((int) part))));
		assertThat(count("SELECT count(*) FROM sample_pos WHERE rfq_item_id = ?", part)).isZero();
	}

	@Test
	void theFormIsValidated() throws Exception {
		long part = approvedPart(buyerA, "PN-VAL");
		String ok = "\"rfqItemId\": %d, \"customerPoNumber\": \"PO-V\", \"quantity\": 5, \"requiredDate\": \"%s\"".formatted(
				part, LocalDate.now().plusDays(10));
		List<String> bad = List.of(
				// missing / blank / too long PO number
				"{\"rfqItemId\": %d, \"quantity\": 5, \"requiredDate\": \"%s\"}".formatted(part, LocalDate.now().plusDays(10)),
				"{\"rfqItemId\": %d, \"customerPoNumber\": \"  \", \"quantity\": 5, \"requiredDate\": \"%s\"}".formatted(part,
						LocalDate.now().plusDays(10)),
				"{\"rfqItemId\": %d, \"customerPoNumber\": \"%s\", \"quantity\": 5, \"requiredDate\": \"%s\"}".formatted(
						part, "X".repeat(61), LocalDate.now().plusDays(10)),
				// quantity: missing, zero, negative
				"{\"rfqItemId\": %d, \"customerPoNumber\": \"PO-V\", \"requiredDate\": \"%s\"}".formatted(part,
						LocalDate.now().plusDays(10)),
				"{\"rfqItemId\": %d, \"customerPoNumber\": \"PO-V\", \"quantity\": 0, \"requiredDate\": \"%s\"}".formatted(
						part, LocalDate.now().plusDays(10)),
				"{\"rfqItemId\": %d, \"customerPoNumber\": \"PO-V\", \"quantity\": -3, \"requiredDate\": \"%s\"}".formatted(
						part, LocalDate.now().plusDays(10)),
				// required date: missing, in the past
				"{\"rfqItemId\": %d, \"customerPoNumber\": \"PO-V\", \"quantity\": 5}".formatted(part),
				"{\"rfqItemId\": %d, \"customerPoNumber\": \"PO-V\", \"quantity\": 5, \"requiredDate\": \"%s\"}".formatted(
						part, LocalDate.now().minusDays(1)),
				// part missing; remarks too long
				"{\"customerPoNumber\": \"PO-V\", \"quantity\": 5, \"requiredDate\": \"%s\"}".formatted(
						LocalDate.now().plusDays(10)),
				"{" + ok + ", \"remarks\": \"" + "r".repeat(2001) + "\"}");
		for (String body : bad) {
			call(post("/api/npd/sample-pos"), npdCustomerA, body).andExpect(status().isBadRequest());
		}
		assertThat(count("SELECT count(*) FROM sample_pos WHERE rfq_item_id = ?", part)).isZero();
		// today is a valid required date
		call(post("/api/npd/sample-pos"), npdCustomerA, "{\"rfqItemId\": %d, \"customerPoNumber\": \"PO-V\", \"quantity\": 5, \"requiredDate\": \"%s\"}"
			.formatted(part, LocalDate.now())).andExpect(status().isCreated());
	}

	// ---------------------------------------------------------------- who sees which Sample PO

	@Test
	void sampleOrdersAreVisibleToTheOwnCustomerAndErgoWithinScopeButNeverToASupplier() throws Exception {
		long po = TestNpd.raise(mvc, npdCustomerA, approvedPart(buyerA, "PN-SEE"));
		String supplierName = nameOf(supplier);

		// the customer's own roles with NPD access, ERGO's account-scoped KAM and the ERGO roles with NPD access
		for (Login viewer : List.of(npdCustomerA, qualityCustomerA, buyerA, kamA, ergoManagement, ergoNpd,
				ergoQuality, ergoOfp)) {
			call(get("/api/npd/sample-pos"), viewer, null).andExpect(status().isOk())
				.andExpect(jsonPath("$[*].id", hasItem((int) po)));
			String json = call(get("/api/npd/sample-pos/{id}", po), viewer, null).andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
			assertThat(json).doesNotContain(supplierName);
		}
		// another KAM (not assigned to this customer) and another customer see nothing of it
		for (Login outsider : List.of(kamB, npdCustomerB, buyerB)) {
			call(get("/api/npd/sample-pos"), outsider, null).andExpect(jsonPath("$[*].id", not(hasItem((int) po))));
			call(get("/api/npd/sample-pos/{id}", po), outsider, null).andExpect(status().isNotFound());
		}
		// a supplier role has no access to Sample POs at all
		for (Login any : List.of(seller, npdSupplier, qualitySupplier, ofpSupplier)) {
			call(get("/api/npd/sample-pos"), any, null).andExpect(status().isForbidden());
			call(get("/api/npd/sample-pos/{id}", po), any, null).andExpect(status().isForbidden());
			call(get("/api/npd/sample-pos/{id}/history", po), any, null).andExpect(status().isForbidden());
		}

		// ERGO reads the part's RFQ data; the customer's view leaves it out
		call(get("/api/npd/sample-pos/{id}", po), ergoManagement, null).andExpect(jsonPath("$.description").value("Bracket"))
			.andExpect(jsonPath("$.customerName").value(nameOf(customerA)));
		call(get("/api/npd/sample-pos/{id}", po), npdCustomerA, null).andExpect(jsonPath("$.description").doesNotExist());
	}

	// ---------------------------------------------------------------- returning and correcting

	@Test
	void returningNeedsACommentAndOnlyTheKamOfThatAccountOrManagementMayDoIt() throws Exception {
		long po = TestNpd.raise(mvc, npdCustomerA, approvedPart(buyerA, "PN-RET"));

		call(post("/api/npd/sample-pos/{id}/return", po), kamA, "{}").andExpect(status().isBadRequest());
		call(post("/api/npd/sample-pos/{id}/return", po), kamA, "{\"comment\": \"   \"}").andExpect(status().isBadRequest());
		call(post("/api/npd/sample-pos/{id}/return", po), kamB, "{\"comment\": \"nope\"}").andExpect(status().isNotFound());
		for (Login other : List.of(ergoNpd, ergoQuality, ergoOfp, ergoRfq, npdCustomerA, seller, npdSupplier)) {
			call(post("/api/npd/sample-pos/{id}/return", po), other, "{\"comment\": \"nope\"}")
				.andExpect(status().isForbidden());
		}
		assertThat(poStatus(po)).isEqualTo("SUBMITTED");

		call(post("/api/npd/sample-pos/{id}/return", po), kamA, "{\"comment\": \"Quantity must be 50\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RETURNED"))
			.andExpect(jsonPath("$.returnComment").value("Quantity must be 50"))
			.andExpect(jsonPath("$.actions", empty()));
		// the customer is told what to fix and may now edit; nobody else may decide a returned one
		call(get("/api/npd/sample-pos/{id}", po), npdCustomerA, null).andExpect(jsonPath("$.status").value("RETURNED"))
			.andExpect(jsonPath("$.returnComment").value("Quantity must be 50"))
			.andExpect(jsonPath("$.actions[0]").value("EDIT"));
		call(post("/api/npd/sample-pos/{id}/return", po), kamA, "{\"comment\": \"again\"}").andExpect(status().isConflict());
		call(post("/api/npd/sample-pos/{id}/create-project", po), kamA, assign(ergoNpd, ergoQuality))
			.andExpect(status().isConflict());
		assertThat(count("SELECT count(*) FROM npd_projects WHERE sample_po_id = ?", po)).isZero();

		// ERGO Management also decides
		long other = TestNpd.raise(mvc, npdCustomerB, approvedPart(buyerB, "PN-RET2"));
		call(post("/api/npd/sample-pos/{id}/return", other), ergoManagement, "{\"comment\": \"Wrong date\"}")
			.andExpect(status().isOk());
	}

	@Test
	void theCustomerCorrectsAReturnedSamplePoAndItGoesBackToTheKam() throws Exception {
		long po = TestNpd.raise(mvc, npdCustomerA, approvedPart(buyerA, "PN-FIX"));
		String correction = editForm();

		// not yet returned: nothing to correct
		call(put("/api/npd/sample-pos/{id}", po), npdCustomerA, correction).andExpect(status().isConflict());
		call(post("/api/npd/sample-pos/{id}/return", po), kamA, "{\"comment\": \"Fix the quantity\"}").andExpect(status().isOk());

		// only Customer NPD of that company
		for (Login other : List.of(qualityCustomerA, buyerA, seller, npdSupplier, kamA, ergoManagement)) {
			call(put("/api/npd/sample-pos/{id}", po), other, correction).andExpect(status().isForbidden());
		}
		call(put("/api/npd/sample-pos/{id}", po), npdCustomerB, correction).andExpect(status().isNotFound());
		// the form is validated again
		call(put("/api/npd/sample-pos/{id}", po), npdCustomerA,
				"{\"customerPoNumber\": \"PO-2\", \"quantity\": 0, \"requiredDate\": \"%s\"}".formatted(LocalDate.now().plusDays(9)))
			.andExpect(status().isBadRequest());
		call(put("/api/npd/sample-pos/{id}", po), npdCustomerA,
				"{\"customerPoNumber\": \"PO-2\", \"quantity\": 4, \"requiredDate\": \"%s\"}".formatted(LocalDate.now().minusDays(2)))
			.andExpect(status().isBadRequest());
		assertThat(poStatus(po)).isEqualTo("RETURNED");

		call(put("/api/npd/sample-pos/{id}", po), npdCustomerA, correction).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.customerPoNumber").value("PO-FIXED"))
			.andExpect(jsonPath("$.quantity").value(50))
			.andExpect(jsonPath("$.returnComment").doesNotExist());
		// once resubmitted it can be corrected no further, until it is returned again
		call(put("/api/npd/sample-pos/{id}", po), npdCustomerA, correction).andExpect(status().isConflict());
		call(post("/api/npd/sample-pos/{id}/create-project", po), kamA, assign(ergoNpd, ergoQuality))
			.andExpect(status().isCreated());
		call(put("/api/npd/sample-pos/{id}", po), npdCustomerA, correction).andExpect(status().isConflict());
	}

	// ---------------------------------------------------------------- accepting: the project

	@Test
	void theKamCreatesTheProjectFromTheSamplePoAssigningErgoNpdAndErgoQuality() throws Exception {
		long part = approvedPart(buyerA, "PN-PRJ");
		long po = TestNpd.raise(mvc, npdCustomerA, part);
		LocalDate required = LocalDate.parse(JsonPath.read(
				call(get("/api/npd/sample-pos/{id}", po), kamA, null).andReturn().getResponse().getContentAsString(),
				"$.requiredDate"));

		// only the KAM of that account and Management; every other role is refused and nothing is created
		call(post("/api/npd/sample-pos/{id}/create-project", po), kamB, assign(ergoNpd, ergoQuality))
			.andExpect(status().isNotFound());
		for (Login other : List.of(ergoNpd, ergoQuality, ergoOfp, ergoRfq, npdCustomerA, seller, npdSupplier)) {
			call(post("/api/npd/sample-pos/{id}/create-project", po), other, assign(ergoNpd, ergoQuality))
				.andExpect(status().isForbidden());
		}
		assertThat(count("SELECT count(*) FROM npd_projects WHERE sample_po_id = ?", po)).isZero();

		String project = call(post("/api/npd/sample-pos/{id}/create-project", po), kamA,
				"{\"ergoNpdUserId\": %d, \"ergoQualityUserId\": %d, \"comment\": \"Kick-off with the sample team\"}"
					.formatted(ergoNpd.userId(), ergoQuality.userId()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.audience").value("ERGO"))
			.andExpect(jsonPath("$.stage").value("PLANNING"))
			.andExpect(jsonPath("$.planStatus").value("AWAITING_PLAN"))
			.andExpect(jsonPath("$.ownerUserId").value((int) ergoNpd.userId()))
			.andExpect(jsonPath("$.qualityOwnerUserId").value((int) ergoQuality.userId()))
			.andExpect(jsonPath("$.samplePoId").value((int) po))
			.andExpect(jsonPath("$.targetSampleDate").value(required.toString()))
			.andExpect(jsonPath("$.partNumber").value("PN-PRJ"))
			.andExpect(jsonPath("$.stageHistory", hasSize(1)))
			.andExpect(jsonPath("$.stageHistory[0].stage").value("PLANNING"))
			.andExpect(jsonPath("$.stageHistory[0].remarks").value("Kick-off with the sample team"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long projectId = JsonPath.<Number>read(project, "$.id").longValue();
		assertThat((String) JsonPath.read(project, "$.samplePoNumber")).startsWith("SPO-");

		// the Sample PO is accepted and points at the project
		call(get("/api/npd/sample-pos/{id}", po), npdCustomerA, null).andExpect(jsonPath("$.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.npdProjectId").value((int) projectId))
			.andExpect(jsonPath("$.npdNumber").isNotEmpty())
			.andExpect(jsonPath("$.actions", empty()));
		assertThat(jdbc.queryForObject("SELECT sample_po_id FROM npd_projects WHERE npd_project_id = ?", Long.class, projectId))
			.isEqualTo(po);
		assertThat(jdbc.queryForObject("SELECT quality_owner_user_id FROM npd_projects WHERE npd_project_id = ?", Long.class,
				projectId)).isEqualTo(ergoQuality.userId());
		// ... and a second acceptance is a conflict, not a second project
		call(post("/api/npd/sample-pos/{id}/create-project", po), kamA, assign(ergoNpd, ergoQuality))
			.andExpect(status().isConflict());
		call(post("/api/npd/sample-pos/{id}/create-project", po), ergoManagement, assign(ergoNpd, ergoQuality))
			.andExpect(status().isConflict());
		assertThat(count("SELECT count(*) FROM npd_projects WHERE sample_po_id = ?", po)).isEqualTo(1);

		// Management can accept too (no account assignment needed)
		long other = TestNpd.raise(mvc, npdCustomerB, approvedPart(buyerB, "PN-PRJ2"));
		call(post("/api/npd/sample-pos/{id}/create-project", other), ergoManagement, assign(ergoNpd, ergoQuality))
			.andExpect(status().isCreated());
	}

	@Test
	void theAssigneesMustBeActiveUsersHoldingTheRightErgoRole() throws Exception {
		long po = TestNpd.raise(mvc, npdCustomerA, approvedPart(buyerA, "PN-ASG"));
		Login retiredNpd = users.login(ergo, "ERGO_NPD");
		jdbc.update("UPDATE users SET active = false WHERE user_id = ?", retiredNpd.userId());

		List<String> bad = List.of(
				assign(ergoQuality, ergoQuality), // an ERGO Quality user as the NPD lead
				assign(ergoNpd, ergoNpd2), // an ERGO NPD user as the Quality lead
				assign(retiredNpd, ergoQuality), // inactive
				assign(npdSupplier, ergoQuality), // another company's user, even with an NPD role
				assign(ergoNpd, npdCustomerA),
				"{\"ergoNpdUserId\": 999999999, \"ergoQualityUserId\": %d}".formatted(ergoQuality.userId()), // unknown
				"{\"ergoNpdUserId\": %d, \"ergoQualityUserId\": 999999999}".formatted(ergoNpd.userId()));
		for (String body : bad) {
			call(post("/api/npd/sample-pos/{id}/create-project", po), kamA, body).andExpect(status().isUnprocessableContent());
		}
		// both are required
		call(post("/api/npd/sample-pos/{id}/create-project", po), kamA, "{\"ergoNpdUserId\": %d}".formatted(ergoNpd.userId()))
			.andExpect(status().isBadRequest());
		call(post("/api/npd/sample-pos/{id}/create-project", po), kamA, "{}").andExpect(status().isBadRequest());
		assertThat(poStatus(po)).isEqualTo("SUBMITTED");
		assertThat(count("SELECT count(*) FROM npd_projects WHERE sample_po_id = ?", po)).isZero();

		// the KAM's pick list holds exactly the active NPD and Quality users
		call(get("/api/npd/sample-pos/assignees"), kamA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.npd[*].id", hasItem((int) ergoNpd.userId())))
			.andExpect(jsonPath("$.npd[*].id", not(hasItem((int) retiredNpd.userId()))))
			.andExpect(jsonPath("$.npd[*].id", not(hasItem((int) ergoQuality.userId()))))
			.andExpect(jsonPath("$.quality[*].id", hasItem((int) ergoQuality.userId())))
			.andExpect(jsonPath("$.quality[*].id", not(hasItem((int) ergoNpd.userId()))));
		for (Login other : List.of(ergoNpd, ergoQuality, ergoOfp, npdCustomerA, seller, npdSupplier)) {
			call(get("/api/npd/sample-pos/assignees"), other, null).andExpect(status().isForbidden());
		}
		call(get("/api/npd/sample-pos/assignees"), ergoManagement, null).andExpect(status().isOk());
	}

	@Test
	void everyStepOfASamplePoIsInTheAuditTrailWithItsReasonAndOnlyErgoReadsIt() throws Exception {
		long po = TestNpd.raise(mvc, npdCustomerA, approvedPart(buyerA, "PN-AUD"));
		call(post("/api/npd/sample-pos/{id}/return", po), kamA, "{\"comment\": \"Quantity must be 50\"}").andExpect(status().isOk());
		call(put("/api/npd/sample-pos/{id}", po), npdCustomerA, editForm()).andExpect(status().isOk());
		call(post("/api/npd/sample-pos/{id}/create-project", po), kamA, assign(ergoNpd, ergoQuality))
			.andExpect(status().isCreated());

		String history = call(get("/api/npd/sample-pos/{id}/history", po), kamA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$[*].event", hasItem("CREATE")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(JsonPath.<List<String>>read(history, "$[?(@.field == 'status')].newValue")).contains("RETURNED", "SUBMITTED",
				"ACCEPTED");
		assertThat(JsonPath.<List<String>>read(history, "$[?(@.newValue == 'RETURNED')].reason"))
			.containsExactly("Quantity must be 50");
		assertThat(JsonPath.<List<String>>read(history, "$[?(@.field == 'customerPoNumber')].newValue")).contains("PO-FIXED");
		call(get("/api/npd/sample-pos/{id}/history", po), ergoManagement, null).andExpect(status().isOk());
		// the customer's own Sample PO, but the trail is ERGO-internal
		for (Login customer : List.of(npdCustomerA, qualityCustomerA, buyerA)) {
			call(get("/api/npd/sample-pos/{id}/history", po), customer, null).andExpect(status().isForbidden());
		}
		call(get("/api/npd/sample-pos/{id}/history", po), kamB, null).andExpect(status().isNotFound());
		assertThat(jdbc.queryForList("""
				SELECT event_type FROM audit_logs WHERE entity_name = 'NpdProject'
				AND record_id = (SELECT npd_project_id FROM npd_projects WHERE sample_po_id = ?)""", String.class, po))
			.contains("CREATE");
	}

	// ---------------------------------------------------------------- the plan: supplier visibility and release

	@Test
	void theSupplierDoesNotKnowTheProjectExistsWhileErgoNpdIsStillBuildingThePlan() throws Exception {
		long part = approvedPart(buyerA, "PN-HID");
		long project = TestNpd.start(mvc, npdCustomerA, kamA, ergoNpd, ergoQuality, part);
		TestNpd.addSupplierStep(mvc, ergoNpd, project, supplier, "Raw material timeline", true);

		for (Login external : List.of(npdSupplier, seller, qualitySupplier)) {
			call(get("/api/npd/{id}", project), external, null).andExpect(status().isNotFound());
			call(get("/api/npd/mine"), external, null).andExpect(jsonPath("$", hasSize(0)));
		}
		call(get("/api/dashboard"), npdSupplier, null).andExpect(jsonPath("$.npdItems[*].id", not(hasItem((int) project))));
		// every other write path to the project is closed to the supplier as well
		call(post("/api/npd/activities/{id}/progress", firstActivity(project)), npdSupplier,
				"{\"status\": \"IN_PROGRESS\", \"issueFlag\": false, \"riskFlag\": false}").andExpect(status().isNotFound());
		// the customer, on the other hand, sees its own project at once, with no supplier in it
		call(get("/api/npd/{id}", project), npdCustomerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CUSTOMER"));
		// no supplier was told about a step of a plan it cannot see
		for (Login external : List.of(npdSupplier, seller, qualitySupplier, ofpSupplier)) {
			assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND linked_module IN ('npd', 'sample-po')",
					external.userId())).isZero();
		}

		// a quality record cannot be linked to a project whose plan is not released: it carries the project and part
		call(post("/api/quality/inspections"), ergoQuality, inspection(project)).andExpect(status().isUnprocessableContent());
		call(post("/api/quality/inspections"), qualitySupplier, inspection(project)).andExpect(status().isUnprocessableContent());
		assertThat(count("SELECT count(*) FROM inspections WHERE linked_module = 'NPD' AND linked_record_id = ?", project))
			.isZero();

		TestNpd.assignPlan(mvc, ergoNpd, project);
		call(post("/api/quality/inspections"), ergoQuality, inspection(project)).andExpect(status().isCreated());
		for (Login external : List.of(npdSupplier, seller, qualitySupplier)) {
			call(get("/api/npd/{id}", project), external, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.audience").value("SUPPLIER"));
			call(get("/api/npd/mine"), external, null).andExpect(jsonPath("$[*].id", hasItem((int) project)));
		}
		call(get("/api/dashboard"), npdSupplier, null).andExpect(jsonPath("$.npdItems[*].id", hasItem((int) project)));
		// a project of another supplier company stays invisible
		call(get("/api/npd/{id}", project), npdSupplierB, null).andExpect(status().isNotFound());
		// a supplier's OFP team reaches OFP projects only: a sample project does not exist for it
		call(get("/api/npd/{id}", project), ofpSupplier, null).andExpect(status().isNotFound());
	}

	@Test
	void assigningThePlanNeedsASupplierStepAndOnlyErgoNpdOrManagementReleasesIt() throws Exception {
		long project = TestNpd.start(mvc, npdCustomerA, kamA, ergoNpd, ergoQuality, approvedPart(buyerA, "PN-ASN"));

		// the project is awaiting its plan and ERGO NPD may release it, but there is nothing for the supplier yet
		call(get("/api/npd/{id}", project), ergoNpd, null).andExpect(jsonPath("$.actions[*]", hasItem("ASSIGN_PLAN")));
		call(post("/api/npd/{id}/plan/assign", project), ergoNpd, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail", containsString("step for the supplier")));
		// an ERGO step or the customer's step is not a step for the supplier
		call(post("/api/npd/{id}/activities", project), ergoNpd, ownedStep("ERGO step", "ERGO", null)).andExpect(status().isCreated());
		call(post("/api/npd/{id}/activities", project), ergoNpd, ownedStep("Customer step", "CUSTOMER", customerA))
			.andExpect(status().isCreated());
		call(post("/api/npd/{id}/plan/assign", project), ergoNpd, "{}").andExpect(status().isConflict());
		TestNpd.addSupplierStep(mvc, ergoNpd, project, supplier, "Tooling readiness", true);

		// ERGO OFP owns OFP projects only: on a sample project the action is simply not there
		call(post("/api/npd/{id}/plan/assign", project), ergoOfp, "{}").andExpect(status().isConflict());
		for (Login other : List.of(kamA, ergoQuality, ergoRfq, npdCustomerA, buyerA, seller, npdSupplier)) {
			call(post("/api/npd/{id}/plan/assign", project), other, "{}").andExpect(status().isForbidden());
		}
		call(post("/api/npd/{id}/plan/assign", project), kamB, "{}").andExpect(status().isForbidden());
		assertThat(planStatus(project)).isEqualTo("AWAITING_PLAN");

		call(post("/api/npd/{id}/plan/assign", project), ergoNpd, "{\"comment\": \"Plan agreed with Quality\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.planStatus").value("ASSIGNED_TO_SUPPLIER"))
			.andExpect(jsonPath("$.actions[*]", not(hasItem("ASSIGN_PLAN"))));
		assertThat(planStatus(project)).isEqualTo("ASSIGNED_TO_SUPPLIER");
		// once is enough
		call(post("/api/npd/{id}/plan/assign", project), ergoNpd, "{}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/plan/assign", project), ergoManagement, "{}").andExpect(status().isConflict());
		assertThat(jdbc.queryForObject("""
				SELECT action_reason FROM audit_logs WHERE entity_name = 'NpdProject' AND record_id = ?
				AND field_name = 'planStatus' ORDER BY audit_log_id DESC LIMIT 1""", String.class, project))
			.isEqualTo("Plan agreed with Quality");

		// Management releases a plan too
		long other = TestNpd.start(mvc, npdCustomerA, ergoManagement, ergoNpd, ergoQuality, approvedPart(buyerA, "PN-ASN2"));
		TestNpd.addSupplierStep(mvc, ergoNpd, other, supplier, "Tooling readiness", true);
		call(post("/api/npd/{id}/plan/assign", other), ergoManagement, "{}").andExpect(status().isOk());
	}

	@Test
	void theSupplierSeesOnlyItsOwnStepsAndTheCustomerOnlyItsOwnAfterTheReleaseWithNoDataOfTheOtherSide() throws Exception {
		long part = approvedPart(buyerA, "PN-DATA");
		long po = TestNpd.raise(mvc, npdCustomerA, part);
		long project = TestNpd.createProject(mvc, kamA, po, ergoNpd, ergoQuality, null);
		call(post("/api/npd/{id}/activities", project), ergoNpd, ownedStep("ERGO step", "ERGO", null)).andExpect(status().isCreated());
		call(post("/api/npd/{id}/activities", project), ergoNpd, ownedStep("Customer step", "CUSTOMER", customerA))
			.andExpect(status().isCreated());
		TestNpd.addSupplierStep(mvc, ergoNpd, project, supplier, "Supplier step", true);
		TestNpd.assignPlan(mvc, ergoNpd, project);

		String poNumber = jdbc.queryForObject("SELECT sample_po_number FROM sample_pos WHERE sample_po_id = ?", String.class, po);
		String customerPoNumber = jdbc.queryForObject("SELECT customer_po_number FROM sample_pos WHERE sample_po_id = ?",
				String.class, po);
		String supplierView = call(get("/api/npd/{id}", project), npdSupplier, null).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(JsonPath.<List<String>>read(supplierView, "$.activities[*].activityName")).containsExactly("Supplier step");
		assertThat(supplierView).doesNotContain(nameOf(customerA)).doesNotContain(poNumber).doesNotContain(customerPoNumber)
			.doesNotContain("samplePo").doesNotContain("qualityOwner");
		assertThat(supplierView).doesNotContain("\"customer\"");

		String customerView = call(get("/api/npd/{id}", project), npdCustomerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.samplePoId").value((int) po))
			.andExpect(jsonPath("$.samplePoNumber").value(poNumber))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(JsonPath.<List<String>>read(customerView, "$.activities[*].activityName")).containsExactly("Customer step");
		assertThat(customerView).doesNotContain(nameOf(supplier)).doesNotContain("\"supplier\"").doesNotContain("planStatus")
			.doesNotContain("qualityOwner");
		// the customer's list rows carry no supplier either
		assertThat(call(get("/api/npd/mine"), npdCustomerA, null).andReturn().getResponse().getContentAsString())
			.doesNotContain(nameOf(supplier));

		String ergoView = call(get("/api/npd/{id}", project), ergoNpd, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.planStatus").value("ASSIGNED_TO_SUPPLIER"))
			.andExpect(jsonPath("$.qualityOwner").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(JsonPath.<List<String>>read(ergoView, "$.activities[*].activityName"))
			.containsExactlyInAnyOrder("ERGO step", "Customer step", "Supplier step");
		call(get("/api/npd?size=100"), ergoNpd, null).andExpect(jsonPath("$.items[?(@.id == %d)].planStatus".formatted(project)).value(hasItem("ASSIGNED_TO_SUPPLIER")));
	}

	@Test
	void aProjectCannotBeMovedOnWhileItsPlanIsBeingBuilt() throws Exception {
		long project = TestNpd.start(mvc, npdCustomerA, kamA, ergoNpd, ergoQuality, approvedPart(buyerA, "PN-CLS"));

		call(get("/api/npd/{id}", project), ergoNpd, null).andExpect(jsonPath("$.actions[*]", not(hasItem("CLOSE_STAGE"))));
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/close-stage", project), ergoManagement, "{}").andExpect(status().isConflict());
		assertThat(stage(project)).isEqualTo("PLANNING");

		TestNpd.addSupplierStep(mvc, ergoNpd, project, supplier, "Tooling readiness", false);
		TestNpd.assignPlan(mvc, ergoNpd, project);
		// released is not enough: the plan must be submitted by the supplier and agreed by ERGO NPD
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isConflict());
		TestNpd.submitPlan(mvc, npdSupplier, project);
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isConflict());
		TestNpd.agreePlan(mvc, ergoNpd, project);
		call(get("/api/npd/{id}", project), ergoNpd, null).andExpect(jsonPath("$.actions[*]", hasItem("CLOSE_STAGE")));
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk())
			.andExpect(jsonPath("$.stage").value("COMMERCIAL_SOURCING_READINESS"));
	}

	@Test
	void thePlanStepsCanBePlannedIntoLaterStagesAndMovedOnlyWhileThePlanIsBeingBuilt() throws Exception {
		long project = TestNpd.start(mvc, npdCustomerA, kamA, ergoNpd, ergoQuality, approvedPart(buyerA, "PN-STG"));

		// no stage = the current one; a later stage is allowed; an earlier one or CLOSED is not
		String current = call(post("/api/npd/{id}/activities", project), ergoNpd, ownedStepIn("Now", "ERGO", null, null))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(current, "$.activities[?(@.activityName == 'Now')].phaseName"))
			.containsExactly("PLANNING");
		String later = call(post("/api/npd/{id}/activities", project), ergoNpd,
				ownedStepIn("Tooling readiness", "SUPPLIER", supplier, "TOOLING_MATERIAL_RELEASE")).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(later, "$.activities[?(@.activityName == 'Tooling readiness')].phaseName"))
			.containsExactly("TOOLING_MATERIAL_RELEASE");
		call(post("/api/npd/{id}/activities", project), ergoNpd, ownedStepIn("Too early", "ERGO", null, "PENDING_CONVERSION"))
			.andExpect(status().isUnprocessableContent());
		call(post("/api/npd/{id}/activities", project), ergoNpd, ownedStepIn("Too late", "ERGO", null, "CLOSED"))
			.andExpect(status().isUnprocessableContent());
		assertThat(count("SELECT count(*) FROM npd_activities WHERE npd_project_id = ?", project)).isEqualTo(2);

		// moved to another stage while the plan is still being built; an edit with no stage leaves it alone
		long step = firstActivityNamed(project, "Tooling readiness");
		call(put("/api/npd/activities/{id}", step), ergoNpd, ownedStepIn("Tooling readiness", "SUPPLIER", supplier, "TRIAL_PREPARATION"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activities[?(@.id == %d)].phaseName".formatted(step)).value(hasItem("TRIAL_PREPARATION")));
		call(put("/api/npd/activities/{id}", step), ergoNpd, ownedStepIn("Tooling readiness (renamed)", "SUPPLIER", supplier, null))
			.andExpect(jsonPath("$.activities[?(@.id == %d)].phaseName".formatted(step)).value(hasItem("TRIAL_PREPARATION")));
		call(put("/api/npd/activities/{id}", step), ergoNpd, ownedStepIn("Tooling readiness", "SUPPLIER", supplier, "PENDING_CONVERSION"))
			.andExpect(status().isUnprocessableContent());

		// a step planned for a later stage does not hold up the current one
	TestNpd.assignPlan(mvc, ergoNpd, project);
		TestNpd.submitPlan(mvc, npdSupplier, project);
		TestNpd.agreePlan(mvc, ergoNpd, project);
		call(put("/api/npd/activities/{id}", step), ergoNpd, ownedStepIn("Tooling readiness", "SUPPLIER", supplier, "TRIAL_EXECUTION"))
			.andExpect(status().isConflict());
		call(put("/api/npd/activities/{id}", firstActivityNamed(project, "Now")), ergoNpd,
				doneStep("Now", "ERGO", null, null)).andExpect(status().isOk());
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk())
			.andExpect(jsonPath("$.stage").value("COMMERCIAL_SOURCING_READINESS"));
		// the stage is behind us now: planning into it is refused
		call(post("/api/npd/{id}/activities", project), ergoNpd, ownedStepIn("In the past", "ERGO", null, "PLANNING"))
			.andExpect(status().isUnprocessableContent());
	}

	// ---------------------------------------------------------------- the KAM may decide Sample POs, not edit projects

	@Test
	void theKamSeesItsProjectsButCannotEditThemAndOtherKamsSeeNothing() throws Exception {
		long project = TestNpd.release(mvc, npdCustomerA, kamA, ergoNpd, ergoQuality, supplier, approvedPart(buyerA, "PN-KAM"));
		long activity = firstActivity(project);
		long issue = JsonPath.<List<Number>>read(call(post("/api/npd/{id}/issues", project), ergoNpd,
				"{\"type\": \"ISSUE\", \"title\": \"Late drawing\", \"severity\": \"MEDIUM\", \"ownerOrganizationType\": \"CUSTOMER\", \"ownerCompanyId\": %d}"
					.formatted(customerA)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
				"$.issuesRisks[?(@.title == 'Late drawing')].id").getFirst().longValue();

		// it sees the projects of its own accounts, with no buttons, and the others do not exist for it
		call(get("/api/npd?size=100"), kamA, null).andExpect(jsonPath("$.items[*].id", hasItem((int) project)));
		call(get("/api/npd/{id}", project), kamA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", empty()))
			.andExpect(jsonPath("$.activities[0].actions", empty()));
		call(get("/api/npd?size=100"), kamB, null).andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.items[*].id", not(hasItem((int) project))));
		call(get("/api/npd/{id}", project), kamB, null).andExpect(status().isNotFound());

		// level A on NPD covers the Sample PO decision only: no project change is possible
		call(post("/api/npd/{id}/activities", project), kamA, ownedStep("Sneaky", "ERGO", null)).andExpect(status().isConflict());
		call(put("/api/npd/activities/{id}", activity), kamA, ownedStep("Renamed", "SUPPLIER", supplier)).andExpect(status().isConflict());
		call(put("/api/npd/{id}", project), kamA, "{\"riskLevel\": \"HIGH\"}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/issues", project), kamA,
				"{\"type\": \"ISSUE\", \"title\": \"Mine\", \"severity\": \"LOW\", \"ownerOrganizationType\": \"ERGO\"}")
			.andExpect(status().isConflict());
		call(post("/api/npd/{id}/approvals", project), kamA, "{\"approvalType\": \"STAGE_GATE\"}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/prompt-checklists", project), kamA,
				"{\"activityId\": %d, \"questionText\": \"Ready?\", \"responseType\": \"YES_NO\", \"mandatory\": true, \"sequenceNo\": 1}"
					.formatted(activity)).andExpect(status().isConflict());
		call(post("/api/npd/activities/{id}/progress", activity), kamA,
				"{\"status\": \"DONE\", \"issueFlag\": false, \"riskFlag\": false}").andExpect(status().isNotFound());
		call(post("/api/npd/activities/{id}/block", activity), kamA, "{\"comment\": \"x\"}").andExpect(status().isNotFound());
		call(post("/api/npd/activities/{id}/unblock", activity), kamA, "{}").andExpect(status().isNotFound());
		call(put("/api/npd/issues/{id}/status", issue), kamA, "{\"status\": \"CLOSED\"}").andExpect(status().isNotFound());
		call(post("/api/npd/{id}/close-stage", project), kamA, "{}").andExpect(status().isForbidden());
		call(post("/api/npd/{id}/plan/assign", project), kamA, "{}").andExpect(status().isForbidden());

		// nothing changed
		assertThat(jdbc.queryForObject("SELECT risk_level FROM npd_projects WHERE npd_project_id = ?", String.class, project))
			.isNull();
		assertThat(count("SELECT count(*) FROM npd_activities WHERE npd_project_id = ?", project)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM npd_issues_risks WHERE npd_project_id = ?", project)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- notifications

	@Test
	void everyoneWhoMustActHearsOfItAndNoOneElseDoes() throws Exception {
		long part = approvedPart(buyerA, "PN-NOT");
		long po = TestNpd.raise(mvc, npdCustomerA, part);

		// a new Sample PO: the KAM of that account and Management, not another KAM, NPD or the raiser
		assertThat(heard(kamA, "SAMPLE_PO_SUBMITTED", "sample-po", po)).isEqualTo(1);
		assertThat(heard(ergoManagement, "SAMPLE_PO_SUBMITTED", "sample-po", po)).isEqualTo(1);
		for (Login not : List.of(kamB, ergoNpd, ergoQuality, ergoOfp, npdCustomerA, qualityCustomerA, npdSupplier)) {
			assertThat(heard(not, "SAMPLE_PO_SUBMITTED", "sample-po", po)).isZero();
		}
		assertThat(jdbc.queryForObject("SELECT action_url FROM notifications WHERE recipient_user_id = ? AND linked_record_id = ?",
				String.class, kamA.userId(), po)).isEqualTo("/npd/sample-pos/" + po);

		// returned: Customer NPD of that company, nobody else
		call(post("/api/npd/sample-pos/{id}/return", po), kamA, "{\"comment\": \"Fix the quantity\"}").andExpect(status().isOk());
		assertThat(heard(npdCustomerA, "SAMPLE_PO_RETURNED", "sample-po", po)).isEqualTo(1);
		for (Login not : List.of(qualityCustomerA, buyerA, npdCustomerB, ergoManagement, kamA, npdSupplier)) {
			assertThat(heard(not, "SAMPLE_PO_RETURNED", "sample-po", po)).isZero();
		}

		// corrected: the KAM hears again
		call(put("/api/npd/sample-pos/{id}", po), npdCustomerA, editForm()).andExpect(status().isOk());
		assertThat(heard(kamA, "SAMPLE_PO_SUBMITTED", "sample-po", po)).isEqualTo(2);

		// accepted: exactly the two people the project was assigned to (not another ERGO NPD user, not the KAM itself)
		long project = TestNpd.createProject(mvc, kamA, po, ergoNpd, ergoQuality, null);
		assertThat(heard(ergoNpd, "NPD_PROJECT_ASSIGNED", "npd", project)).isEqualTo(1);
		assertThat(heard(ergoQuality, "NPD_PROJECT_ASSIGNED", "npd", project)).isEqualTo(1);
		for (Login not : List.of(ergoNpd2, kamA, ergoManagement, ergoOfp, npdCustomerA, npdSupplier)) {
			assertThat(heard(not, "NPD_PROJECT_ASSIGNED", "npd", project)).isZero();
		}

		// the supplier hears nothing until the plan is released, then its NPD, RFQ and Quality teams do (not its OFP team)
		TestNpd.addSupplierStep(mvc, ergoNpd, project, supplier, "Tooling readiness", true);
		for (Login supplierUser : List.of(npdSupplier, seller, qualitySupplier, ofpSupplier)) {
			assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND linked_module IN ('npd', 'sample-po')",
					supplierUser.userId())).isZero();
		}
		TestNpd.assignPlan(mvc, ergoNpd, project);
		for (Login team : List.of(npdSupplier, seller, qualitySupplier)) {
			assertThat(heard(team, "NPD_PLAN_ASSIGNED", "npd", project)).isEqualTo(1);
		}
		for (Login not : List.of(ofpSupplier, npdSupplierB, npdCustomerA, ergoNpd, ergoQuality)) {
			assertThat(heard(not, "NPD_PLAN_ASSIGNED", "npd", project)).isZero();
		}
	}

	// ---------------------------------------------------------------- counts

	@Test
	void theAwaitingCountIsAllSamplePosForManagementAndTheAssignedAccountsForTheKam() throws Exception {
		int before = awaiting(ergoManagement);
		long poA = TestNpd.raise(mvc, npdCustomerA, approvedPart(buyerA, "PN-CNT1"));
		long poB = TestNpd.raise(mvc, npdCustomerB, approvedPart(buyerB, "PN-CNT2"));
		long poA2 = TestNpd.raise(mvc, npdCustomerA, approvedPart(buyerA, "PN-CNT3"));

		assertThat(awaiting(ergoManagement)).isEqualTo(before + 3);
		assertThat(awaiting(kamA)).isEqualTo(2);
		assertThat(awaiting(kamB)).isEqualTo(1);
		// every other role has no queue
		for (Login other : List.of(ergoNpd, ergoQuality, ergoOfp, npdCustomerA, npdSupplier)) {
			assertThat(awaiting(other)).isZero();
		}
		Login lonelyKam = users.login(ergo, "ERGO_KAM");
		assertThat(awaiting(lonelyKam)).isZero();
		call(get("/api/dashboard"), ergoManagement, null).andExpect(jsonPath("$.samplePosAwaitingCount").value(before + 3));

		call(post("/api/npd/sample-pos/{id}/return", poA), kamA, "{\"comment\": \"x\"}").andExpect(status().isOk());
		TestNpd.createProject(mvc, kamB, poB, ergoNpd, ergoQuality, null);
		assertThat(awaiting(ergoManagement)).isEqualTo(before + 1);
		assertThat(awaiting(kamA)).isEqualTo(1);
		assertThat(awaiting(kamB)).isZero();
		assertThat(poStatus(poA2)).isEqualTo("SUBMITTED");
	}

	// ---------------------------------------------------------------- module switches and the role sheet

	@Test
	void aReadOnlyOrHiddenNpdModuleRefusesEveryChangeAndHidesTheSamplePos() throws Exception {
		long part = approvedPart(buyerA, "PN-SW");
		long returned = TestNpd.raise(mvc, npdCustomerA, part);
		call(post("/api/npd/sample-pos/{id}/return", returned), kamA, "{\"comment\": \"Fix\"}").andExpect(status().isOk());
		long waiting = TestNpd.raise(mvc, npdCustomerB, approvedPart(buyerB, "PN-SW2"));
		long freshPart = approvedPart(buyerA, "PN-SW3");
		long project = TestNpd.start(mvc, npdCustomerB, kamB, ergoNpd, ergoQuality, approvedPart(buyerB, "PN-SW4"));
		TestNpd.addSupplierStep(mvc, ergoNpd, project, supplier, "Tooling readiness", true);

		cap("NPD", "V");
		// readable, but with no buttons
		call(get("/api/npd/sample-pos/{id}", waiting), kamB, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", empty()));
		call(get("/api/npd/sample-pos/{id}", returned), npdCustomerA, null).andExpect(jsonPath("$.actions", empty()));
		call(post("/api/npd/sample-pos"), npdCustomerA, TestNpd.samplePoForm(freshPart)).andExpect(status().isForbidden());
		call(put("/api/npd/sample-pos/{id}", returned), npdCustomerA, editForm()).andExpect(status().isForbidden());
		call(post("/api/npd/sample-pos/{id}/return", waiting), kamB, "{\"comment\": \"x\"}").andExpect(status().isForbidden());
		call(post("/api/npd/sample-pos/{id}/create-project", waiting), kamB, assign(ergoNpd, ergoQuality))
			.andExpect(status().isForbidden());
		call(post("/api/npd/{id}/plan/assign", project), ergoNpd, "{}").andExpect(status().isForbidden());
		assertThat(poStatus(waiting)).isEqualTo("SUBMITTED");
		assertThat(poStatus(returned)).isEqualTo("RETURNED");
		assertThat(planStatus(project)).isEqualTo("AWAITING_PLAN");
		assertThat(count("SELECT count(*) FROM sample_pos WHERE rfq_item_id = ?", freshPart)).isZero();

		cap("NPD", "NV");
		call(get("/api/npd/sample-pos"), kamB, null).andExpect(status().isForbidden());
		call(get("/api/npd/sample-pos/{id}", waiting), kamB, null).andExpect(status().isForbidden());
		call(get("/api/npd/sample-pos"), npdCustomerA, null).andExpect(status().isForbidden());
		call(get("/api/npd/sample-pos/eligible-parts"), npdCustomerA, null).andExpect(status().isForbidden());
		assertThat(awaiting(kamB)).isZero();

		// back on: everything is where it was
		cap("NPD", "O");
		call(get("/api/npd/sample-pos/{id}", waiting), kamB, null).andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.actions[*]", hasItem("CREATE_PROJECT")));
		call(put("/api/npd/sample-pos/{id}", returned), npdCustomerA, editForm()).andExpect(status().isOk());
		call(post("/api/npd/{id}/plan/assign", project), ergoNpd, "{}").andExpect(status().isOk());
	}

	@Test
	void theShippedSheetGivesTheKamAOnNpdAndSupplierRfqAViewAndTheAdminScreenShowsIt() throws Exception {
		assertThat(DefaultAccessSheet.code(Role.ERGO_KAM, "NPD")).isEqualTo("A");
		assertThat(DefaultAccessSheet.code(Role.SUPPLIER_RFQ, "NPD")).isEqualTo("V");
		assertThat(jdbc.queryForObject("SELECT access_level FROM role_module_permissions WHERE role_code = 'ERGO_KAM' AND module_code = 'NPD'",
				String.class)).isEqualTo("A");
		assertThat(jdbc.queryForObject("SELECT access_level FROM role_module_permissions WHERE role_code = 'SUPPLIER_RFQ' AND module_code = 'NPD'",
				String.class)).isEqualTo("V");

		Login admin = users.login(ergo, "ERGO_ADMIN");
		call(get("/api/admin/permissions/ERGO"), admin, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.roles[?(@.code=='ERGO_KAM')].cells.NPD.level").value(hasItem("A")));
		call(get("/api/admin/permissions/SUPPLIER"), admin, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.roles[?(@.code=='SUPPLIER_RFQ')].cells.NPD.level").value(hasItem("V")));

		// the screen still cuts the KAM off: at NV on NPD it decides no Sample PO and reaches none
		long po = TestNpd.raise(mvc, npdCustomerA, approvedPart(buyerA, "PN-SHEET"));
		jdbc.update("UPDATE role_module_permissions SET access_level = 'NV' WHERE role_code = 'ERGO_KAM' AND module_code = 'NPD'");
		jdbc.update("UPDATE access_config SET version = version + 1");
		policy.refresh();
		call(get("/api/npd/sample-pos"), kamA, null).andExpect(status().isForbidden());
		call(post("/api/npd/sample-pos/{id}/return", po), kamA, "{\"comment\": \"x\"}").andExpect(status().isForbidden());
		// ... and at V it reads but cannot decide
		jdbc.update("UPDATE role_module_permissions SET access_level = 'V' WHERE role_code = 'ERGO_KAM' AND module_code = 'NPD'");
		jdbc.update("UPDATE access_config SET version = version + 1");
		policy.refresh();
		call(get("/api/npd/sample-pos/{id}", po), kamA, null).andExpect(status().isOk()).andExpect(jsonPath("$.actions", empty()));
		call(post("/api/npd/sample-pos/{id}/return", po), kamA, "{\"comment\": \"x\"}").andExpect(status().isForbidden());
		assertThat(poStatus(po)).isEqualTo("SUBMITTED");
	}

	// ---------------------------------------------------------------- concurrency

	@Test
	void twoDecisionsOnOneSamplePoRunOneAfterTheOtherAndOnlyOneWins() throws Exception {
		for (int round = 0; round < 3; round++) {
			long po = TestNpd.raise(mvc, npdCustomerA, approvedPart(buyerA, "PN-RACE" + round));
			List<Integer> outcomes = race(
					() -> send(post("/api/npd/sample-pos/{id}/return", po), kamA, "{\"comment\": \"Fix it\"}"),
					() -> send(post("/api/npd/sample-pos/{id}/create-project", po), kamA, assign(ergoNpd, ergoQuality)),
					() -> send(post("/api/npd/sample-pos/{id}/create-project", po), ergoManagement, assign(ergoNpd, ergoQuality)));
			// exactly one decision took effect; the others saw its result and were refused with a 409
			assertThat(outcomes.stream().filter(s -> s == 200 || s == 201).count()).isEqualTo(1);
			assertThat(outcomes.stream().filter(s -> s == 409).count()).isEqualTo(2);
			long projects = count("SELECT count(*) FROM npd_projects WHERE sample_po_id = ?", po);
			assertThat(projects).isLessThanOrEqualTo(1);
			assertThat(poStatus(po)).isEqualTo(projects == 1 ? "ACCEPTED" : "RETURNED");
		}
	}

	@Test
	void twoCustomersRaisingTheSamePartAtOnceCreateOneSamplePo() throws Exception {
		long part = approvedPart(buyerA, "PN-DUP");
		Login second = users.login(customerA, "CUSTOMER_NPD");
		List<Integer> outcomes = race(() -> send(post("/api/npd/sample-pos"), npdCustomerA, TestNpd.samplePoForm(part)),
				() -> send(post("/api/npd/sample-pos"), second, TestNpd.samplePoForm(part)));
		assertThat(outcomes).containsExactlyInAnyOrder(201, 409);
		assertThat(count("SELECT count(*) FROM sample_pos WHERE rfq_item_id = ?", part)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- helpers

	/** Starts the calls together and returns each one's HTTP status. */
	@SafeVarargs
	private List<Integer> race(Callable<Integer>... calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.length);
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> call : calls) {
				futures.add(pool.submit(() -> {
					go.await();
					return call.call();
				}));
			}
			go.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(60, TimeUnit.SECONDS));
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private int send(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		return call(request, user, body).andReturn().getResponse().getStatus();
	}

	/** A part of the customer's RFQ whose quotation the customer approved; returns the part (RFQ item) id. */
	private long approvedPart(Login buyer, String partNumber) throws Exception {
		long id = TestRfq.create(mvc, buyer, TestRfq.form(inr, partNumber));
		call(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		TestRfq.approveWhole(mvc, buyer, ergoRfq, seller, supplier, inr, id, "90");
		return TestRfq.itemIds(mvc, ergoRfq, id).getFirst();
	}

	/** A part of a submitted RFQ that is still under review: no approved quotation. */
	private long unapprovedPart(Login buyer, String partNumber) throws Exception {
		long id = TestRfq.create(mvc, buyer, TestRfq.form(inr, partNumber));
		call(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		return TestRfq.itemIds(mvc, ergoRfq, id).getFirst();
	}

	private static String assign(Login npd, Login quality) {
		return "{\"ergoNpdUserId\": %d, \"ergoQualityUserId\": %d}".formatted(npd.userId(), quality.userId());
	}

	private static String editForm() {
		return "{\"customerPoNumber\": \"PO-FIXED\", \"quantity\": 50, \"requiredDate\": \"%s\", \"remarks\": \"Corrected\"}"
			.formatted(LocalDate.now().plusDays(21));
	}

	private static String ownedStep(String name, String ownerType, Long ownerCompanyId) {
		return ownedStepIn(name, ownerType, ownerCompanyId, null);
	}

	private static String ownedStepIn(String name, String ownerType, Long ownerCompanyId, String stage) {
		return stepJson(name, ownerType, ownerCompanyId, stage, "NOT_STARTED");
	}

	private static String doneStep(String name, String ownerType, Long ownerCompanyId, String stage) {
		return stepJson(name, ownerType, ownerCompanyId, stage, "DONE");
	}

	private static String stepJson(String name, String ownerType, Long ownerCompanyId, String stage, String status) {
		return """
				{"activityName": "%s", "status": "%s", "mandatory": true, "riskFlag": false, "issueFlag": false,
				 "ownerOrganizationType": "%s", "ownerCompanyId": %s, "approvalRequired": false, "stage": %s, "plannedDate": "%s"}"""
			.formatted(name, status, ownerType, ownerCompanyId == null ? "null" : ownerCompanyId,
					stage == null ? "null" : "\"" + stage + "\"", LocalDate.now().plusDays(14));
	}

	private static String inspection(long projectId) {
		return """
				{"linkedModule": "NPD", "linkedRecordId": %d, "partNumber": "PN-STD", "customerVisible": false}"""
			.formatted(projectId);
	}

	private long firstActivity(long project) {
		return jdbc.queryForObject("SELECT min(npd_activity_id) FROM npd_activities WHERE npd_project_id = ?", Long.class,
				project);
	}

	private long firstActivityNamed(long project, String name) {
		return jdbc.queryForObject("SELECT min(npd_activity_id) FROM npd_activities WHERE npd_project_id = ? AND activity_name = ?",
				Long.class, project, name);
	}

	private String poStatus(long samplePoId) {
		return jdbc.queryForObject("SELECT status FROM sample_pos WHERE sample_po_id = ?", String.class, samplePoId);
	}

	private String planStatus(long project) {
		return jdbc.queryForObject("SELECT plan_status FROM npd_projects WHERE npd_project_id = ?", String.class, project);
	}

	private String stage(long project) {
		return jdbc.queryForObject("SELECT stage FROM npd_projects WHERE npd_project_id = ?", String.class, project);
	}

	private String nameOf(long companyId) {
		return jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?", String.class, companyId);
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private long heard(Login user, String kind, String module, long record) {
		return count("""
				SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_kind = ? AND linked_module = ?
				AND linked_record_id = ?""", user.userId(), kind, module, record);
	}

	private int awaiting(Login user) {
		return CurrentUser.runAs(new CurrentUser(user.userId(), user.companyId(), roleOf(user).companyType(), roleOf(user), 0L),
				() -> npdApi.samplePosAwaitingCount());
	}

	private Role roleOf(Login user) {
		if (user == ergoManagement) {
			return Role.ERGO_MANAGEMENT;
		}
		if (user == kamA || user == kamB) {
			return Role.ERGO_KAM;
		}
		String code = jdbc.queryForObject("SELECT default_role_code FROM users WHERE user_id = ?", String.class, user.userId());
		return Role.valueOf(code);
	}

	private void cap(String module, String cap) {
		jdbc.update("UPDATE app_modules SET access_cap = ? WHERE module_code = ?", cap, module);
		jdbc.update("UPDATE access_config SET version = version + 1");
		policy.refresh();
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

}
