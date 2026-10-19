package com.ergo.connect.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
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
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.ergo.connect.quality.domain.DeviationService;
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 5c: deviations (SRS §10.8, §13.6.4, §14.7) - record scope with 2 customers and 2 suppliers, dual-approval
 * orchestration (both required and not-required paths), approve/reject/send-back permission boundaries (ERGO cannot
 * decide the customer's row and vice versa, Supplier Quality never approves), automatic expiry, versioning and audit
 * - same coverage depth as {@link NcrTests}.
 */
@IntegrationTest
class DeviationTests {

	/** Unique order/NPD numbers (each column is UNIQUE), same precedent as {@link NcrTests}. */
	private static final AtomicInteger SEQ = new AtomicInteger(900_000);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DeviationService deviationService;

	TestUsers users;

	long customerA;

	long customerB;

	long supplierX;

	long supplierY;

	Login ergoManagement;

	Login ergoQuality;

	Login ergoAdmin;

	Login ergoKam;

	Login ergoNpd;

	Login ergoOfp;

	Login ergoRfq;

	Login customerAQuality;

	/** A second Customer A Quality user, distinct from {@link #customerAQuality} - used to prove a non-creator user
	 * of the same company can still decide a row the creator itself is blocked from (security review H2). */
	Login customerAQuality2;

	Login customerBQuality;

	Login supplierXQuality;

	Login supplierYQuality;

	Login supplierXNpd;

	Login supplierXOfp;

	Login supplierXKam;

	Login customerANpd;

	Login customerAOfp;

	Login customerAKam;

	// Note: CUSTOMER_MANAGEMENT is intentionally omitted - the role is seeded inactive for MVP (V2 migration, SRS
	// §9.2/§16.2) and cannot log in at all, even though DeviationService.CUSTOMER_VIEWERS still names it for when
	// it is activated later.

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		customerA = users.company("CUSTOMER");
		customerB = users.company("CUSTOMER");
		supplierX = users.company("SUPPLIER");
		supplierY = users.company("SUPPLIER");
		ergoManagement = users.login(ergo, "ERGO_MANAGEMENT");
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		ergoAdmin = users.login(ergo, "ERGO_ADMIN");
		ergoKam = users.login(ergo, "ERGO_KAM");
		users.assignAccounts(ergoKam.userId(), customerA);
		ergoNpd = users.login(ergo, "ERGO_NPD");
		ergoOfp = users.login(ergo, "ERGO_OFP");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		customerAQuality = users.login(customerA, "CUSTOMER_QUALITY");
		customerAQuality2 = users.login(customerA, "CUSTOMER_QUALITY");
		customerBQuality = users.login(customerB, "CUSTOMER_QUALITY");
		supplierXQuality = users.login(supplierX, "SUPPLIER_QUALITY");
		supplierYQuality = users.login(supplierY, "SUPPLIER_QUALITY");
		supplierXNpd = users.login(supplierX, "SUPPLIER_NPD");
		supplierXOfp = users.login(supplierX, "SUPPLIER_OFP");
		supplierXKam = users.login(supplierX, "SUPPLIER_KAM");
		customerANpd = users.login(customerA, "CUSTOMER_NPD");
		customerAOfp = users.login(customerA, "CUSTOMER_OFP");
		customerAKam = users.login(customerA, "CUSTOMER_KAM");
	}

	// ---------------------------------------------------------------- create / role check

	@Test
	void everyRoleThatActsOnQualityMayRaiseADeviationAndTheViewOnlyOnesMayNot() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		// role sheet: ERGO Management / Quality ("O"), ERGO OFP ("A", OFP-linked only), Customer NPD / Quality ("CO"),
		// Supplier Quality / KAM ("A")
		call(post("/api/quality/deviations"), ergoManagement, deviationForm(orderId, false)).andExpect(status().isCreated());
		call(post("/api/quality/deviations"), ergoQuality, deviationForm(orderId, false)).andExpect(status().isCreated());
		call(post("/api/quality/deviations"), ergoOfp, deviationForm(orderId, false)).andExpect(status().isCreated());
		call(post("/api/quality/deviations"), customerAQuality, deviationForm(orderId, false))
			.andExpect(status().isCreated());
		call(post("/api/quality/deviations"), customerANpd, deviationForm(orderId, false))
			.andExpect(status().isCreated());
		call(post("/api/quality/deviations"), supplierXQuality, deviationForm(orderId, false))
			.andExpect(status().isCreated());
		call(post("/api/quality/deviations"), supplierXKam, deviationForm(orderId, false))
			.andExpect(status().isCreated());

		for (Login denied : List.of(ergoAdmin, ergoKam, ergoNpd, ergoRfq, customerAOfp, customerAKam, supplierXNpd,
				supplierXOfp)) {
			call(post("/api/quality/deviations"), denied, deviationForm(orderId, false)).andExpect(status().isForbidden());
		}
	}

	@Test
	void createIsAuditedAndGetsAGeneratedNumberAndAnInitialVersion() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsErgo(ergoQuality, orderId, false);

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_logs WHERE entity_name = 'Deviation' AND record_id = ? AND event_type = 'CREATE'",
				Integer.class, id)).isGreaterThanOrEqualTo(1);
		String number = jdbc.queryForObject("SELECT deviation_number FROM deviations WHERE deviation_id = ?",
				String.class, id);
		assertThat(number).startsWith("DEV-");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM deviation_versions WHERE deviation_id = ?", Integer.class,
				id)).isEqualTo(1);
	}

	@Test
	void linkedModuleMustBeNpdOrOfp() throws Exception {
		call(post("/api/quality/deviations"), ergoQuality, """
				{"linkedModule": "INCOMING", "linkedRecordId": 1, "partNumber": "PN", "deviationDescription": "d",
				 "customerApprovalRequiredFlag": false, "validityQuantity": 10, "validityEndDate": "%s"}"""
			.formatted(LocalDate.now().plusDays(30))).andExpect(status().isUnprocessableContent());
	}

	// ---------------------------------------------------------------- record scope: 2 customers, 2 suppliers

	@Test
	void supplierCannotSeeAnotherSuppliersDeviation() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsErgo(ergoQuality, orderId, false);

		call(get("/api/quality/deviations/{id}", id), supplierXQuality, null).andExpect(status().isOk());
		call(get("/api/quality/deviations"), supplierXQuality, null)
			.andExpect(jsonPath("$[*].id", hasItem((int) id)));

		call(get("/api/quality/deviations/{id}", id), supplierYQuality, null).andExpect(status().isNotFound());
		call(get("/api/quality/deviations"), supplierYQuality, null)
			.andExpect(jsonPath("$[*].id", not(hasItem((int) id))));
	}

	@Test
	void customerCannotSeeAnotherCustomersDeviation() throws Exception {
		long orderA = createOrder(customerA, supplierX);
		long orderB = createOrder(customerB, supplierY);
		long forA = createAsCustomer(customerAQuality, orderA);
		long forB = createAsCustomer(customerBQuality, orderB);

		call(get("/api/quality/deviations/{id}", forA), customerAQuality, null).andExpect(status().isOk());
		call(get("/api/quality/deviations/{id}", forB), customerAQuality, null).andExpect(status().isNotFound());
		call(get("/api/quality/deviations"), customerAQuality, null)
			.andExpect(jsonPath("$[*].id", hasItem((int) forA)))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) forB))));
	}

	@Test
	void ergoKamSeesOnlyItsAssignedCustomersDeviationsAndErgoNpdOfpSeeOnlyTheirOwnLinkedModule() throws Exception {
		long orderA = createOrder(customerA, supplierX);
		long orderB = createOrder(customerB, supplierY);
		long forA = createAsErgo(ergoQuality, orderA, false);
		long forB = createAsErgo(ergoQuality, orderB, false);
		long npdProjectId = createNpdProject(customerA, supplierX);
		long npdOne = createAsErgoLinked(ergoQuality, "NPD", npdProjectId, false);
		long ofpOne = createAsErgo(ergoQuality, orderA, false);

		call(get("/api/quality/deviations"), ergoKam, null).andExpect(jsonPath("$[*].id", hasItem((int) forA)))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) forB))));
		call(get("/api/quality/deviations/{id}", forB), ergoKam, null).andExpect(status().isNotFound());

		call(get("/api/quality/deviations"), ergoNpd, null).andExpect(jsonPath("$[*].id", hasItem((int) npdOne)))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) ofpOne))));
		call(get("/api/quality/deviations"), ergoOfp, null).andExpect(jsonPath("$[*].id", hasItem((int) ofpOne)))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) npdOne))));

		// ERGO RFQ and Admin view every deviation (role sheet "V")
		for (Login viewer : List.of(ergoRfq, ergoAdmin)) {
			call(get("/api/quality/deviations"), viewer, null).andExpect(jsonPath("$[*].id", hasItem((int) forB)));
			call(get("/api/quality/deviations/{id}", forA), viewer, null).andExpect(status().isOk());
		}
	}

	/** Security review M5 precedent ({@code NcrService.applyLinkedCompanies}): companies always come from the linked
	 * OFP order itself, never the client - and a supplier not actually on that order is refused entirely. */
	@Test
	void companiesAreDerivedFromTheLinkedOrderNeverFromTheClientAndASupplierNotOnItIsRefused() throws Exception {
		long orderId = createOrder(customerA, supplierX);

		String created = call(post("/api/quality/deviations"), ergoQuality, deviationForm(orderId, false))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long id = ((Number) JsonPath.read(created, "$.id")).longValue();
		call(get("/api/quality/deviations/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.customerCompanyId").value((int) customerA))
			.andExpect(jsonPath("$.supplierCompanyId").value((int) supplierX));

		call(post("/api/quality/deviations"), supplierYQuality, deviationForm(orderId, false))
			.andExpect(status().isUnprocessableContent());
		call(post("/api/quality/deviations"), customerBQuality, deviationForm(orderId, false))
			.andExpect(status().isUnprocessableContent());
	}

	/** Security review H3 fix: extended beyond role labels/company identity (the original gap) to also cover
	 * approval-row comment text, which is free-form and can itself name the other party. */
	@Test
	void twoWayAnonymityNeitherPartyLearnsTheOtherIncludingApprovalComments() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		// creator is ergoManagement, decider is ergoQuality (security review H2: a creator may not decide its own row)
		long id = createAsErgo(ergoManagement, orderId, true); // customerApprovalRequiredFlag forces customerVisible later
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());
		// the ERGO_QUALITY row's comment is, by design, shown in full to the supplier (DeviationDtos's own H3
		// javadoc) - so it is kept free of anything that would itself leak the customer's identity
		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality,
				"{\"reason\": \"check the batch heat-treat records before release\"}").andExpect(status().isOk());
		// the CUSTOMER_QUALITY row's comment is the customer's own text and must never reach the supplier
		call(post("/api/quality/deviations/{id}/approve", id), customerAQuality,
				"{\"reason\": \"approved pending the next incoming inspection\"}").andExpect(status().isOk());

		String supplierView = call(get("/api/quality/deviations/{id}", id), supplierXQuality, null)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(supplierView).doesNotContain("customerCompanyId").doesNotContain("\"customer\"")
			.doesNotContain("approved pending the next incoming inspection"); // security review H3: CUSTOMER_QUALITY
																				// row's comment redacted for the supplier

		String customerView = call(get("/api/quality/deviations/{id}", id), customerAQuality, null)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(customerView).doesNotContain("supplierCompanyId").doesNotContain("\"supplier\"")
			.doesNotContain("check the batch heat-treat records"); // security review H3: ERGO_QUALITY row's comment
																		// redacted for the customer

		String ergoView = call(get("/api/quality/deviations/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.customerCompanyId").value((int) customerA))
			.andExpect(jsonPath("$.supplierCompanyId").value((int) supplierX))
			.andReturn()
			.getResponse()
			.getContentAsString();
		// ERGO sees both comments in full (internal audience, both rows "its own")
		assertThat(ergoView).contains("check the batch heat-treat records")
			.contains("approved pending the next incoming inspection");
	}

	// ---------------------------------------------------------------- customerVisible (judgment call, class comment)

	@Test
	void customerCreatedDeviationIsVisibleToItsOwnCompanyImmediately() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsCustomer(customerAQuality, orderId);
		call(get("/api/quality/deviations/{id}", id), customerAQuality, null).andExpect(status().isOk());
	}

	@Test
	void ergoCreatedDeviationWithNoCustomerApprovalRequiredStaysHiddenFromTheCustomer() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsErgo(ergoQuality, orderId, false);
		call(get("/api/quality/deviations/{id}", id), customerAQuality, null).andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- dual approval: not required

	@Test
	void whenCustomerApprovalIsNotRequiredErgoApprovalAloneFinalizesIt() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		// creator is ergoManagement, decider is ergoQuality (security review H2: a creator may not decide its own row)
		long id = createAsErgo(ergoManagement, orderId, false);

		call(get("/api/quality/deviations/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.approvals.length()").value(1)); // only the ERGO_QUALITY row exists

		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.currentStatus").value("UNDER_REVIEW"));

		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("APPROVED"));

		call(post("/api/quality/deviations/{id}/close", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CLOSED"));
	}

	// ---------------------------------------------------------------- dual approval: required

	@Test
	void whenCustomerApprovalIsRequiredBothRowsMustApproveBeforeFinalApproval() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		// creator is ergoManagement, decider is ergoQuality (security review H2: a creator may not decide its own row)
		long id = createAsErgo(ergoManagement, orderId, true);

		call(get("/api/quality/deviations/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.approvals.length()").value(2)) // both rows exist up front
			.andExpect(jsonPath("$.approvals[0].decision").value("PENDING"))
			.andExpect(jsonPath("$.approvals[1].decision").value("PENDING"));

		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());

		// the customer cannot even see this deviation yet (customerVisible is still false - forced true only once
		// ERGO approves its own row below), so it 404s rather than revealing a conflict
		call(post("/api/quality/deviations/{id}/approve", id), customerAQuality, "{}").andExpect(status().isNotFound());

		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CUSTOMER_APPROVAL_PENDING"))
			.andExpect(jsonPath("$.customerVisible").value(true))
			// row boundary (security review M2): the ERGO row is APPROVED, the customer's own row stays untouched
			.andExpect(jsonPath("$.approvals[?(@.approverRole=='ERGO_QUALITY')].decision").value("APPROVED"))
			.andExpect(jsonPath("$.approvals[?(@.approverRole=='CUSTOMER_QUALITY')].decision").value("PENDING"));

		// an ERGO approver cannot decide the customer's row
		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}").andExpect(status().isConflict());

		// the deviation is now visible to the customer, forced by the ERGO approval itself
		call(get("/api/quality/deviations/{id}", id), customerAQuality, null).andExpect(status().isOk());

		call(post("/api/quality/deviations/{id}/approve", id), customerAQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("APPROVED"))
			// row boundary: both rows end APPROVED, the ERGO row's earlier decision was not disturbed
			.andExpect(jsonPath("$.approvals[?(@.approverRole=='ERGO_QUALITY')].decision").value("APPROVED"))
			.andExpect(jsonPath("$.approvals[?(@.approverRole=='CUSTOMER_QUALITY')].decision").value("APPROVED"));
	}

	/** Security review M2: extended beyond APPROVE to REJECT/SEND_BACK/START_REVIEW - Supplier Quality has none of
	 * these rights either, and all four are blocked at the controller's own role check (403), before any record-scope
	 * or status check runs. */
	@Test
	void supplierQualityNeverHasAnApproveRejectSendBackOrStartReviewRight() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsErgo(ergoQuality, orderId, false);

		call(post("/api/quality/deviations/{id}/start-review", id), supplierXQuality, null)
			.andExpect(status().isForbidden());
		call(post("/api/quality/deviations/{id}/approve", id), supplierXQuality, "{}").andExpect(status().isForbidden());
		call(post("/api/quality/deviations/{id}/reject", id), supplierXQuality, "{\"reason\": \"x\"}")
			.andExpect(status().isForbidden());
		call(post("/api/quality/deviations/{id}/send-back", id), supplierXQuality, "{\"reason\": \"x\"}")
			.andExpect(status().isForbidden());

		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());
		call(get("/api/quality/deviations/{id}", id), supplierXQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", not(hasItem("APPROVE"))));
	}

	/** Security review M2: ERGO KAM has no approve right either (SRS §10.8), blocked the same way. */
	@Test
	void ergoKamNeverHasAnApproveRight() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsErgo(ergoQuality, orderId, false);
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());

		call(post("/api/quality/deviations/{id}/approve", id), ergoKam, "{}").andExpect(status().isForbidden());
	}

	/** Role sheet: ERGO Admin is view-only on Quality - it creates, edits and approves nothing; ERGO Management ("O")
	 * decides the ERGO approval row. */
	@Test
	void ergoAdminIsViewOnlyAndErgoManagementMayApprove() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsErgo(ergoQuality, orderId, false);
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());

		call(post("/api/quality/deviations"), ergoAdmin, deviationForm(orderId, false))
			.andExpect(status().isForbidden());
		call(put("/api/quality/deviations/{id}", id), ergoAdmin, updateForm(false, false))
			.andExpect(status().isForbidden());
		call(post("/api/quality/deviations/{id}/approve", id), ergoAdmin, "{}").andExpect(status().isForbidden());
		call(get("/api/quality/deviations/{id}", id), ergoAdmin, null).andExpect(status().isOk());

		call(post("/api/quality/deviations/{id}/approve", id), ergoManagement, "{}")
			.andExpect(jsonPath("$.currentStatus").value("APPROVED"));
	}

	// ---------------------------------------------------------------- reject / send-back: reason required

	@Test
	void rejectRequiresAReasonAndIsTerminal() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		// creator is ergoManagement, decider is ergoQuality (security review H2: a creator may not decide its own row)
		long id = createAsErgo(ergoManagement, orderId, false);
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());

		call(post("/api/quality/deviations/{id}/reject", id), ergoQuality, "{}")
			.andExpect(status().isUnprocessableContent());
		call(post("/api/quality/deviations/{id}/reject", id), ergoQuality, "{\"reason\": \"non-conforming\"}")
			.andExpect(jsonPath("$.currentStatus").value("REJECTED"));

		assertThat(jdbc.queryForObject("""
				SELECT action_reason FROM audit_logs WHERE entity_name = 'Deviation' AND record_id = ?
				AND field_name = 'currentStatus' AND new_value = 'REJECTED' ORDER BY audit_log_id DESC LIMIT 1""",
				String.class, id)).isEqualTo("non-conforming");

		// terminal: no further action is available
		call(get("/api/quality/deviations/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", empty()));
	}

	@Test
	void sendBackFromErgoRowReturnsToRequestedAndStartReviewReArmsIt() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		// creator is ergoManagement, decider is ergoQuality (security review H2: a creator may not decide its own row)
		long id = createAsErgo(ergoManagement, orderId, false);
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());

		call(post("/api/quality/deviations/{id}/send-back", id), ergoQuality, "{}")
			.andExpect(status().isUnprocessableContent());
		call(post("/api/quality/deviations/{id}/send-back", id), ergoQuality, "{\"reason\": \"need more detail\"}")
			.andExpect(jsonPath("$.currentStatus").value("REQUESTED"));

		// security review H1: this deviation was created by ERGO (ergoManagement), not the supplier - the supplier never
		// edits it, even while REQUESTED
		call(put("/api/quality/deviations/{id}", id), supplierXQuality, updateForm(false, false))
			.andExpect(status().isConflict());
		// an ERGO editor may always edit while REQUESTED, regardless of who created it
		call(put("/api/quality/deviations/{id}", id), ergoQuality, updateForm(false, false)).andExpect(status().isOk());

		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.currentStatus").value("UNDER_REVIEW"));
		// security review H1: edit is frozen for everyone, including ERGO, once review has started
		call(put("/api/quality/deviations/{id}", id), ergoQuality, updateForm(false, false))
			.andExpect(status().isConflict());
		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("APPROVED"));
	}

	@Test
	void sendBackFromCustomerRowReturnsToUnderReviewAndReArmsTheErgoRow() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		// creator is ergoManagement, decider is ergoQuality (security review H2: a creator may not decide its own row)
		long id = createAsErgo(ergoManagement, orderId, true);
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());
		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CUSTOMER_APPROVAL_PENDING"));

		call(post("/api/quality/deviations/{id}/send-back", id), customerAQuality, "{\"reason\": \"need tighter scope\"}")
			.andExpect(jsonPath("$.currentStatus").value("UNDER_REVIEW"));

		// the ERGO row was re-armed: customer cannot decide now (its own row stays SENT_BACK, not re-askable yet),
		// and ERGO must approve again before the customer gets another turn
		call(post("/api/quality/deviations/{id}/approve", id), customerAQuality, "{}").andExpect(status().isConflict());
		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CUSTOMER_APPROVAL_PENDING"));
		call(post("/api/quality/deviations/{id}/approve", id), customerAQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("APPROVED"));
	}

	// ---------------------------------------------------------------- close

	@Test
	void closeOnlyFromApprovedOrExpiredAndOnlyErgoQualityAdmin() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		// creator is ergoManagement, decider is ergoQuality (security review H2: a creator may not decide its own row)
		long id = createAsErgo(ergoManagement, orderId, false);
		call(post("/api/quality/deviations/{id}/close", id), ergoQuality, "{}").andExpect(status().isConflict());

		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());
		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}").andExpect(status().isOk());

		call(post("/api/quality/deviations/{id}/close", id), supplierXQuality, "{}").andExpect(status().isForbidden());
		call(post("/api/quality/deviations/{id}/close", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CLOSED"));
	}

	// ---------------------------------------------------------------- automatic expiry

	@Test
	void expiredDeviationsAreAutomaticallyExpiredByTheScheduledJob() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		// Validity is set directly via jdbc below (bypassing the @FutureOrPresent create-time validation added by
		// security review L1) so the deviation can already be APPROVED before its validity lapses - the create form
		// itself can no longer carry a past validityEndDate.
		// creator is ergoManagement, decider is ergoQuality (security review H2: a creator may not decide its own row)
		long id = createAsErgoWithValidity(ergoManagement, orderId, false, LocalDate.now().plusDays(1));
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());
		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("APPROVED"));
		jdbc.update("UPDATE deviations SET validity_end_date = ? WHERE deviation_id = ?", LocalDate.now().minusDays(1),
				id);

		deviationService.expireOverdueDeviations();

		assertThat(jdbc.queryForObject("SELECT current_status FROM deviations WHERE deviation_id = ?", String.class,
				id)).isEqualTo("EXPIRED");
		call(post("/api/quality/deviations/{id}/close", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CLOSED"));
	}

	// ---------------------------------------------------------------- versioning (SRS §15.4)

	@Test
	void everyApprovalDecisionWritesAnImmutableVersionSnapshot() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		// creator is ergoManagement, decider is ergoQuality (security review H2: a creator may not decide its own row)
		long id = createAsErgo(ergoManagement, orderId, false); // version 1: initial submission
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());
		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}").andExpect(status().isOk()); // version 2

		assertThat(jdbc.queryForObject("SELECT count(*) FROM deviation_versions WHERE deviation_id = ?", Integer.class,
				id)).isEqualTo(2);
		assertThat(jdbc.queryForObject(
				"SELECT snapshot::text FROM deviation_versions WHERE deviation_id = ? AND version_no = 2", String.class,
				id)).contains("APPROVED");

		// append-only: UPDATE/DELETE on deviation_versions are blocked at the DB level (same trigger as
		// supplier_quote_versions - QuoteTests asserts this identically).
		assertThatThrownBy(() -> jdbc.update("UPDATE deviation_versions SET version_no = 999"))
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.execute("TRUNCATE deviation_versions")).hasMessageContaining("append-only");
	}

	// ---------------------------------------------------------------- edit boundary (security review H1)

	@Test
	void onlyTheCreatingCompanysEditorMayEditWhileRequestedButErgoMayAlways() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsCustomer(customerAQuality, orderId); // customer A is the creator
		// security review H3: supplierVisible starts false for a customer's own creation - ERGO shares it with the
		// supplier first so this test can isolate the H1 edit-boundary check from the H3 visibility gate; customer
		// A's own visibility (already true since it created this itself) is left untouched
		call(put("/api/quality/deviations/{id}", id), ergoQuality, updateForm(true, true)).andExpect(status().isOk());

		// the supplier can now see it, but it is not the creator's company and so cannot edit it
		call(get("/api/quality/deviations/{id}", id), supplierXQuality, null).andExpect(status().isOk());
		call(put("/api/quality/deviations/{id}", id), supplierXQuality, updateForm(true, true))
			.andExpect(status().isConflict());
		// another Customer A user (same company as the creator) may edit
		call(put("/api/quality/deviations/{id}", id), customerAQuality2, updateForm(true, true))
			.andExpect(status().isOk());
		// ERGO may always edit a REQUESTED deviation, regardless of who created it
		call(put("/api/quality/deviations/{id}", id), ergoQuality, updateForm(true, true)).andExpect(status().isOk());
	}

	@Test
	void editIsFrozenForEveryoneOnceUnderReviewNotJustTheOtherParty() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsErgo(ergoManagement, orderId, false);
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());

		// even ERGO (the creator's own side here) cannot edit while UNDER_REVIEW
		call(put("/api/quality/deviations/{id}", id), ergoManagement, updateForm(false, false)).andExpect(status().isConflict());
		call(put("/api/quality/deviations/{id}", id), ergoQuality, updateForm(false, false)).andExpect(status().isConflict());
	}

	// ---------------------------------------------------------------- self-approval (security review H2)

	@Test
	void theDeviationsOwnCreatorCannotApproveRejectOrSendBackTheErgoQualityRow() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsErgo(ergoQuality, orderId, false);
		call(post("/api/quality/deviations/{id}/start-review", id), ergoManagement, null).andExpect(status().isOk());

		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}").andExpect(status().isConflict());
		call(post("/api/quality/deviations/{id}/reject", id), ergoQuality, "{\"reason\": \"x\"}")
			.andExpect(status().isConflict());
		call(post("/api/quality/deviations/{id}/send-back", id), ergoQuality, "{\"reason\": \"x\"}")
			.andExpect(status().isConflict());

		// a different ERGO approver (not the creator) may still decide it
		call(post("/api/quality/deviations/{id}/approve", id), ergoManagement, "{}")
			.andExpect(jsonPath("$.currentStatus").value("APPROVED"));
	}

	@Test
	void theDeviationsOwnCreatorCannotApproveItsCustomerQualityRowEither() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsCustomer(customerAQuality, orderId, true); // customer A itself created it
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());
		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CUSTOMER_APPROVAL_PENDING"));

		call(post("/api/quality/deviations/{id}/approve", id), customerAQuality, "{}").andExpect(status().isConflict());

		// a different Customer A user (not the creator) may still decide it
		call(post("/api/quality/deviations/{id}/approve", id), customerAQuality2, "{}")
			.andExpect(jsonPath("$.currentStatus").value("APPROVED"));
	}

	// ---------------------------------------------------------------- linked inspection scope (security review M1)

	@Test
	void linkedInspectionMustBelongToTheSameLinkedRecordAsTheDeviation() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long otherOrderId = createOrder(customerB, supplierY);
		long wrongInspectionId = createInspection("OFP", otherOrderId, customerB, supplierY);
		long rightInspectionId = createInspection("OFP", orderId, customerA, supplierX);

		String withWrongInspection = """
				{"linkedModule": "OFP", "linkedRecordId": %d, "inspectionId": %d, "partNumber": "PN-DEV",
				 "deviationDescription": "d", "customerApprovalRequiredFlag": false, "validityQuantity": 10,
				 "validityEndDate": "%s"}""".formatted(orderId, wrongInspectionId, LocalDate.now().plusDays(30));
		call(post("/api/quality/deviations"), ergoQuality, withWrongInspection)
			.andExpect(status().isUnprocessableContent());

		String withNonExistentInspection = """
				{"linkedModule": "OFP", "linkedRecordId": %d, "inspectionId": 999999999, "partNumber": "PN-DEV",
				 "deviationDescription": "d", "customerApprovalRequiredFlag": false, "validityQuantity": 10,
				 "validityEndDate": "%s"}""".formatted(orderId, LocalDate.now().plusDays(30));
		// same generic message/status whether the id doesn't exist or belongs elsewhere - a caller cannot tell which
		call(post("/api/quality/deviations"), ergoQuality, withNonExistentInspection)
			.andExpect(status().isUnprocessableContent());

		String withRightInspection = """
				{"linkedModule": "OFP", "linkedRecordId": %d, "inspectionId": %d, "partNumber": "PN-DEV",
				 "deviationDescription": "d", "customerApprovalRequiredFlag": false, "validityQuantity": 10,
				 "validityEndDate": "%s"}""".formatted(orderId, rightInspectionId, LocalDate.now().plusDays(30));
		call(post("/api/quality/deviations"), ergoQuality, withRightInspection).andExpect(status().isCreated());
	}

	// ---------------------------------------------------------------- cross-company 404 beyond read (security review M2)

	@Test
	void crossCompanyActionsAllReturnNotFoundNotForbiddenOrConflict() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsErgo(ergoManagement, orderId, true);
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());
		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CUSTOMER_APPROVAL_PENDING"));

		// supplierY is not this deviation's supplier
		call(put("/api/quality/deviations/{id}", id), supplierYQuality, updateForm(false, false))
			.andExpect(status().isNotFound());
		// customerB is not this deviation's customer
		call(post("/api/quality/deviations/{id}/approve", id), customerBQuality, "{}").andExpect(status().isNotFound());
		call(post("/api/quality/deviations/{id}/reject", id), customerBQuality, "{\"reason\": \"x\"}")
			.andExpect(status().isNotFound());
		call(post("/api/quality/deviations/{id}/send-back", id), customerBQuality, "{\"reason\": \"x\"}")
			.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- visibility sweep across every viewer role

	/** Security review M2: every role named in SRS §10.8's viewer set - not just *Quality - can read a deviation
	 * once it is visible to its company. */
	@Test
	void everySupplierAndCustomerViewerRoleCanReadADeviationOnceVisible() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsErgo(ergoManagement, orderId, true); // supplierVisible true immediately (ERGO-created)
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());
		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.customerVisible").value(true)); // forces customerVisible true

		for (Login supplierViewer : List.of(supplierXQuality, supplierXNpd, supplierXOfp, supplierXKam)) {
			call(get("/api/quality/deviations/{id}", id), supplierViewer, null).andExpect(status().isOk());
		}
		for (Login customerViewer : List.of(customerAQuality, customerANpd, customerAOfp, customerAKam)) {
			call(get("/api/quality/deviations/{id}", id), customerViewer, null).andExpect(status().isOk());
		}
	}

	/** Security review H3: a customer-created deviation's free text must not reach the supplier until ERGO reviews
	 * it (supplierVisible starts false for a customer's own creation), mirroring the existing customerVisible gate
	 * for ERGO-/supplier-created deviations. */
	@Test
	void customerCreatedDeviationStaysHiddenFromTheSupplierUntilErgoSharesIt() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsCustomer(customerAQuality, orderId);

		call(get("/api/quality/deviations/{id}", id), supplierXQuality, null).andExpect(status().isNotFound());

		call(put("/api/quality/deviations/{id}", id), ergoQuality, updateForm(true, true)).andExpect(status().isOk());

		call(get("/api/quality/deviations/{id}", id), supplierXQuality, null).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- expired validity blocks approval (security review L1)

	@Test
	void aDeviationCannotBeApprovedOnceItsValidityHasLapsed() throws Exception {
		long orderId = createOrder(customerA, supplierX);
		long id = createAsErgoWithValidity(ergoManagement, orderId, false, LocalDate.now().plusDays(1));
		call(post("/api/quality/deviations/{id}/start-review", id), ergoQuality, null).andExpect(status().isOk());
		jdbc.update("UPDATE deviations SET validity_end_date = ? WHERE deviation_id = ?", LocalDate.now().minusDays(1),
				id);

		call(post("/api/quality/deviations/{id}/approve", id), ergoQuality, "{}")
			.andExpect(status().isUnprocessableContent());
	}

	// ---------------------------------------------------------------- helpers

	private long createAsErgo(Login ergo, long orderId, boolean customerApprovalRequired) throws Exception {
		return JsonPath
			.<Number>read(call(post("/api/quality/deviations"), ergo, deviationForm(orderId, customerApprovalRequired))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString(), "$.id")
			.longValue();
	}

	private long createAsErgoLinked(Login ergo, String linkedModule, long linkedRecordId, boolean customerApprovalRequired)
			throws Exception {
		String body = """
				{"linkedModule": "%s", "linkedRecordId": %d, "partNumber": "PN-DEV", "deviationDescription": "desc",
				 "customerApprovalRequiredFlag": %s, "validityQuantity": 100, "validityEndDate": "%s"}"""
			.formatted(linkedModule, linkedRecordId, customerApprovalRequired, LocalDate.now().plusDays(30));
		return JsonPath
			.<Number>read(call(post("/api/quality/deviations"), ergo, body).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString(), "$.id")
			.longValue();
	}

	private long createAsErgoWithValidity(Login ergo, long orderId, boolean customerApprovalRequired, LocalDate endDate)
			throws Exception {
		String body = """
				{"linkedModule": "OFP", "linkedRecordId": %d, "partNumber": "PN-DEV", "deviationDescription": "desc",
				 "customerApprovalRequiredFlag": %s, "validityQuantity": 100, "validityEndDate": "%s"}"""
			.formatted(orderId, customerApprovalRequired, endDate);
		return JsonPath
			.<Number>read(call(post("/api/quality/deviations"), ergo, body).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString(), "$.id")
			.longValue();
	}

	private long createAsCustomer(Login customer, long orderId) throws Exception {
		return createAsCustomer(customer, orderId, false);
	}

	private long createAsCustomer(Login customer, long orderId, boolean customerApprovalRequired) throws Exception {
		return JsonPath
			.<Number>read(call(post("/api/quality/deviations"), customer, deviationForm(orderId, customerApprovalRequired))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString(), "$.id")
			.longValue();
	}

	private static String deviationForm(long orderId, boolean customerApprovalRequired) {
		return """
				{"linkedModule": "OFP", "linkedRecordId": %d, "partNumber": "PN-DEV", "deviationDescription": "desc",
				 "customerApprovalRequiredFlag": %s, "validityQuantity": 100, "validityEndDate": "%s"}"""
			.formatted(orderId, customerApprovalRequired, LocalDate.now().plusDays(30));
	}

	private static String updateForm(boolean customerVisible, boolean supplierVisible) {
		return """
				{"partNumber": "PN-DEV", "deviationDescription": "desc updated", "validityQuantity": 100,
				 "validityEndDate": "%s", "customerVisible": %s, "supplierVisible": %s}"""
			.formatted(LocalDate.now().plusDays(30), customerVisible, supplierVisible);
	}

	/** A minimal inspection row linked to {@code (linkedModule, linkedRecordId)}, no MockMvc call needed (security
	 * review M1's negative test needs one linked to a *different* record than the deviation it's attached to). */
	private long createInspection(String linkedModule, long linkedRecordId, long customerCompanyId, long supplierCompanyId) {
		return jdbc.queryForObject("""
				INSERT INTO inspections (linked_module, linked_record_id, customer_company_id, supplier_company_id,
				                         part_number, created_by_user_id, created_by_company_id)
				VALUES (?, ?, ?, ?, 'PN-INSPECT', ?, ?) RETURNING inspection_id""", Long.class, linkedModule, linkedRecordId,
				customerCompanyId, supplierCompanyId, ergoManagement.userId(), ergoManagement.companyId());
	}

	/** A minimal OFP order, no MockMvc call needed (same helper as {@code NcrTests}). */
	private long createOrder(long customerCompanyId, long supplierCompanyId) {
		return jdbc.queryForObject("""
				INSERT INTO orders (order_number, customer_company_id, supplier_company_id, part_number,
				                    ordered_quantity, current_status)
				VALUES (?, ?, ?, 'PN-ORDER', 10, 'ORDER_CREATED') RETURNING order_id""", Long.class,
				"ND" + SEQ.incrementAndGet(), customerCompanyId, supplierCompanyId);
	}

	/** A minimal NPD project (same helper as {@code NcrTests}). */
	private long createNpdProject(long customerCompanyId, long supplierCompanyId) {
		long currencyId = users.currency("INR");
		long rfqId = jdbc.queryForObject("""
				INSERT INTO rfqs (rfq_number, source_type, customer_company_id, created_by_user_id, status, priority,
				                  part_number, currency_id)
				VALUES (?, 'CUSTOMER', ?, ?, 'CUSTOMER_APPROVED', 'NORMAL', 'PN-RFQ', ?) RETURNING rfq_id""",
				Long.class, "NX" + SEQ.incrementAndGet(), customerCompanyId, ergoManagement.userId(), currencyId);
		return jdbc.queryForObject("""
				INSERT INTO npd_projects (npd_number, source_rfq_id, customer_company_id, supplier_company_id,
				                          part_number, stage, created_by_user_id)
				VALUES (?, ?, ?, ?, 'PN-NPD', 'PLANNING', ?) RETURNING npd_project_id""", Long.class,
				"NY" + SEQ.incrementAndGet(), rfqId, customerCompanyId, supplierCompanyId, ergoManagement.userId());
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

}
