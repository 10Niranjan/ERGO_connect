package com.ergo.connect.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 5b: NCRs and CAPA actions (SRS §10.8, §13.6.3/.5, §14.7) - record scope with 2 customers and 2 suppliers, the
 * linear workflow's allowed/forbidden/missing-reason transitions, the two SRS §17.6 hard gates (root cause before
 * CAPA, evidence before closure), two-way anonymity, self-approval and audit - same coverage depth as
 * {@link QualityTests}.
 */
@IntegrationTest
class NcrTests {

	/** Unique order/NPD numbers for {@link #createOrder}/{@link #createNpdProject} (each column is UNIQUE). */
	private static final AtomicInteger SEQ = new AtomicInteger(800_000);

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
	}

	// ---------------------------------------------------------------- create / role check

	@Test
	void rolesThatActOnQualityMayCreateAndTheViewOnlyOnesMayNot() throws Exception {
		// role sheet: ERGO Management / Quality ("O"), Supplier Quality / KAM ("A") create
		call(post("/api/quality/ncrs"), ergoManagement, ncrForm(1)).andExpect(status().isCreated());
		call(post("/api/quality/ncrs"), ergoQuality, ncrForm(2)).andExpect(status().isCreated());
		call(post("/api/quality/ncrs"), supplierXQuality, ncrForm(3)).andExpect(status().isCreated());
		Login supplierKam = users.login(supplierX, "SUPPLIER_KAM");
		call(post("/api/quality/ncrs"), supplierKam, ncrForm(5)).andExpect(status().isCreated());

		// ERGO OFP ("A") may only raise NCRs within its own area: OFP-linked ones, never a free-standing one
		call(post("/api/quality/ncrs"), ergoOfp, ncrForm(6)).andExpect(status().isUnprocessableContent());

		for (Login denied : List.of(ergoAdmin, ergoKam, ergoNpd, ergoRfq, customerAQuality)) {
			call(post("/api/quality/ncrs"), denied, ncrForm(4)).andExpect(status().isForbidden());
		}
	}

	@Test
	void createIsAuditedAndGetsAGeneratedNumber() throws Exception {
		long id = createAsSupplier(supplierXQuality, 5);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_logs WHERE entity_name = 'Ncr' AND record_id = ? AND event_type = 'CREATE'",
				Integer.class, id)).isGreaterThanOrEqualTo(1);
		String number = jdbc.queryForObject("SELECT ncr_number FROM ncrs WHERE ncr_id = ?", String.class, id);
		assertThat(number).startsWith("NCR-");
	}

	// ---------------------------------------------------------------- record scope: 2 customers, 2 suppliers

	@Test
	void supplierCannotSeeAnotherSuppliersNcr() throws Exception {
		long id = createAsSupplier(supplierXQuality, 10);

		call(get("/api/quality/ncrs/{id}", id), supplierXQuality, null).andExpect(status().isOk());
		call(get("/api/quality/ncrs?size=100"), supplierXQuality, null).andExpect(jsonPath("$.items[*].id", hasItem((int) id)));

		call(get("/api/quality/ncrs/{id}", id), supplierYQuality, null).andExpect(status().isNotFound());
		call(get("/api/quality/ncrs?size=100"), supplierYQuality, null).andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.items[*].id", not(hasItem((int) id))));

		call(put("/api/quality/ncrs/{id}", id), supplierYQuality, updateForm(null, null, null, null))
			.andExpect(status().isNotFound());
		call(post("/api/quality/ncrs/{id}/move-to-containment", id), supplierYQuality,
				updateForm(null, null, null, null)).andExpect(status().isNotFound());
	}

	@Test
	void customerCannotSeeAnotherCustomersNcr() throws Exception {
		long forA = createErgoNcr(20, customerA, null);
		long forB = createErgoNcr(21, customerB, null);
		makeCustomerVisible(forA); // H2: an ERGO editor must review and flip this on before the customer sees it

		call(get("/api/quality/ncrs/{id}", forA), customerAQuality, null).andExpect(status().isOk());
		call(get("/api/quality/ncrs/{id}", forB), customerAQuality, null).andExpect(status().isNotFound());

		String list = call(get("/api/quality/ncrs?size=100"), customerAQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", hasItem((int) forA)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) forB))))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(list).isNotBlank();
	}

	@Test
	void twoWayAnonymityNeitherPartyLearnsTheOther() throws Exception {
		long id = createErgoNcr(30, customerA, supplierX);
		makeCustomerVisible(id); // H2: otherwise the customer cannot see this NCR at all

		String supplierView = call(get("/api/quality/ncrs/{id}", id), supplierXQuality, null)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(supplierView).doesNotContain("customerCompanyId").doesNotContain("\"customer\"");

		String customerView = call(get("/api/quality/ncrs/{id}", id), customerAQuality, null)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(customerView).doesNotContain("supplierCompanyId").doesNotContain("\"supplier\"");

		call(get("/api/quality/ncrs/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.customerCompanyId").value((int) customerA))
			.andExpect(jsonPath("$.supplierCompanyId").value((int) supplierX));
	}

	@Test
	void ergoKamSeesOnlyItsAssignedCustomersNcrsAndErgoNpdOfpSeeOnlyTheirOwnLinkedModule() throws Exception {
		long forA = createErgoNcr(40, customerA, null);
		long forB = createErgoNcr(41, customerB, null);
		long npdOne = createLinkedNcr(ergoQuality, "NPD", createNpdProject(customerA, supplierX));
		long ofpOne = createLinkedNcr(ergoQuality, "OFP", createOrder(customerA, supplierX));

		call(get("/api/quality/ncrs?size=100"), ergoKam, null).andExpect(jsonPath("$.items[*].id", hasItem((int) forA)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) forB))));
		call(get("/api/quality/ncrs/{id}", forB), ergoKam, null).andExpect(status().isNotFound());

		call(get("/api/quality/ncrs?size=100"), ergoNpd, null).andExpect(jsonPath("$.items[*].id", hasItem((int) npdOne)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) ofpOne))));
		call(get("/api/quality/ncrs?size=100"), ergoOfp, null).andExpect(jsonPath("$.items[*].id", hasItem((int) ofpOne)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) npdOne))));

		// ERGO RFQ and Admin view every NCR (role sheet "V")
		for (Login viewer : List.of(ergoRfq, ergoAdmin)) {
			call(get("/api/quality/ncrs?size=100"), viewer, null).andExpect(jsonPath("$.items[*].id", hasItem((int) forB)));
			call(get("/api/quality/ncrs/{id}", forA), viewer, null).andExpect(status().isOk());
		}
	}

	/** Security review M5 precedent: an NCR linked to a real OFP order always derives its companies from that order,
	 * never from the client. */
	@Test
	void ofpLinkedNcrDerivesCompaniesFromTheOrderNeverFromTheClient() throws Exception {
		long orderId = createOrder(customerA, supplierX);

		String created = call(post("/api/quality/ncrs"), ergoQuality, """
				{"linkedModule": "OFP", "linkedRecordId": %d, "issueCategory": "Dimensional",
				 "issueDescription": "desc", "targetClosureDate": "%s", "customerCompanyId": %d, "supplierCompanyId": %d}"""
			.formatted(orderId, LocalDate.now().plusDays(30), customerB, supplierY))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long id = ((Number) JsonPath.read(created, "$.id")).longValue();
		call(get("/api/quality/ncrs/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.customerCompanyId").value((int) customerA))
			.andExpect(jsonPath("$.supplierCompanyId").value((int) supplierX));

		call(post("/api/quality/ncrs"), supplierYQuality, """
				{"linkedModule": "OFP", "linkedRecordId": %d, "issueCategory": "Dimensional", "issueDescription": "desc",
				 "targetClosureDate": "%s"}""".formatted(orderId, LocalDate.now().plusDays(30)))
			.andExpect(status().isUnprocessableContent());
	}

	// ---------------------------------------------------------------- Customer Quality view-only

	@Test
	void customerQualityHasNoWriteAccessAnywhere() throws Exception {
		long id = createErgoNcr(50, customerA, supplierX);
		makeCustomerVisible(id); // H2: otherwise the customer cannot see this NCR at all
		call(post("/api/quality/ncrs"), customerAQuality, ncrForm(51)).andExpect(status().isForbidden());
		call(put("/api/quality/ncrs/{id}", id), customerAQuality, updateForm(null, null, null, null))
			.andExpect(status().isForbidden());
		call(post("/api/quality/ncrs/{id}/move-to-containment", id), customerAQuality,
				updateForm(null, null, null, null)).andExpect(status().isForbidden());
		call(post("/api/quality/ncrs/{id}/close", id), customerAQuality, "{}").andExpect(status().isForbidden());
		call(get("/api/quality/ncrs/{id}", id), customerAQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", empty()));
	}

	// ---------------------------------------------------------------- supplier linked-to-company gate (L4 fix)

	/** Security review L4: SRS §10.8 scopes Supplier Quality's edit rights to NCRs "linked to that supplier", not
	 * "created by that supplier" - an ERGO-raised NCR tagged to Supplier X (the common real-world case: ERGO finds a
	 * defect, raises the NCR, the supplier is expected to respond) must be editable by Supplier X's own Supplier
	 * Quality role, not just view-only. */
	@Test
	void supplierMayRespondToAnErgoCreatedNcrTaggedToItsOwnCompany() throws Exception {
		long id = createErgoNcr(60, null, supplierX);

		call(get("/api/quality/ncrs/{id}", id), supplierXQuality, null).andExpect(status().isOk());

		call(put("/api/quality/ncrs/{id}", id), supplierXQuality, updateForm("contained", null, null, null))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.containmentAction").value("contained"));
		call(post("/api/quality/ncrs/{id}/move-to-containment", id), supplierXQuality,
				updateForm("contained", null, null, null)).andExpect(status().isOk())
			.andExpect(jsonPath("$.currentStatus").value("CONTAINMENT_IN_PROGRESS"));

		call(get("/api/quality/ncrs/{id}", id), supplierXQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions").isNotEmpty())
			// company reassignment/effectivenessStatus/ownerUserId stay ERGO-only regardless (applyUpdate's own gate)
			.andExpect(jsonPath("$.effectivenessStatus").doesNotExist())
			.andExpect(jsonPath("$.ownerUserId").doesNotExist());

		// a different supplier is still out of scope entirely
		call(get("/api/quality/ncrs/{id}", id), supplierYQuality, null).andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- full workflow, hard gates

	@Test
	void fullHappyPathOpenToClosedWithCapaActionsAndEvidence() throws Exception {
		long id = createAsSupplier(supplierXQuality, 70);

		call(post("/api/quality/ncrs/{id}/move-to-containment", id), supplierXQuality,
				updateForm("Segregated stock", null, null, null))
			.andExpect(jsonPath("$.currentStatus").value("CONTAINMENT_IN_PROGRESS"));

		call(post("/api/quality/ncrs/{id}/move-to-root-cause", id), supplierXQuality,
				updateForm("Segregated stock", null, null, null))
			.andExpect(jsonPath("$.currentStatus").value("ROOT_CAUSE_IN_PROGRESS"));

		// hard gate 1: cannot move into CAPA without a root cause
		call(post("/api/quality/ncrs/{id}/move-to-capa", id), supplierXQuality,
				updateForm("Segregated stock", null, null, null)).andExpect(status().isUnprocessableContent());

		call(post("/api/quality/ncrs/{id}/move-to-capa", id), supplierXQuality,
				updateForm("Segregated stock", "Worn tooling", null, null))
			.andExpect(jsonPath("$.currentStatus").value("CAPA_IN_PROGRESS"));

		String withCapa = call(post("/api/quality/ncrs/{id}/capa-actions", id), supplierXQuality,
				capaActionForm("CORRECTIVE", "Replace tooling"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long capaActionId = ((Number) JsonPath.read(withCapa, "$.capaActions[0].id")).longValue();

		// M1: evidence must be attached and the CAPA action closed before requesting closure - CAPA actions/evidence
		// freeze once the NCR reaches PENDING_CLOSURE_APPROVAL.
		mvc.perform(multipart("/api/quality/ncrs/{id}/capa-actions/{capaActionId}/evidence", id, capaActionId)
			.file(new MockMultipartFile("file", "cert.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, supplierXQuality.bearer())).andExpect(status().isOk());

		call(post("/api/quality/ncrs/{id}/capa-actions/{capaActionId}/close", id, capaActionId), supplierXQuality, null)
			.andExpect(jsonPath("$.capaActions[0].status").value("CLOSED"));

		// hard gate 1 also applies to closing a CAPA action - but root cause is already set here, so this succeeds;
		// verified as its own gate in a dedicated test below.
		call(post("/api/quality/ncrs/{id}/submit-for-closure", id), supplierXQuality,
				updateForm("Segregated stock", "Worn tooling", null, null))
			.andExpect(status().isUnprocessableContent()); // preventiveAction still missing

		call(post("/api/quality/ncrs/{id}/submit-for-closure", id), supplierXQuality,
				updateForm("Segregated stock", "Worn tooling", "Replaced tooling", "Added PM schedule"))
			.andExpect(jsonPath("$.currentStatus").value("PENDING_CLOSURE_APPROVAL"));

		// M1: CAPA actions can no longer be touched now that closure is pending
		call(post("/api/quality/ncrs/{id}/capa-actions", id), supplierXQuality, capaActionForm("CORRECTIVE", "late"))
			.andExpect(status().isConflict());

		call(post("/api/quality/ncrs/{id}/close", id), ergoQuality, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CLOSED"));
	}

	/** Hard gate 2 (SRS §17.6): cannot close without evidence on at least one CAPA action. Since M1 freezes CAPA/
	 * evidence once the NCR reaches PENDING_CLOSURE_APPROVAL, evidence has to have existed before submission - so
	 * this NCR, having never had any attached, is stuck unable to close (a send-back plus a fresh cycle would fix
	 * it; not what this test is about). */
	@Test
	void closeIsBlockedWithoutCapaEvidenceAttachedBeforeSubmission() throws Exception {
		long id = createAsSupplier(supplierXQuality, 71);
		call(post("/api/quality/ncrs/{id}/move-to-containment", id), supplierXQuality, updateForm("c", null, null, null))
			.andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/move-to-root-cause", id), supplierXQuality, updateForm("c", null, null, null))
			.andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/move-to-capa", id), supplierXQuality, updateForm("c", "r", null, null))
			.andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/capa-actions", id), supplierXQuality, capaActionForm("CORRECTIVE", "fix"))
			.andExpect(status().isCreated());
		call(post("/api/quality/ncrs/{id}/submit-for-closure", id), supplierXQuality, updateForm("c", "r", "co", "p"))
			.andExpect(jsonPath("$.currentStatus").value("PENDING_CLOSURE_APPROVAL"));

		call(post("/api/quality/ncrs/{id}/close", id), ergoQuality, "{}").andExpect(status().isUnprocessableContent());
	}

	/** Security review M1: content and CAPA actions are frozen while an approver is mid-review. */
	@Test
	void editAndCapaCreateAreBlockedWhilePendingClosureApproval() throws Exception {
		long id = pendingClosureNcr(72);

		call(put("/api/quality/ncrs/{id}", id), supplierXQuality, updateForm("c", "r", "co", "p"))
			.andExpect(status().isConflict());
		call(post("/api/quality/ncrs/{id}/capa-actions", id), supplierXQuality, capaActionForm("CORRECTIVE", "x"))
			.andExpect(status().isConflict());
	}

	/** Hard gate (SRS §17.6): a CAPA action cannot be closed without its parent NCR's root cause on record. */
	@Test
	void capaActionCannotBeClosedWithoutRootCauseOnTheParentNcr() throws Exception {
		long id = createErgoNcr(80, null, supplierX);
		// Full-replace convention: resend the existing supplier so this call is not itself read as a reassignment.
		call(post("/api/quality/ncrs/{id}/move-to-containment", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "part out of tolerance", "targetClosureDate": "%s",
				 "containmentAction": "contained", "supplierCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), supplierX)).andExpect(status().isOk());

		String withCapa = call(post("/api/quality/ncrs/{id}/capa-actions", id), ergoQuality,
				capaActionForm("CORRECTIVE", "fix it"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long capaActionId = ((Number) JsonPath.read(withCapa, "$.capaActions[0].id")).longValue();

		// root cause never set (NCR is still only in CONTAINMENT_IN_PROGRESS) - closing the CAPA action is refused
		call(post("/api/quality/ncrs/{id}/capa-actions/{capaActionId}/close", id, capaActionId), ergoQuality, null)
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void moveToRootCauseRequiresContainmentAction() throws Exception {
		long id = createAsSupplier(supplierXQuality, 90);
		call(post("/api/quality/ncrs/{id}/move-to-root-cause", id), supplierXQuality,
				updateForm(null, null, null, null)).andExpect(status().isConflict()); // still OPEN, wrong state

		call(post("/api/quality/ncrs/{id}/move-to-containment", id), supplierXQuality,
				updateForm(null, null, null, null)).andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/move-to-root-cause", id), supplierXQuality,
				updateForm(null, null, null, null)).andExpect(status().isUnprocessableContent());
	}

	// ---------------------------------------------------------------- send-back / reopen: reason required

	@Test
	void sendBackAndReopenRequireAReasonAndOnlyApproversMayDecide() throws Exception {
		long id = pendingClosureNcr(100);

		call(post("/api/quality/ncrs/{id}/send-back", id), ergoQuality, "{}")
			.andExpect(status().isUnprocessableContent());
		call(post("/api/quality/ncrs/{id}/send-back", id), supplierXQuality, "{\"reason\": \"not enough evidence\"}")
			.andExpect(status().isForbidden());
		call(post("/api/quality/ncrs/{id}/send-back", id), ergoQuality, "{\"reason\": \"not enough evidence\"}")
			.andExpect(jsonPath("$.currentStatus").value("CAPA_IN_PROGRESS"));
		assertThat(jdbc.queryForObject("""
				SELECT action_reason FROM audit_logs WHERE entity_name = 'Ncr' AND record_id = ?
				AND field_name = 'currentStatus' AND new_value = 'CAPA_IN_PROGRESS'
				ORDER BY audit_log_id DESC LIMIT 1""", String.class, id))
			.isEqualTo("not enough evidence");
	}

	@Test
	void reopenIsTheOnlyPathFromClosedAndRequiresAReason() throws Exception {
		long id = closedNcr(110);

		call(post("/api/quality/ncrs/{id}/submit-for-closure", id), supplierXQuality,
				updateForm("c", "r", "co", "p")).andExpect(status().isConflict());

		call(post("/api/quality/ncrs/{id}/reopen", id), ergoQuality, "{}").andExpect(status().isUnprocessableContent());
		call(post("/api/quality/ncrs/{id}/reopen", id), supplierXQuality, "{\"reason\": \"recheck\"}")
			.andExpect(status().isForbidden());
		call(post("/api/quality/ncrs/{id}/reopen", id), ergoQuality, "{\"reason\": \"recheck\"}")
			.andExpect(jsonPath("$.currentStatus").value("OPEN"));
		assertThat(jdbc.queryForObject("""
				SELECT action_reason FROM audit_logs WHERE entity_name = 'Ncr' AND record_id = ?
				AND field_name = 'currentStatus' AND new_value = 'OPEN' ORDER BY audit_log_id DESC LIMIT 1""",
				String.class, id)).isEqualTo("recheck");
	}

	// ---------------------------------------------------------------- self-approval (owner as the brief's own proxy)

	@Test
	void ncrOwnerCannotApproveItsOwnClosure() throws Exception {
		long id = createAsSupplier(supplierXQuality, 120);
		long ownerUserId = ergoQuality.userId();
		// Full-replace convention (same as QualityTests): ERGO must resend the NCR's existing supplierCompanyId or
		// the edit is read as a reassignment (M6 precedent) and clears it.
		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "part out of tolerance", "targetClosureDate": "%s",
				 "ownerUserId": %d, "supplierCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), ownerUserId, supplierX)).andExpect(status().isOk());

		call(post("/api/quality/ncrs/{id}/move-to-containment", id), supplierXQuality,
				updateForm("c", null, null, null)).andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/move-to-root-cause", id), supplierXQuality,
				updateForm("c", null, null, null)).andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/move-to-capa", id), supplierXQuality,
				updateForm("c", "r", null, null)).andExpect(status().isOk());

		// M1: CAPA action + evidence must be attached before submitting for closure - they freeze afterwards.
		String withCapa = call(post("/api/quality/ncrs/{id}/capa-actions", id), ergoQuality,
				capaActionForm("CORRECTIVE", "fix")).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long capaActionId = ((Number) JsonPath.read(withCapa, "$.capaActions[0].id")).longValue();
		mvc.perform(multipart("/api/quality/ncrs/{id}/capa-actions/{capaActionId}/evidence", id, capaActionId)
			.file(new MockMultipartFile("file", "cert.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, ergoQuality.bearer())).andExpect(status().isOk());

		call(post("/api/quality/ncrs/{id}/submit-for-closure", id), supplierXQuality,
				updateForm("c", "r", "co", "p")).andExpect(status().isOk());

		// ergoQuality is the NCR's own owner: blocked from closing it
		call(post("/api/quality/ncrs/{id}/close", id), ergoQuality, "{}").andExpect(status().isConflict());
		call(get("/api/quality/ncrs/{id}", id), ergoQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", not(hasItem("CLOSE"))));

		// a different approver still may
		call(post("/api/quality/ncrs/{id}/close", id), ergoManagement, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CLOSED"));
	}

	/** Security review H3: ownerUserId alone was trivially bypassable - it is null by default, so an ERGO Quality
	 * user who creates an NCR and drives it all the way to PENDING_CLOSURE_APPROVAL themselves could still close it
	 * uncontested. closureRequestedByUserId (set atomically by submitForClosure) and createdByUserId close that gap
	 * even when ownerUserId is never set. */
	@Test
	void ownerlessNcrCannotBeClosedByThePersonWhoCreatedAndDroveItThemselves() throws Exception {
		long id = createLinkedNcr(ergoQuality, "INCOMING", 121);
		// First reassignment while OPEN (M2): supplierCompanyId moves from null to supplierX in the same call that
		// also tries to set containmentAction - reassignment wins and clears it (M6 precedent), so this call's own
		// containmentAction does not stick; the next call sets it again with supplierCompanyId now unchanged.
		call(post("/api/quality/ncrs/{id}/move-to-containment", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "containmentAction": "c", "supplierCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), supplierX)).andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/move-to-root-cause", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "containmentAction": "c", "rootCause": "r", "supplierCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), supplierX)).andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/move-to-capa", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "containmentAction": "c", "rootCause": "r", "supplierCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), supplierX)).andExpect(status().isOk());

		String withCapa = call(post("/api/quality/ncrs/{id}/capa-actions", id), ergoQuality,
				capaActionForm("CORRECTIVE", "fix")).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long capaActionId = ((Number) JsonPath.read(withCapa, "$.capaActions[0].id")).longValue();
		mvc.perform(multipart("/api/quality/ncrs/{id}/capa-actions/{capaActionId}/evidence", id, capaActionId)
			.file(new MockMultipartFile("file", "cert.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, ergoQuality.bearer())).andExpect(status().isOk());

		call(post("/api/quality/ncrs/{id}/submit-for-closure", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "containmentAction": "c", "rootCause": "r", "correctiveAction": "co", "preventiveAction": "p",
				 "supplierCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), supplierX))
			.andExpect(jsonPath("$.currentStatus").value("PENDING_CLOSURE_APPROVAL"));

		assertThat(jdbc.queryForObject("SELECT owner_user_id FROM ncrs WHERE ncr_id = ?", Long.class, id)).isNull();

		// ergoQuality created this NCR and is also the one who requested closure - blocked either way
		call(post("/api/quality/ncrs/{id}/close", id), ergoQuality, "{}").andExpect(status().isConflict());

		// a different approver still may
		call(post("/api/quality/ncrs/{id}/close", id), ergoManagement, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CLOSED"));
	}

	// ---------------------------------------------------------------- reassignment clears content (M6 precedent)

	@Test
	void reassigningTheSupplierClearsAlreadyAuthoredProgressAndCapaActions() throws Exception {
		long id = createErgoNcr(130, null, supplierX);
		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "containmentAction": "contained", "rootCause": "worn tool", "supplierCompanyId": %d,
				 "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), supplierX)).andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/capa-actions", id), ergoQuality, capaActionForm("CORRECTIVE", "fix"))
			.andExpect(status().isCreated());

		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "supplierCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), supplierY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rootCause").doesNotExist())
			.andExpect(jsonPath("$.containmentAction").doesNotExist())
			.andExpect(jsonPath("$.capaActions", empty()))
			.andExpect(jsonPath("$.currentStatus").value("OPEN"));

		assertThat(jdbc.queryForObject("SELECT root_cause FROM ncrs WHERE ncr_id = ?", String.class, id)).isNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM capa_actions WHERE ncr_id = ?", Integer.class, id))
			.isZero();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_logs WHERE entity_name = 'Ncr' AND record_id = ?
				AND event_type = 'REASSIGNED_CONTENT_CLEARED'""", Integer.class, id)).isEqualTo(1);
	}

	@Test
	void auditTrailIsErgoOnly() throws Exception {
		long id = createErgoNcr(141, customerA, supplierX);
		makeCustomerVisible(id);

		call(get("/api/quality/ncrs/{id}/history", id), customerAQuality, null).andExpect(status().isForbidden());
		call(get("/api/quality/ncrs/{id}/history", id), supplierXQuality, null).andExpect(status().isForbidden());
		call(get("/api/quality/ncrs/{id}/history", id), customerBQuality, null).andExpect(status().isNotFound());
		// ERGO_KAM: excluded even for its own assigned account - reassignment while OPEN could carry a previous,
		// unassigned customer's data past the KAM's scope (security review, ARCHITECTURE.md §8 2026-10-05)
		call(get("/api/quality/ncrs/{id}/history", id), ergoKam, null).andExpect(status().isForbidden());
		call(get("/api/quality/ncrs/{id}/history", id), ergoQuality, null)
			.andExpect(jsonPath("$[*].event", hasItem("CREATE")));
	}

	// ---------------------------------------------------------------- internal-vs-shared field split

	@Test
	void effectivenessStatusAndOwnerAreErgoInternalNeverExposedExternally() throws Exception {
		long id = createErgoNcr(140, customerA, supplierX);
		// Full-replace convention: resend the existing companies so this edit is not itself read as a reassignment.
		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "effectivenessStatus": "Effective", "ownerUserId": %d, "customerCompanyId": %d, "supplierCompanyId": %d,
				 "customerVisible": true}"""
			.formatted(LocalDate.now().plusDays(30), ergoQuality.userId(), customerA, supplierX)).andExpect(status().isOk())
			.andExpect(jsonPath("$.effectivenessStatus").value("Effective"))
			.andExpect(jsonPath("$.owner").isNotEmpty());

		String supplierView = call(get("/api/quality/ncrs/{id}", id), supplierXQuality, null)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(supplierView).doesNotContain("effectivenessStatus").doesNotContain("ownerUserId")
			.doesNotContain("\"owner\"");

		String customerView = call(get("/api/quality/ncrs/{id}", id), customerAQuality, null)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(customerView).doesNotContain("effectivenessStatus").doesNotContain("ownerUserId")
			.doesNotContain("\"owner\"");
	}

	/** The shared content fields (the point of an NCR raised against/by a party) do reach both external views,
	 * unlike {@code Inspection.remarks}. */
	@Test
	void containmentAndRootCauseAreSharedWithTheLinkedCompany() throws Exception {
		long id = createErgoNcr(150, customerA, supplierX);
		// Full-replace convention: resend the existing companies so this edit is not itself read as a reassignment.
		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "containmentAction": "stock segregated", "rootCause": "worn tooling", "customerCompanyId": %d,
				 "supplierCompanyId": %d, "customerVisible": true}"""
			.formatted(LocalDate.now().plusDays(30), customerA, supplierX)).andExpect(status().isOk());

		call(get("/api/quality/ncrs/{id}", id), supplierXQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.containmentAction").value("stock segregated"))
			.andExpect(jsonPath("$.rootCause").value("worn tooling"));
		call(get("/api/quality/ncrs/{id}", id), customerAQuality, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.containmentAction").value("stock segregated"))
			.andExpect(jsonPath("$.rootCause").value("worn tooling"));
	}

	// ---------------------------------------------------------------- customer_visible gate (H2 fix)

	@Test
	void supplierCreatedNcrTaggedToCustomerIsHiddenUntilErgoMakesItCustomerVisible() throws Exception {
		long id = createAsSupplier(supplierXQuality, 180);
		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), customerA, supplierX)).andExpect(status().isOk());

		call(get("/api/quality/ncrs/{id}", id), customerAQuality, null).andExpect(status().isNotFound());
		call(get("/api/quality/ncrs?size=100"), customerAQuality, null).andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.items[*].id", not(hasItem((int) id))));

		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "customerVisible": true}"""
			.formatted(LocalDate.now().plusDays(30), customerA, supplierX)).andExpect(status().isOk());

		call(get("/api/quality/ncrs/{id}", id), customerAQuality, null).andExpect(status().isOk());
		call(get("/api/quality/ncrs?size=100"), customerAQuality, null).andExpect(jsonPath("$.items[*].id", hasItem((int) id)));
	}

	@Test
	void theNcrListIsPagedFilteredAndSearchedInTheQueryWithoutBreakingTwoWayAnonymity() throws Exception {
		long a = createErgoNcr(310, customerA, supplierX);
		call(put("/api/quality/ncrs/{id}", a), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "customerVisible": true}"""
			.formatted(LocalDate.now().plusDays(30), customerA, supplierX)).andExpect(status().isOk());
		long b = createErgoNcr(311, customerB, supplierY);
		String numberA = jdbc.queryForObject("SELECT ncr_number FROM ncrs WHERE ncr_id = ?", String.class, a);
		String numberB = jdbc.queryForObject("SELECT ncr_number FROM ncrs WHERE ncr_id = ?", String.class, b);
		String customerAName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?", String.class, customerA);
		String customerBName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?", String.class, customerB);
		String supplierXName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?", String.class, supplierX);

		// paging: newest first, one row a page; sizes and pages are clamped
		call(get("/api/quality/ncrs?size=1"), ergoQuality, null).andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value((int) b)).andExpect(jsonPath("$.size").value(1))
			.andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
		call(get("/api/quality/ncrs?size=1&page=1"), ergoQuality, null).andExpect(jsonPath("$.items[0].id").value((int) a));
		call(get("/api/quality/ncrs?size=100000&page=-3"), ergoQuality, null).andExpect(jsonPath("$.size").value(100)).andExpect(jsonPath("$.page").value(0));

		// each side sees exactly its own, and only what its row shows is searchable
		call(get("/api/quality/ncrs"), customerAQuality, null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value((int) a));
		call(get("/api/quality/ncrs"), supplierXQuality, null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value((int) a));
		call(get("/api/quality/ncrs?q=" + numberA.toLowerCase()), customerAQuality, null).andExpect(jsonPath("$.total").value(1));
		call(get("/api/quality/ncrs?q=" + numberB), customerAQuality, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/ncrs").param("q", supplierXName), customerAQuality, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/ncrs").param("q", customerAName), supplierXQuality, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/ncrs?q=dimensional"), supplierXQuality, null).andExpect(jsonPath("$.total").value(1));

		// ERGO also searches the company names; the account-scoped KAM only within its own accounts
		call(get("/api/quality/ncrs").param("q", customerBName), ergoQuality, null).andExpect(jsonPath("$.items[*].id", hasItem((int) b)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) a))));
		call(get("/api/quality/ncrs").param("q", customerBName), ergoKam, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/ncrs").param("q", "%"), ergoQuality, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/ncrs").param("q", "\u0000" + numberA), ergoQuality, null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));

		// filters narrow inside the same query; an unknown value is refused, not ignored
		call(get("/api/quality/ncrs?status=OPEN&q=" + numberA), ergoQuality, null).andExpect(jsonPath("$.total").value(1));
		call(get("/api/quality/ncrs?status=CLOSED&q=" + numberA), ergoQuality, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/ncrs?linkedModule=INCOMING&q=" + numberA), ergoQuality, null).andExpect(jsonPath("$.total").value(1));
		call(get("/api/quality/ncrs?linkedModule=NPD&q=" + numberA), ergoQuality, null).andExpect(jsonPath("$.total").value(0));
		call(get("/api/quality/ncrs?status=BOGUS"), ergoQuality, null).andExpect(status().isUnprocessableEntity());
		call(get("/api/quality/ncrs?linkedModule=BOGUS"), ergoQuality, null).andExpect(status().isUnprocessableEntity());
	}

	@Test
	void ergoCreatedNcrIsAlsoHiddenFromTheCustomerByDefault() throws Exception {
		long id = createErgoNcr(190, customerA, supplierX);
		call(get("/api/quality/ncrs/{id}", id), customerAQuality, null).andExpect(status().isNotFound());
		call(get("/api/quality/ncrs?size=100"), customerAQuality, null).andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.items[*].id", not(hasItem((int) id))));
	}

	// ---------------------------------------------------------------- CAPA evidence: supplier-only, never customer (H1 fix)

	@Test
	void capaEvidenceFileDetailsNeverReachTheCustomerAndDownloadIsErgoAndSupplierOnly() throws Exception {
		long id = createErgoNcr(200, customerA, supplierX);
		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "containmentAction": "c", "rootCause": "r", "customerCompanyId": %d, "supplierCompanyId": %d,
				 "customerVisible": true}"""
			.formatted(LocalDate.now().plusDays(30), customerA, supplierX)).andExpect(status().isOk());
		String withCapa = call(post("/api/quality/ncrs/{id}/capa-actions", id), ergoQuality,
				capaActionForm("CORRECTIVE", "fix")).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long capaActionId = ((Number) JsonPath.read(withCapa, "$.capaActions[0].id")).longValue();
		mvc.perform(multipart("/api/quality/ncrs/{id}/capa-actions/{capaActionId}/evidence", id, capaActionId)
			.file(new MockMultipartFile("file", "cert.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, ergoQuality.bearer())).andExpect(status().isOk());

		String supplierView = call(get("/api/quality/ncrs/{id}", id), supplierXQuality, null)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(supplierView).contains("evidenceFileName").contains("evidenceDocumentId");

		String customerView = call(get("/api/quality/ncrs/{id}", id), customerAQuality, null)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(customerView).doesNotContain("evidenceFileName").doesNotContain("evidenceDocumentId")
			.contains("evidenceAttached");

		call(get("/api/quality/ncrs/{id}/capa-actions/{capaActionId}/evidence", id, capaActionId), ergoQuality, null)
			.andExpect(status().isOk());
		call(get("/api/quality/ncrs/{id}/capa-actions/{capaActionId}/evidence", id, capaActionId), supplierXQuality, null)
			.andExpect(status().isOk());
		call(get("/api/quality/ncrs/{id}/capa-actions/{capaActionId}/evidence", id, capaActionId), customerAQuality, null)
			.andExpect(status().isNotFound());
		// a different supplier is out of scope of the NCR entirely, confirmed still 404 after the DTO split
		call(get("/api/quality/ncrs/{id}/capa-actions/{capaActionId}/evidence", id, capaActionId), supplierYQuality, null)
			.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- company reassignment: OPEN-only, omission-safe (M2 fix)

	@Test
	void companyReassignmentRequiresOpenStatusAndOmittedFieldsAreUnchanged() throws Exception {
		long id = createErgoNcr(160, customerA, supplierX);

		// M2(a): omitting supplierCompanyId in a PUT must not wipe it
		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), customerA))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.supplierCompanyId").value((int) supplierX));

		// M2(b): move it out of OPEN, then an actual reassignment must be refused
		call(post("/api/quality/ncrs/{id}/move-to-containment", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "containmentAction": "contained", "customerCompanyId": %d, "supplierCompanyId": %d,
				 "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), customerA, supplierX)).andExpect(status().isOk());

		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), customerA, supplierY)).andExpect(status().isConflict());

		// resending the unchanged existing companies is not a reassignment, and still succeeds outside OPEN
		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), customerA, supplierX)).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- owner id validation (M3 fix)

	@Test
	void invalidNcrAndCapaOwnerIdsAreRejectedNotLeftToFail500() throws Exception {
		long id = createErgoNcr(170, customerA, supplierX);

		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "ownerUserId": 999999, "customerCompanyId": %d, "supplierCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), customerA, supplierX))
			.andExpect(status().isUnprocessableContent());

		// a real user, but not an ERGO one (a supplier's own user) must also be rejected as the NCR owner
		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "ownerUserId": %d, "customerCompanyId": %d, "supplierCompanyId": %d, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), supplierXQuality.userId(), customerA, supplierX))
			.andExpect(status().isUnprocessableContent());

		call(post("/api/quality/ncrs/{id}/capa-actions", id), ergoQuality, """
				{"actionType": "CORRECTIVE", "actionDescription": "fix", "targetDate": "%s", "ownerUserId": 999999}"""
			.formatted(LocalDate.now().plusDays(10)))
			.andExpect(status().isUnprocessableContent());

		// a customer's user is neither ERGO nor the NCR's supplier company - rejected as a CAPA action owner too
		call(post("/api/quality/ncrs/{id}/capa-actions", id), ergoQuality, """
				{"actionType": "CORRECTIVE", "actionDescription": "fix", "targetDate": "%s", "ownerUserId": %d}"""
			.formatted(LocalDate.now().plusDays(10), customerAQuality.userId()))
			.andExpect(status().isUnprocessableContent());

		// a user of the NCR's own supplier company is a valid CAPA owner
		call(post("/api/quality/ncrs/{id}/capa-actions", id), ergoQuality, """
				{"actionType": "CORRECTIVE", "actionDescription": "fix", "targetDate": "%s", "ownerUserId": %d}"""
			.formatted(LocalDate.now().plusDays(10), supplierXQuality.userId()))
			.andExpect(status().isCreated());
	}

	// ---------------------------------------------------------------- helpers

	private long createAsSupplier(Login supplier, long linkedRecordId) throws Exception {
		return JsonPath
			.<Number>read(call(post("/api/quality/ncrs"), supplier, ncrForm(linkedRecordId))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString(), "$.id")
			.longValue();
	}

	private long createErgoNcr(long linkedRecordId, Long customerCompanyId, Long supplierCompanyId) throws Exception {
		String body = """
				{"linkedModule": "INCOMING", "linkedRecordId": %d, "issueCategory": "Dimensional",
				 "issueDescription": "desc", "targetClosureDate": "%s", %s %s "ownerUserId": null}"""
			.formatted(linkedRecordId, LocalDate.now().plusDays(30),
					customerCompanyId == null ? "" : "\"customerCompanyId\": " + customerCompanyId + ",",
					supplierCompanyId == null ? "" : "\"supplierCompanyId\": " + supplierCompanyId + ",");
		return JsonPath
			.<Number>read(call(post("/api/quality/ncrs"), ergoQuality, body).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id")
			.longValue();
	}

	/** H2: every NCR is created with {@code customerVisible = false}; tests that only care about whether the
	 * customer can see it at all call this right after creation, before anything else has been authored to wipe. */
	private void makeCustomerVisible(long id) throws Exception {
		call(put("/api/quality/ncrs/{id}", id), ergoQuality, """
				{"issueCategory": "Dimensional", "issueDescription": "desc", "targetClosureDate": "%s",
				 "customerVisible": true}""".formatted(LocalDate.now().plusDays(30)))
			.andExpect(status().isOk());
	}

	private long createLinkedNcr(Login ergo, String linkedModule, long linkedRecordId) throws Exception {
		String body = """
				{"linkedModule": "%s", "linkedRecordId": %d, "issueCategory": "Dimensional", "issueDescription": "desc",
				 "targetClosureDate": "%s"}""".formatted(linkedModule, linkedRecordId, LocalDate.now().plusDays(30));
		return JsonPath
			.<Number>read(call(post("/api/quality/ncrs"), ergo, body).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString(), "$.id")
			.longValue();
	}

	private long pendingClosureNcr(long linkedRecordId) throws Exception {
		long id = createAsSupplier(supplierXQuality, linkedRecordId);
		call(post("/api/quality/ncrs/{id}/move-to-containment", id), supplierXQuality, updateForm("c", null, null, null))
			.andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/move-to-root-cause", id), supplierXQuality, updateForm("c", null, null, null))
			.andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/move-to-capa", id), supplierXQuality, updateForm("c", "r", null, null))
			.andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/submit-for-closure", id), supplierXQuality, updateForm("c", "r", "co", "p"))
			.andExpect(status().isOk());
		return id;
	}

	/** M1: CAPA action creation + evidence upload must happen before {@code submit-for-closure} - they freeze once
	 * the NCR reaches PENDING_CLOSURE_APPROVAL - so this does not simply build on {@link #pendingClosureNcr}. */
	private long closedNcr(long linkedRecordId) throws Exception {
		long id = createAsSupplier(supplierXQuality, linkedRecordId);
		call(post("/api/quality/ncrs/{id}/move-to-containment", id), supplierXQuality, updateForm("c", null, null, null))
			.andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/move-to-root-cause", id), supplierXQuality, updateForm("c", null, null, null))
			.andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/move-to-capa", id), supplierXQuality, updateForm("c", "r", null, null))
			.andExpect(status().isOk());
		String withCapa = call(post("/api/quality/ncrs/{id}/capa-actions", id), ergoQuality,
				capaActionForm("CORRECTIVE", "fix")).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long capaActionId = ((Number) JsonPath.read(withCapa, "$.capaActions[0].id")).longValue();
		mvc.perform(multipart("/api/quality/ncrs/{id}/capa-actions/{capaActionId}/evidence", id, capaActionId)
			.file(new MockMultipartFile("file", "cert.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, ergoQuality.bearer())).andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/submit-for-closure", id), supplierXQuality, updateForm("c", "r", "co", "p"))
			.andExpect(status().isOk());
		call(post("/api/quality/ncrs/{id}/close", id), ergoQuality, "{}").andExpect(status().isOk());
		return id;
	}

	private static String ncrForm(long linkedRecordId) {
		return """
				{"linkedModule": "INCOMING", "linkedRecordId": %d, "issueCategory": "Dimensional",
				 "issueDescription": "part out of tolerance", "targetClosureDate": "%s"}"""
			.formatted(linkedRecordId, LocalDate.now().plusDays(30));
	}

	private static String updateForm(String containment, String rootCause, String corrective, String preventive) {
		return updateForm(containment, rootCause, corrective, preventive, null);
	}

	private static String updateForm(String containment, String rootCause, String corrective, String preventive,
			Long ownerUserId) {
		return """
				{"issueCategory": "Dimensional", "issueDescription": "part out of tolerance", "targetClosureDate": "%s",
				 "containmentAction": %s, "rootCause": %s, "correctiveAction": %s, "preventiveAction": %s,
				 "ownerUserId": %s, "customerVisible": false}"""
			.formatted(LocalDate.now().plusDays(30), json(containment), json(rootCause), json(corrective),
					json(preventive), ownerUserId == null ? "null" : ownerUserId);
	}

	private static String capaActionForm(String type, String description) {
		return """
				{"actionType": "%s", "actionDescription": "%s", "targetDate": "%s"}"""
			.formatted(type, description, LocalDate.now().plusDays(10));
	}

	private static String json(String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}

	/** A minimal OFP order, no MockMvc call needed: {@code orders} has no dependency on RFQ/NPD (same helper as
	 * {@code QualityTests}). */
	private long createOrder(long customerCompanyId, long supplierCompanyId) {
		return jdbc.queryForObject("""
				INSERT INTO orders (order_number, customer_company_id, supplier_company_id, part_number,
				                    ordered_quantity, current_status)
				VALUES (?, ?, ?, 'PN-ORDER', 10, 'ORDER_CREATED') RETURNING order_id""", Long.class,
				"NO" + SEQ.incrementAndGet(), customerCompanyId, supplierCompanyId);
	}

	/** A minimal NPD project (same helper as {@code QualityTests}). */
	private long createNpdProject(long customerCompanyId, long supplierCompanyId) {
		long currencyId = users.currency("INR");
		long rfqId = jdbc.queryForObject("""
				INSERT INTO rfqs (rfq_number, source_type, customer_company_id, created_by_user_id, status, priority,
				                  currency_id)
				VALUES (?, 'CUSTOMER', ?, ?, 'CUSTOMER_APPROVED', 'NORMAL', ?) RETURNING rfq_id""",
				Long.class, "NR" + SEQ.incrementAndGet(), customerCompanyId, ergoAdmin.userId(), currencyId);
		long rfqItemId = jdbc.queryForObject(
				"INSERT INTO rfq_items (rfq_id, line_no, part_number) VALUES (?, 1, 'PN-RFQ') RETURNING rfq_item_id",
				Long.class, rfqId);
		return jdbc.queryForObject("""
				INSERT INTO npd_projects (npd_number, source_rfq_id, source_rfq_item_id, customer_company_id, supplier_company_id,
				                          part_number, stage, created_by_user_id)
				VALUES (?, ?, ?, ?, ?, 'PN-NPD', 'PLANNING', ?) RETURNING npd_project_id""", Long.class,
				"NN" + SEQ.incrementAndGet(), rfqId, rfqItemId, customerCompanyId, supplierCompanyId, ergoAdmin.userId());
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

}
