package com.ergo.connect.quality;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 5a: quality inspections (SRS §10.8, §13.6, §14.7) - record scope with 2 customers and 2 suppliers, the
 * workflow's allowed/forbidden/missing-reason transitions, and that Customer Quality stays view-only (the judgment
 * call flagged in {@code InspectionService}'s class comment) despite the matrix's literal C/E/S/A*.
 */
@IntegrationTest
class QualityTests {

	/** Unique order/RFQ/NPD numbers for {@link #createOrder}/{@link #createNpdProject} (each column is UNIQUE). */
	private static final AtomicInteger SEQ = new AtomicInteger(700_000);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long customerA;

	long customerB;

	long supplierX;

	long supplierY;

	Login ergoAdmin;

	Login ergoQuality;

	Login ergoManagement;

	Login ergoKam;

	Login ergoNpd;

	Login ergoOfp;

	Login ergoRfq;

	Login customerAQuality;

	Login customerBQuality;

	Login supplierXQuality;

	Login supplierYQuality;

	Login customerAKam;

	Login supplierXOfp;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		customerA = users.company("CUSTOMER");
		customerB = users.company("CUSTOMER");
		supplierX = users.company("SUPPLIER");
		supplierY = users.company("SUPPLIER");
		ergoAdmin = users.login(ergo, "ERGO_ADMIN");
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		ergoManagement = users.login(ergo, "ERGO_MANAGEMENT");
		ergoKam = users.login(ergo, "ERGO_KAM");
		users.assignAccounts(ergoKam.userId(), customerA);
		ergoNpd = users.login(ergo, "ERGO_NPD");
		ergoOfp = users.login(ergo, "ERGO_OFP");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		customerAQuality = users.login(customerA, "CUSTOMER_QUALITY");
		customerBQuality = users.login(customerB, "CUSTOMER_QUALITY");
		supplierXQuality = users.login(supplierX, "SUPPLIER_QUALITY");
		supplierYQuality = users.login(supplierY, "SUPPLIER_QUALITY");
		customerAKam = users.login(customerA, "CUSTOMER_KAM");
		supplierXOfp = users.login(supplierX, "SUPPLIER_OFP");
	}

	// ---------------------------------------------------------------- create / role check

	@Test
	void rolesThatActOnQualityMayCreateAndTheViewOnlyOnesMayNot() throws Exception {
		// role sheet: ERGO Management / Quality ("O"), Supplier Quality / KAM ("A") create
		call(post("/api/quality/inspections"), ergoManagement, inspectionForm("INCOMING", 1)).andExpect(status().isCreated());
		call(post("/api/quality/inspections"), ergoQuality, inspectionForm("INCOMING", 2)).andExpect(status().isCreated());
		call(post("/api/quality/inspections"), supplierXQuality, inspectionForm("INCOMING", 3))
			.andExpect(status().isCreated());
		Login supplierKam = users.login(supplierX, "SUPPLIER_KAM");
		call(post("/api/quality/inspections"), supplierKam, inspectionForm("INCOMING", 5)).andExpect(status().isCreated());

		// ERGO OFP ("A") may only raise inspections within its own area: OFP-linked ones, never a free-standing one
		call(post("/api/quality/inspections"), ergoOfp, inspectionForm("INCOMING", 6))
			.andExpect(status().isUnprocessableContent());

		// view only: ERGO Admin/KAM/NPD/RFQ; customers never create inspections
		for (Login denied : List.of(ergoAdmin, ergoKam, ergoNpd, ergoRfq, customerAQuality)) {
			call(post("/api/quality/inspections"), denied, inspectionForm("INCOMING", 4))
				.andExpect(status().isForbidden());
		}
	}

	@Test
	void supplierCreateIsForcedToItsOwnCompanyAndNeverExposesACustomer() throws Exception {
		String created = call(post("/api/quality/inspections"), supplierXQuality, """
				{"linkedModule": "INCOMING", "linkedRecordId": 1, "partNumber": "PN-1",
				 "customerCompanyId": %d, "customerVisible": true}""".formatted(customerA))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.customerVisible").doesNotExist()) // supplier's own view never carries this field
			.andReturn()
			.getResponse()
			.getContentAsString();
		long id = ((Number) JsonPath.read(created, "$.id")).longValue();

		// even though the supplier tried to set customerCompanyId/customerVisible, ERGO's own view shows neither
		// took effect: the record is not customer-visible and has no customer attached
		call(get("/api/quality/inspections/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.customerVisible").value(false))
			.andExpect(jsonPath("$.customerCompanyId").doesNotExist())
			.andExpect(jsonPath("$.supplierCompanyId").value((int) supplierX));

		// and Customer A (the company the supplier tried to name) cannot see it
		call(get("/api/quality/inspections/{id}", id), customerAQuality, null).andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- record scope: 2 customers, 2 suppliers

	@Test
	void supplierCannotSeeAnotherSuppliersInspection() throws Exception {
		long id = createAsSupplier(supplierXQuality, "INCOMING", 10);

		call(get("/api/quality/inspections/{id}", id), supplierXQuality, null).andExpect(status().isOk());
		call(get("/api/quality/inspections?size=100"), supplierXQuality, null)
			.andExpect(jsonPath("$.items[*].id", hasItem((int) id)));

		// supplier Y cannot see it: 404, not 403 (scope, not a role gap)
		call(get("/api/quality/inspections/{id}", id), supplierYQuality, null).andExpect(status().isNotFound());
		call(get("/api/quality/inspections?size=100"), supplierYQuality, null)
			.andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.items[*].id", not(hasItem((int) id))));

		// nor may supplier Y edit or transition it
		call(put("/api/quality/inspections/{id}", id), supplierYQuality, updateForm("2", "0")).andExpect(status().isNotFound());
		call(post("/api/quality/inspections/{id}/submit", id), supplierYQuality, updateForm("2", "0"))
			.andExpect(status().isNotFound());
	}

	@Test
	void customerQualityCannotSeeInspectionsNotMarkedCustomerVisibleOrBelongingToAnotherCustomer() throws Exception {
		long visibleToA = createErgoInspection("FINAL", 20, customerA, null, true);
		long notVisibleToA = createErgoInspection("FINAL", 21, customerA, null, false);
		long visibleToB = createErgoInspection("FINAL", 22, customerB, null, true);

		call(get("/api/quality/inspections/{id}", visibleToA), customerAQuality, null).andExpect(status().isOk());
		// customer_visible=false: not shown even though it is customer A's own record
		call(get("/api/quality/inspections/{id}", notVisibleToA), customerAQuality, null)
			.andExpect(status().isNotFound());
		// another customer's record, even though it is customer_visible=true
		call(get("/api/quality/inspections/{id}", visibleToB), customerAQuality, null)
			.andExpect(status().isNotFound());

		String list = call(get("/api/quality/inspections?size=100"), customerAQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", hasItem((int) visibleToA)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) notVisibleToA))))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) visibleToB))))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(list).isNotBlank();
	}

	@Test
	void twoWayAnonymityNeitherPartyLearnsTheOther() throws Exception {
		long id = createErgoInspection("FINAL", 30, customerA, supplierX, true);

		String supplierView = call(get("/api/quality/inspections/{id}", id), supplierXQuality, null)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(supplierView).doesNotContain("customerCompanyId").doesNotContain("\"customer\"");

		String customerView = call(get("/api/quality/inspections/{id}", id), customerAQuality, null)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(customerView).doesNotContain("supplierCompanyId").doesNotContain("\"supplier\"");

		// ERGO keeps the full view
		call(get("/api/quality/inspections/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.customerCompanyId").value((int) customerA))
			.andExpect(jsonPath("$.supplierCompanyId").value((int) supplierX));
	}

	@Test
	void ergoKamSeesOnlyItsAssignedCustomersInspectionsAndErgoNpdOfpSeeOnlyTheirOwnLinkedModule() throws Exception {
		long forA = createErgoInspection("FINAL", 40, customerA, null, false);
		long forB = createErgoInspection("FINAL", 41, customerB, null, false);
		long npdOne = createErgoInspection("NPD", createNpdProject(customerA, supplierX), null, null, false);
		long ofpOne = createErgoInspection("OFP", createOrder(customerA, supplierX), null, null, false);

		call(get("/api/quality/inspections?size=100"), ergoKam, null).andExpect(jsonPath("$.items[*].id", hasItem((int) forA)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) forB))));
		call(get("/api/quality/inspections/{id}", forA), ergoKam, null).andExpect(status().isOk());
		call(get("/api/quality/inspections/{id}", forB), ergoKam, null).andExpect(status().isNotFound());

		call(get("/api/quality/inspections?size=100"), ergoNpd, null).andExpect(jsonPath("$.items[*].id", hasItem((int) npdOne)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) ofpOne))));
		call(get("/api/quality/inspections/{id}", ofpOne), ergoNpd, null).andExpect(status().isNotFound());

		call(get("/api/quality/inspections?size=100"), ergoOfp, null).andExpect(jsonPath("$.items[*].id", hasItem((int) ofpOne)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) npdOne))));
		call(get("/api/quality/inspections/{id}", npdOne), ergoOfp, null).andExpect(status().isNotFound());

		// ERGO RFQ and Admin view every inspection (role sheet "V")
		for (Login viewer : List.of(ergoRfq, ergoAdmin)) {
			call(get("/api/quality/inspections?size=100"), viewer, null).andExpect(jsonPath("$.items[*].id", hasItem((int) forB)));
			call(get("/api/quality/inspections/{id}", forA), viewer, null).andExpect(status().isOk());
		}
		// customer RFQ / supplier RFQ: customers view only customer-visible ones, supplier RFQ has no access at all
		Login supplierRfq = users.login(supplierX, "SUPPLIER_RFQ");
		call(get("/api/quality/inspections"), supplierRfq, null).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- workflow: allowed / forbidden / missing reason

	@Test
	void fullHappyPathOpenToInInspectionToPassedToClosed() throws Exception {
		long id = createAsSupplier(supplierXQuality, "INCOMING", 50);

		call(post("/api/quality/inspections/{id}/submit", id), supplierXQuality, updateForm("100", "5"))
			.andExpect(jsonPath("$.resultStatus").value("IN_INSPECTION"))
			.andExpect(jsonPath("$.quantityChecked").value(100))
			.andExpect(jsonPath("$.defects[0].defectCategory").value("Dimensional"));

		// supplier cannot decide the outcome (no approve right)
		call(post("/api/quality/inspections/{id}/pass", id), supplierXQuality, "{}").andExpect(status().isForbidden());

		call(post("/api/quality/inspections/{id}/pass", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.resultStatus").value("PASSED"));

		// ERGO Management may also approve (it owns Quality, "O")
		long id2 = createAsSupplier(supplierXQuality, "INCOMING", 51);
		call(post("/api/quality/inspections/{id}/submit", id2), supplierXQuality, updateForm("10", "0"))
			.andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/pass", id2), ergoManagement, "{}")
			.andExpect(jsonPath("$.resultStatus").value("PASSED"));
		call(post("/api/quality/inspections/{id}/close", id2), ergoAdmin, "{}")
			.andExpect(status().isForbidden()); // ERGO Admin is view-only: it approves nothing

		call(post("/api/quality/inspections/{id}/close", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.resultStatus").value("CLOSED"));
	}

	@Test
	void submitRequiresQuantityCheckedAndInspectionDate() throws Exception {
		long id = createAsSupplier(supplierXQuality, "INCOMING", 60);
		call(post("/api/quality/inspections/{id}/submit", id), supplierXQuality, """
				{"partNumber": "PN-1", "customerVisible": false}""").andExpect(status().isUnprocessableContent());
	}

	@Test
	void rejectAndReleaseWithDeviationRequireAReasonAndOnlyApproversMayDecide() throws Exception {
		long rejected = createAsSupplier(supplierXQuality, "INCOMING", 70);
		call(post("/api/quality/inspections/{id}/submit", rejected), supplierXQuality, updateForm("50", "50"))
			.andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/reject", rejected), ergoQuality, "{}")
			.andExpect(status().isUnprocessableContent());
		call(post("/api/quality/inspections/{id}/reject", rejected), supplierXQuality, "{\"reason\": \"bad batch\"}")
			.andExpect(status().isForbidden());
		call(post("/api/quality/inspections/{id}/reject", rejected), ergoQuality, "{\"reason\": \"bad batch\"}")
			.andExpect(jsonPath("$.resultStatus").value("REJECTED"));
		assertThat(jdbc.queryForObject("""
				SELECT action_reason FROM audit_logs WHERE entity_name = 'Inspection' AND record_id = ?
				AND field_name = 'resultStatus' AND new_value = 'REJECTED'""", String.class, rejected))
			.isEqualTo("bad batch");

		long deviated = createAsSupplier(supplierXQuality, "INCOMING", 71);
		call(post("/api/quality/inspections/{id}/submit", deviated), supplierXQuality, updateForm("50", "2"))
			.andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/release-with-deviation", deviated), ergoQuality, "{}")
			.andExpect(status().isUnprocessableContent());
		call(post("/api/quality/inspections/{id}/release-with-deviation", deviated), ergoQuality,
				"{\"reason\": \"customer accepted deviation\"}")
			.andExpect(jsonPath("$.resultStatus").value("RELEASED_WITH_DEVIATION"));
	}

	@Test
	void reopenIsTheOnlyPathFromClosedAndRequiresAReason() throws Exception {
		long id = createAsSupplier(supplierXQuality, "INCOMING", 80);
		call(post("/api/quality/inspections/{id}/submit", id), supplierXQuality, updateForm("10", "0"))
			.andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/pass", id), ergoQuality, "{}").andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/close", id), ergoQuality, "{}").andExpect(status().isOk());

		// cannot submit/pass/reject/release a closed inspection
		call(post("/api/quality/inspections/{id}/submit", id), supplierXQuality, updateForm("10", "0"))
			.andExpect(status().isConflict());
		call(post("/api/quality/inspections/{id}/pass", id), ergoQuality, "{}").andExpect(status().isConflict());

		call(post("/api/quality/inspections/{id}/reopen", id), ergoQuality, "{}")
			.andExpect(status().isUnprocessableContent());
		call(post("/api/quality/inspections/{id}/reopen", id), supplierXQuality, "{\"reason\": \"recheck needed\"}")
			.andExpect(status().isForbidden());
		call(post("/api/quality/inspections/{id}/reopen", id), ergoQuality, "{\"reason\": \"recheck needed\"}")
			.andExpect(jsonPath("$.resultStatus").value("OPEN"));
		assertThat(jdbc.queryForObject("""
				SELECT action_reason FROM audit_logs WHERE entity_name = 'Inspection' AND record_id = ?
				AND field_name = 'resultStatus' AND new_value = 'OPEN' ORDER BY audit_log_id DESC LIMIT 1""",
				String.class, id)).isEqualTo("recheck needed");
	}

	@Test
	void editIsBlockedOnceAResultHasBeenDecided() throws Exception {
		long id = createAsSupplier(supplierXQuality, "INCOMING", 90);
		call(post("/api/quality/inspections/{id}/submit", id), supplierXQuality, updateForm("10", "0"))
			.andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/pass", id), ergoQuality, "{}").andExpect(status().isOk());

		call(put("/api/quality/inspections/{id}", id), ergoQuality, updateForm("20", "0"))
			.andExpect(status().isConflict());
		call(put("/api/quality/inspections/{id}", id), supplierXQuality, updateForm("20", "0"))
			.andExpect(status().isConflict());
	}

	/** Security review M1: edit is frozen the moment a submission moves it to IN_INSPECTION, not just once a result
	 * has been decided - the only way back to editable is a decision, then Close, then Reopen with a reason. */
	@Test
	void editIsBlockedOnceSubmittedEvenBeforeAResultIsDecided() throws Exception {
		long id = createAsSupplier(supplierXQuality, "INCOMING", 91);
		call(post("/api/quality/inspections/{id}/submit", id), supplierXQuality, updateForm("10", "0"))
			.andExpect(status().isOk());

		call(put("/api/quality/inspections/{id}", id), supplierXQuality, updateForm("20", "0"))
			.andExpect(status().isConflict());
		call(put("/api/quality/inspections/{id}", id), ergoQuality, updateForm("20", "0"))
			.andExpect(status().isConflict());
		call(get("/api/quality/inspections/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", not(hasItem("EDIT"))));
	}

	// ---------------------------------------------------------------- security review H2/M2/M5/M6 (2026-09-30)

	/** Security review H2 (confirmed with the user, ARCHITECTURE.md §8 2026-09-30): Supplier Quality may edit/submit
	 * only an inspection its own company *created*, not merely one tagged to it - an ERGO-authored inspection about
	 * a supplier's goods must stay read-only to that supplier. */
	@Test
	void supplierCannotEditOrSubmitAnErgoCreatedInspectionEvenThoughItIsTaggedToItsOwnCompany() throws Exception {
		long id = createErgoInspection("INCOMING", 120, null, supplierX, false);

		// still visible to supplier X (read scope is unchanged: a direct supplierCompanyId match)
		call(get("/api/quality/inspections/{id}", id), supplierXQuality, null).andExpect(status().isOk());

		call(put("/api/quality/inspections/{id}", id), supplierXQuality, updateForm("5", "0"))
			.andExpect(status().isConflict());
		call(post("/api/quality/inspections/{id}/submit", id), supplierXQuality, updateForm("5", "0"))
			.andExpect(status().isConflict());
		call(get("/api/quality/inspections/{id}", id), supplierXQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", empty()));

		// ERGO Quality, which created it, still may
		call(put("/api/quality/inspections/{id}", id), ergoQuality, updateForm("5", "0")).andExpect(status().isOk());
	}

	/** Security review M2: the user who submitted (and so became {@code inspectedByUserId}) must not also decide its
	 * own PASS/REJECT/RELEASE_WITH_DEVIATION, regardless of role - matches NPD's M-1 self-approval rule. */
	@Test
	void theSameUserWhoSubmittedCannotDecideItsOwnOutcome() throws Exception {
		long id = createErgoInspection("INCOMING", 121, null, null, false);
		call(post("/api/quality/inspections/{id}/submit", id), ergoQuality, updateForm("10", "0"))
			.andExpect(status().isOk());

		call(post("/api/quality/inspections/{id}/pass", id), ergoQuality, "{}").andExpect(status().isConflict());
		call(get("/api/quality/inspections/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", not(hasItem("PASS"))));

		// a different approver still may
		call(post("/api/quality/inspections/{id}/pass", id), ergoManagement, "{}")
			.andExpect(jsonPath("$.resultStatus").value("PASSED"));
	}

	/** Security review L2: a supplier's own PUT can never set customerVisible or attach a customer, regardless of
	 * what it sends - already forced server-side (InspectionService.applyUpdate), asserted explicitly here. */
	@Test
	void supplierEditCannotSetCustomerVisibleOrAttachACustomer() throws Exception {
		long id = createAsSupplier(supplierXQuality, "INCOMING", 122);
		call(put("/api/quality/inspections/{id}", id), supplierXQuality, """
				{"partNumber": "PN-STD", "quantityChecked": 5, "quantityRejected": 0,
				 "inspectionDate": "%s", "customerVisible": true, "customerCompanyId": %d, "defects": []}"""
			.formatted(LocalDate.now(), customerA)).andExpect(status().isOk());

		call(get("/api/quality/inspections/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.customerVisible").value(false))
			.andExpect(jsonPath("$.customerCompanyId").doesNotExist());
	}

	/** Security review M5: an inspection linked to a real OFP order always derives its companies from that order,
	 * never from the client - an ERGO-sent conflicting company id is ignored, and a supplier creator is rejected
	 * outright unless the order is actually theirs. */
	@Test
	void ofpLinkedInspectionDerivesCompaniesFromTheOrderNeverFromTheClient() throws Exception {
		long orderId = createOrder(customerA, supplierX);

		// ERGO's own create ignores a conflicting supplierCompanyId/customerCompanyId from the request body
		String created = call(post("/api/quality/inspections"), ergoQuality, """
				{"linkedModule": "OFP", "linkedRecordId": %d, "partNumber": "PN-1",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "customerVisible": true}"""
			.formatted(orderId, customerB, supplierY))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long id = ((Number) JsonPath.read(created, "$.id")).longValue();
		call(get("/api/quality/inspections/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.customerCompanyId").value((int) customerA))
			.andExpect(jsonPath("$.supplierCompanyId").value((int) supplierX));

		// supplier Y (not the order's own supplier) may not self-create against this order
		call(post("/api/quality/inspections"), supplierYQuality, """
				{"linkedModule": "OFP", "linkedRecordId": %d, "partNumber": "PN-1", "customerVisible": false}"""
			.formatted(orderId)).andExpect(status().isUnprocessableContent());

		// supplier X (the order's own supplier) may, and gets the order's real customer attached ERGO-side, even
		// though its own view still never names the customer
		String ownCreated = call(post("/api/quality/inspections"), supplierXQuality, """
				{"linkedModule": "OFP", "linkedRecordId": %d, "partNumber": "PN-1", "customerVisible": false}"""
			.formatted(orderId))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.customerCompanyId").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long ownId = ((Number) JsonPath.read(ownCreated, "$.id")).longValue();
		call(get("/api/quality/inspections/{id}", ownId), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.customerCompanyId").value((int) customerA))
			.andExpect(jsonPath("$.supplierCompanyId").value((int) supplierX));
	}

	// ---------------------------------------------------------------- record scope beyond *_QUALITY (L2)

	/** Security review L2: record scope and write-denial exercised for a role beyond the *_QUALITY ones already
	 * covered above and below - Customer KAM (customer side) and Supplier OFP (supplier side). */
	@Test
	void customerKamAndSupplierOfpShareTheirOwnCompanysScopeButHaveNoWriteAccess() throws Exception {
		long visibleToA = createErgoInspection("FINAL", 130, customerA, null, true);
		long visibleToB = createErgoInspection("FINAL", 131, customerB, null, true);
		long forSupplierX = createAsSupplier(supplierXQuality, "INCOMING", 132);
		long forSupplierY = createAsSupplier(supplierYQuality, "INCOMING", 133);

		call(get("/api/quality/inspections/{id}", visibleToA), customerAKam, null).andExpect(status().isOk());
		call(get("/api/quality/inspections/{id}", visibleToB), customerAKam, null).andExpect(status().isNotFound());
		call(get("/api/quality/inspections/{id}", forSupplierX), supplierXOfp, null).andExpect(status().isOk());
		call(get("/api/quality/inspections/{id}", forSupplierY), supplierXOfp, null).andExpect(status().isNotFound());

		call(post("/api/quality/inspections"), customerAKam, inspectionForm("FINAL", 199))
			.andExpect(status().isForbidden());
		call(post("/api/quality/inspections"), supplierXOfp, inspectionForm("INCOMING", 198))
			.andExpect(status().isForbidden());
		call(put("/api/quality/inspections/{id}", forSupplierX), supplierXOfp, updateForm("1", "0"))
			.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- Customer Quality is view-only (judgment call)

	@Test
	void customerQualityHasNoWriteAccessAnywhereDespiteTheMatrixsLiteralCreateEditSubmitApprove() throws Exception {
		long id = createErgoInspection("FINAL", 100, customerA, supplierX, true);
		call(post("/api/quality/inspections"), customerAQuality, inspectionForm("FINAL", 101))
			.andExpect(status().isForbidden());
		call(put("/api/quality/inspections/{id}", id), customerAQuality, updateForm("10", "0"))
			.andExpect(status().isForbidden());
		call(post("/api/quality/inspections/{id}/submit", id), customerAQuality, updateForm("10", "0"))
			.andExpect(status().isForbidden());
		call(post("/api/quality/inspections/{id}/pass", id), customerAQuality, "{}").andExpect(status().isForbidden());
		call(post("/api/quality/inspections/{id}/reject", id), customerAQuality, "{\"reason\": \"x\"}")
			.andExpect(status().isForbidden());
		call(post("/api/quality/inspections/{id}/close", id), customerAQuality, "{}").andExpect(status().isForbidden());

		call(get("/api/quality/inspections/{id}", id), customerAQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", empty()));
	}

	/** Bug fix (2026-09-30): remarks is ERGO's own internal free text, dropped entirely from the supplier's own view
	 * (security review H1) - so a supplier's edit request can never carry the current value back. Applying it
	 * verbatim on every editor's request (the pre-fix behaviour) silently wiped out whatever ERGO Quality had
	 * written, since the supplier's client always sends a blank/absent remarks. */
	@Test
	void supplierEditDoesNotWipeRemarksOnlyErgoCanSet() throws Exception {
		long id = createAsSupplier(supplierXQuality, "INCOMING", 150);

		// ERGO Quality sets remarks while the record is still OPEN (editable). supplierCompanyId is re-sent as-is
		// (the supplier's own create already set it) so this edit is not itself treated as a reassignment, which
		// would otherwise clear remarks/defects on its own (see reassigningTheSupplierClearsAlreadyAuthoredRemarks...
		// above) - unrelated to the bug this test targets.
		call(put("/api/quality/inspections/{id}", id), ergoQuality, """
				{"partNumber": "PN-STD", "quantityChecked": 5, "quantityRejected": 0, "inspectionDate": "%s",
				 "remarks": "ERGO internal finding", "customerVisible": false, "supplierCompanyId": %d, "defects": []}"""
			.formatted(LocalDate.now(), supplierX)).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT remarks FROM inspections WHERE inspection_id = ?", String.class, id))
			.isEqualTo("ERGO internal finding");

		// the supplier edits quantity/defects without ever touching remarks in its own request (its view never
		// carries the field, so its client can't reproduce it) - the existing value must survive untouched
		call(put("/api/quality/inspections/{id}", id), supplierXQuality, updateForm("20", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.remarks").doesNotExist());
		assertThat(jdbc.queryForObject("SELECT remarks FROM inspections WHERE inspection_id = ?", String.class, id))
			.isEqualTo("ERGO internal finding");

		// the supplier submitting instead of editing must leave it untouched too
		call(post("/api/quality/inspections/{id}/submit", id), supplierXQuality, updateForm("20", "1"))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT remarks FROM inspections WHERE inspection_id = ?", String.class, id))
			.isEqualTo("ERGO internal finding");
	}

	// ---------------------------------------------------------------- audit

	@Test
	void createAndSubmitAreAudited() throws Exception {
		long id = createAsSupplier(supplierXQuality, "INCOMING", 110);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_logs WHERE entity_name = 'Inspection' AND record_id = ? AND event_type = 'CREATE'",
				Integer.class, id)).isGreaterThanOrEqualTo(1);

		call(post("/api/quality/inspections/{id}/submit", id), supplierXQuality, updateForm("10", "1"))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_logs WHERE entity_name = 'Inspection' AND record_id = ?
				AND field_name = 'resultStatus' AND new_value = 'IN_INSPECTION'""", Integer.class, id))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_logs WHERE entity_name = 'InspectionDefect' AND event_type = 'CREATE'",
				Integer.class)).isGreaterThanOrEqualTo(1);
	}

	/** Security review M3: replacing an already-populated defect set writes one explicit audit row carrying the old
	 * content, rather than a bare delete/insert pair with no record of what existed. */
	@Test
	void editingDefectsWritesAnAuditRowWithTheOldContentBeforeItIsReplaced() throws Exception {
		long id = createAsSupplier(supplierXQuality, "INCOMING", 140);
		call(put("/api/quality/inspections/{id}", id), supplierXQuality, """
				{"partNumber": "PN-STD", "quantityChecked": 5, "quantityRejected": 0, "inspectionDate": "%s",
				 "customerVisible": false,
				 "defects": [{"defectCategory": "Dimensional", "defectQuantity": 1, "severity": "MINOR"}]}"""
			.formatted(LocalDate.now())).andExpect(status().isOk());

		call(put("/api/quality/inspections/{id}", id), supplierXQuality, """
				{"partNumber": "PN-STD", "quantityChecked": 5, "quantityRejected": 0, "inspectionDate": "%s",
				 "customerVisible": false,
				 "defects": [{"defectCategory": "Surface", "defectQuantity": 2, "severity": "MAJOR"}]}"""
			.formatted(LocalDate.now())).andExpect(status().isOk());

		String oldValue = jdbc.queryForObject("""
				SELECT old_value FROM audit_logs WHERE entity_name = 'Inspection' AND record_id = ?
				AND event_type = 'DEFECTS_REPLACED'""", String.class, id);
		assertThat(oldValue).contains("Dimensional");
	}

	/** Security review M6 (matches NPD's identical H2 fix): reassigning the supplier on a record that already
	 * carries remarks/defects clears that authored content instead of carrying it across to the new party. */
	@Test
	void reassigningTheSupplierClearsAlreadyAuthoredRemarksAndDefects() throws Exception {
		long id = createErgoInspection("INCOMING", 141, null, supplierX, false);
		call(put("/api/quality/inspections/{id}", id), ergoQuality, """
				{"partNumber": "PN-STD", "quantityChecked": 5, "quantityRejected": 0, "inspectionDate": "%s",
				 "remarks": "supplier X's own note", "customerVisible": false, "supplierCompanyId": %d,
				 "defects": [{"defectCategory": "Dimensional", "defectQuantity": 1, "severity": "MINOR"}]}"""
			.formatted(LocalDate.now(), supplierX)).andExpect(status().isOk());

		call(put("/api/quality/inspections/{id}", id), ergoQuality, """
				{"partNumber": "PN-STD", "quantityChecked": 5, "quantityRejected": 0, "inspectionDate": "%s",
				 "customerVisible": false, "supplierCompanyId": %d, "defects": []}"""
			.formatted(LocalDate.now(), supplierY)).andExpect(status().isOk());

		assertThat(jdbc.queryForObject("SELECT remarks FROM inspections WHERE inspection_id = ?", String.class, id))
			.isNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM inspection_defects WHERE inspection_id = ?",
				Integer.class, id)).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_logs WHERE entity_name = 'Inspection' AND record_id = ?
				AND event_type = 'REASSIGNED_CONTENT_CLEARED'""", Integer.class, id)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- helpers

	@Test
	void theInspectionListIsPagedFilteredAndSearchedInTheQueryWithoutBreakingTwoWayAnonymity() throws Exception {
		long a = createPagedInspection("OFP", createOrder(customerA, supplierX), customerA, supplierX, true, "PN-PAGE-A", "LOT-PAGE-A");
		long b = createPagedInspection("OFP", createOrder(customerB, supplierY), customerB, supplierY, false, "PN-PAGE-B", "LOT-PAGE-B");
		String customerAName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?", String.class, customerA);
		String customerBName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?", String.class, customerB);
		String supplierXName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?", String.class, supplierX);

		// paging: newest first, one row a page; sizes and pages are clamped
		call(get("/api/quality/inspections?size=1"), ergoQuality, null).andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value((int) b)).andExpect(jsonPath("$.size").value(1))
			.andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
		call(get("/api/quality/inspections?size=1&page=1"), ergoQuality, null).andExpect(jsonPath("$.items[0].id").value((int) a));
		call(get("/api/quality/inspections?size=100000&page=-3"), ergoQuality, null).andExpect(jsonPath("$.size").value(100)).andExpect(jsonPath("$.page").value(0));

		// each side sees exactly its own (the customer only what is released to it), searching only what its row shows
		call(get("/api/quality/inspections"), customerAQuality, null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value((int) a));
		call(get("/api/quality/inspections"), customerBQuality, null).andExpect(jsonPath("$.total").value(0)); // B is not customer-visible
		call(get("/api/quality/inspections"), supplierXQuality, null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value((int) a));
		call(get("/api/quality/inspections?q=pn-page-a"), customerAQuality, null).andExpect(jsonPath("$.total").value(1));
		call(get("/api/quality/inspections?q=lot-page-a"), supplierXQuality, null).andExpect(jsonPath("$.total").value(1));
		call(get("/api/quality/inspections?q=pn-page-b"), customerAQuality, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/inspections").param("q", supplierXName), customerAQuality, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/inspections").param("q", customerAName), supplierXQuality, null).andExpect(jsonPath("$.total").value(0));

		// ERGO also searches the company names; the account-scoped KAM only within its own accounts
		call(get("/api/quality/inspections").param("q", customerBName), ergoQuality, null).andExpect(jsonPath("$.items[*].id", hasItem((int) b)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) a))));
		call(get("/api/quality/inspections").param("q", customerBName), ergoKam, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/inspections").param("q", "%"), ergoQuality, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/inspections").param("q", "\u0000PN-PAGE-A"), ergoQuality, null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));

		// filters narrow inside the same query (and cannot widen a module-linked role's scope); unknown values are refused
		call(get("/api/quality/inspections?status=OPEN&q=pn-page-a"), ergoQuality, null).andExpect(jsonPath("$.total").value(1));
		call(get("/api/quality/inspections?status=PASSED&q=pn-page-a"), ergoQuality, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/inspections?linkedModule=OFP&q=pn-page-a"), ergoQuality, null).andExpect(jsonPath("$.total").value(1));
		call(get("/api/quality/inspections?linkedModule=NPD&q=pn-page-a"), ergoQuality, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/inspections?linkedModule=NPD&q=pn-page-a"), ergoOfp, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/inspections?status=BOGUS"), ergoQuality, null).andExpect(status().isUnprocessableEntity());
		call(get("/api/quality/inspections?linkedModule=BOGUS"), ergoQuality, null).andExpect(status().isUnprocessableEntity());
	}

	private long createPagedInspection(String linkedModule, long linkedRecordId, long customerCompanyId, long supplierCompanyId,
			boolean customerVisible, String partNumber, String batchLotNo) throws Exception {
		String body = """
				{"linkedModule": "%s", "linkedRecordId": %d, "partNumber": "%s", "batchLotNo": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "customerVisible": %s}"""
			.formatted(linkedModule, linkedRecordId, partNumber, batchLotNo, customerCompanyId, supplierCompanyId, customerVisible);
		return JsonPath
			.<Number>read(call(post("/api/quality/inspections"), ergoQuality, body).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id")
			.longValue();
	}

	private long createAsSupplier(Login supplier, String linkedModule, long linkedRecordId) throws Exception {
		return JsonPath
			.<Number>read(call(post("/api/quality/inspections"), supplier, inspectionForm(linkedModule, linkedRecordId))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString(), "$.id")
			.longValue();
	}

	private long createErgoInspection(String linkedModule, long linkedRecordId, Long customerCompanyId,
			Long supplierCompanyId, boolean customerVisible) throws Exception {
		String body = """
				{"linkedModule": "%s", "linkedRecordId": %d, "partNumber": "PN-ERGO", %s %s "customerVisible": %s}"""
			.formatted(linkedModule, linkedRecordId, customerCompanyId == null ? "" : "\"customerCompanyId\": " + customerCompanyId + ",",
					supplierCompanyId == null ? "" : "\"supplierCompanyId\": " + supplierCompanyId + ",", customerVisible);
		return JsonPath
			.<Number>read(call(post("/api/quality/inspections"), ergoQuality, body).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id")
			.longValue();
	}

	private static String inspectionForm(String linkedModule, long linkedRecordId) {
		return """
				{"linkedModule": "%s", "linkedRecordId": %d, "partNumber": "PN-STD", "customerVisible": false}"""
			.formatted(linkedModule, linkedRecordId);
	}

	private static String updateForm(String quantityChecked, String quantityRejected) {
		return """
				{"partNumber": "PN-STD", "quantityChecked": %s, "quantityRejected": %s,
				 "inspectionDate": "%s", "customerVisible": false,
				 "defects": [{"defectCategory": "Dimensional", "defectQuantity": 1, "severity": "MINOR"}]}"""
			.formatted(quantityChecked, quantityRejected, LocalDate.now());
	}

	/** A minimal OFP order (M5's test target), no MockMvc call needed: {@code orders} has no dependency on RFQ/NPD. */
	private long createOrder(long customerCompanyId, long supplierCompanyId) {
		return jdbc.queryForObject("""
				INSERT INTO orders (order_number, customer_company_id, supplier_company_id, part_number,
				                    ordered_quantity, current_status)
				VALUES (?, ?, ?, 'PN-ORDER', 10, 'ORDER_CREATED') RETURNING order_id""", Long.class,
				"TO" + SEQ.incrementAndGet(), customerCompanyId, supplierCompanyId);
	}

	/** A minimal NPD project (M5's other test target): {@code npd_projects.source_rfq_id} is a mandatory unique FK,
	 * so a throwaway RFQ row is inserted first. */
	private long createNpdProject(long customerCompanyId, long supplierCompanyId) {
		long currencyId = users.currency("INR");
		long rfqId = jdbc.queryForObject("""
				INSERT INTO rfqs (rfq_number, source_type, customer_company_id, created_by_user_id, status, priority,
				                  currency_id)
				VALUES (?, 'CUSTOMER', ?, ?, 'CUSTOMER_APPROVED', 'NORMAL', ?) RETURNING rfq_id""",
				Long.class, "TR" + SEQ.incrementAndGet(), customerCompanyId, ergoAdmin.userId(), currencyId);
		long rfqItemId = jdbc.queryForObject(
				"INSERT INTO rfq_items (rfq_id, line_no, part_number) VALUES (?, 1, 'PN-RFQ') RETURNING rfq_item_id",
				Long.class, rfqId);
		return jdbc.queryForObject("""
				INSERT INTO npd_projects (npd_number, source_rfq_id, source_rfq_item_id, customer_company_id, supplier_company_id,
				                          part_number, stage, created_by_user_id)
				VALUES (?, ?, ?, ?, ?, 'PN-NPD', 'PLANNING', ?) RETURNING npd_project_id""", Long.class,
				"TN" + SEQ.incrementAndGet(), rfqId, rfqItemId, customerCompanyId, supplierCompanyId, ergoAdmin.userId());
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

}
