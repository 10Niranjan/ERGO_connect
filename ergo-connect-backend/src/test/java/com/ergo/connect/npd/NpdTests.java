package com.ergo.connect.npd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

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
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 3a: the manual RFQ->NPD conversion queue and the 9-stage NPD workflow (SRS §13.4, §14.5, §17.4), record
 * scope (§10.6) and the mandatory-activity gate on Close Stage.
 */
@IntegrationTest
class NpdTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long customerA;

	long customerB;

	long supplier;

	long supplierB;

	long inr;

	Login ergoRfq;

	Login ergoManagement;

	Login ergoNpd;

	Login ergoQuality;

	Login buyerA;

	Login buyerB;

	Login seller;

	/** SRS §10.6's NPD-facing customer/supplier roles. CUSTOMER_RFQ/SUPPLIER_RFQ above are given view-only (RV)
	 * access by the SRS, not none; that's deferred here, matching the same under-provisioning precedent 3a already
	 * set for ERGO OFP/Quality. */
	Login npdCustomerA;

	Login npdSupplier;

	Login npdSupplierB;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		customerA = users.company("CUSTOMER");
		customerB = users.company("CUSTOMER");
		supplier = users.company("SUPPLIER");
		supplierB = users.company("SUPPLIER");
		inr = users.currency("INR");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		ergoManagement = users.login(ergo, "ERGO_MANAGEMENT");
		ergoNpd = users.login(ergo, "ERGO_NPD");
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		buyerB = users.login(customerB, "CUSTOMER_RFQ");
		seller = users.login(supplier, "SUPPLIER_RFQ");
		npdCustomerA = users.login(customerA, "CUSTOMER_NPD");
		npdSupplier = users.login(supplier, "SUPPLIER_NPD");
		npdSupplierB = users.login(supplierB, "SUPPLIER_NPD");
	}

	@Test
	void onlyNpdAndManagementConvertAndTheSameRfqCannotBeConvertedTwice() throws Exception {
		long rfqId = approvedRfq(buyerA, "90");

		List<List<String>> actionsForRfq = JsonPath.read(
				call(get("/api/npd/conversion-queue"), ergoRfq, null).andReturn().getResponse().getContentAsString(),
				"$[?(@.rfqId == " + rfqId + ")].actions");
		assertThat(actionsForRfq).hasSize(1);
		assertThat(actionsForRfq.getFirst()).isEmpty(); // ERGO_RFQ cannot convert
		List<List<String>> actionsForNpd = JsonPath.read(
				call(get("/api/npd/conversion-queue"), ergoNpd, null).andReturn().getResponse().getContentAsString(),
				"$[?(@.rfqId == " + rfqId + ")].actions");
		assertThat(actionsForNpd).hasSize(1);
		assertThat(actionsForNpd.getFirst()).containsExactly("CONVERT");

		call(post("/api/npd/convert/{id}", rfqId), ergoRfq, "{}").andExpect(status().isForbidden());
		long projectId = JsonPath
			.<Number>read(
					call(post("/api/npd/convert/{id}", rfqId), ergoNpd, "{\"comment\": \"Kickoff\"}")
						.andExpect(status().isCreated())
						.andExpect(jsonPath("$.npdNumber", containsString("NPD-")))
						.andExpect(jsonPath("$.stage").value("PLANNING"))
						.andExpect(jsonPath("$.rfqId").value((int) rfqId))
						.andReturn().getResponse().getContentAsString(), "$.id")
			.longValue();

		// the queue no longer offers an already-converted RFQ, and converting it again is a conflict
		call(get("/api/npd/conversion-queue"), ergoNpd, null)
			.andExpect(jsonPath("$[*].rfqId", not(hasItem((int) rfqId))));
		call(post("/api/npd/convert/{id}", rfqId), ergoManagement, "{}").andExpect(status().isConflict());

		// the very first stage_history row: PLANNING with no previous stage
		List<String> stages = jdbc.queryForList("""
				SELECT stage_name FROM npd_stage_history WHERE npd_project_id = ? ORDER BY npd_stage_history_id""",
				String.class, projectId);
		assertThat(stages).containsExactly("PLANNING");
		assertThat(jdbc.queryForObject(
				"SELECT previous_stage_name FROM npd_stage_history WHERE npd_project_id = ?", String.class, projectId))
			.isNull();
		// the automatic audit trail also picked up the conversion
		assertThat(jdbc.queryForList(
				"SELECT event_type FROM audit_logs WHERE entity_name = 'NpdProject' AND record_id = ?", String.class,
				projectId)).contains("CREATE");
	}

	@Test
	void convertingAnRfqWithNoApprovedQuoteFails() throws Exception {
		long id = JsonPath
			.<Number>read(call(post("/api/rfqs"), buyerA, """
					{"partNumber": "PN-NOQ", "description": "Bracket", "materialSpec": "EN8",
					 "targetQuoteDate": "%s", "currencyId": %d, "targetProductionPiecePrice": 99.5,
					 "processSteps": [{"stepName": "Casting"}]}""".formatted(LocalDate.now().plusDays(10), inr))
				.andReturn().getResponse().getContentAsString(), "$.id")
			.longValue();
		call(post("/api/rfqs/{id}/submit", id), buyerA, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		call(post("/api/rfqs/{id}/validate", id), ergoRfq, "{}").andExpect(status().isOk());

		// ERGO_ADMIN (unrestricted RFQ scope), not ERGO_NPD: RfqApi.forErgo only lets ERGO_NPD see RFQs that are
		// already CUSTOMER_APPROVED, so ERGO_NPD would get 404 here rather than reach the "no approved quote" check.
		call(post("/api/npd/convert/{id}", id), ergoManagement, "{}").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("approved customer quote")));
	}

	@Test
	void keyAccountManagersSeeOnlyTheirAssignedCustomersProjectsAndOfpQualityViewEverythingButConvertNothing() throws Exception {
		long projectA = convert(approvedRfq(buyerA, "90"));
		long projectB = convert(approvedRfq(buyerB, "95"));
		Login kam = users.login(users.company("ERGO"), "ERGO_KAM");
		users.assignAccounts(kam.userId(), customerA);
		Login ofp = users.login(users.company("ERGO"), "ERGO_OFP");
		Login quality = users.login(users.company("ERGO"), "ERGO_QUALITY");

		call(get("/api/npd"), kam, null).andExpect(jsonPath("$[*].id", hasItem((int) projectA)))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) projectB))));
		call(get("/api/npd/{id}", projectA), kam, null).andExpect(status().isOk());
		call(get("/api/npd/{id}", projectB), kam, null).andExpect(status().isNotFound());

		// role sheet: ERGO OFP (V) and ERGO Quality (A) see every project, but only the O roles convert
		for (Login viewer : List.of(ofp, quality)) {
			call(get("/api/npd"), viewer, null).andExpect(jsonPath("$[*].id", hasItem((int) projectA)))
				.andExpect(jsonPath("$[*].id", hasItem((int) projectB)));
			call(get("/api/npd/{id}", projectB), viewer, null).andExpect(status().isOk());
		}
		call(get("/api/npd/conversion-queue"), ofp, null).andExpect(status().isOk())
			.andExpect(jsonPath("$", org.hamcrest.Matchers.empty())); // no RFQ access, so nothing to convert
		call(post("/api/npd/convert/{id}", projectA), quality, "{}").andExpect(status().isForbidden());
		call(post("/api/npd/convert/{id}", projectA), ofp, "{}").andExpect(status().isForbidden());

		// ERGO sees the supplier (its own internal view, SRS §12.8.1's own list column); no customer/supplier
		// role ever reaches this endpoint at all, checked above and in the RFQ-side anonymity tests
		String supplierName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?",
				String.class, supplier);
		call(get("/api/npd"), ergoNpd, null).andExpect(jsonPath("$[*].supplierName", hasItem(supplierName)));
	}

	@Test
	void auditTrailIsErgoOnly() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		call(put("/api/npd/{id}", projectId), ergoNpd, "{\"riskLevel\": \"HIGH\"}").andExpect(status().isOk());

		call(get("/api/npd/{id}/history", projectId), npdCustomerA, null).andExpect(status().isForbidden());
		call(get("/api/npd/{id}/history", projectId), ergoNpd, null)
			.andExpect(jsonPath("$[*].event", hasItem("CREATE")));
	}

	@Test
	void closeStageIsBlockedUntilMandatoryActivitiesAreDoneAndEveryMoveIsRecorded() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));

		long mandatoryActivity = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/activities", projectId), ergoNpd, activityForm("Tool trial", true))
						.andExpect(status().isCreated())
						.andReturn().getResponse().getContentAsString(),
					"$.activities[?(@.activityName == 'Tool trial')].id")
			.getFirst().longValue();
		call(post("/api/npd/{id}/activities", projectId), ergoNpd, activityForm("Optional prep", false))
			.andExpect(status().isCreated());

		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail", containsString("Tool trial")));

		// only Admin/NPD may edit an activity or close a stage
		call(put("/api/npd/activities/{id}", mandatoryActivity), ergoRfq, activityForm("Tool trial", true))
			.andExpect(status().isForbidden());
		call(post("/api/npd/{id}/close-stage", projectId), ergoRfq, "{}").andExpect(status().isForbidden());

		call(put("/api/npd/activities/{id}", mandatoryActivity), ergoNpd,
				activityFormWithStatus("Tool trial", true, "DONE")).andExpect(status().isOk())
			.andExpect(jsonPath("$.activities[?(@.id == %d)].status".formatted(mandatoryActivity),
					contains("DONE")));

		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{\"comment\": \"All mandatory work done\"}")
			.andExpect(jsonPath("$.stage").value("COMMERCIAL_SOURCING_READINESS"))
			.andExpect(jsonPath("$.stageHistory", hasSize(2)));

		List<String> stages = jdbc.queryForList("""
				SELECT stage_name FROM npd_stage_history WHERE npd_project_id = ? ORDER BY npd_stage_history_id""",
				String.class, projectId);
		assertThat(stages).containsExactly("PLANNING", "COMMERCIAL_SOURCING_READINESS");
		assertThat(jdbc.queryForObject("""
				SELECT action_reason FROM audit_logs WHERE entity_name = 'NpdProject' AND record_id = ?
				AND field_name = 'stage' ORDER BY audit_log_id DESC LIMIT 1""", String.class, projectId))
			.isEqualTo("All mandatory work done");
	}

	@Test
	void markingBlockedRequiresAReasonAndUnblockClearsIt() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		long activityId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/activities", projectId), ergoNpd, activityForm("Sample run", true))
						.andReturn().getResponse().getContentAsString(),
					"$.activities[?(@.activityName == 'Sample run')].id")
			.getFirst().longValue();

		call(post("/api/npd/activities/{id}/block", activityId), ergoNpd, "{}")
			.andExpect(status().isUnprocessableContent());
		String blocked = call(post("/api/npd/activities/{id}/block", activityId), ergoNpd,
				"{\"comment\": \"Waiting for tool steel\"}").andReturn().getResponse().getContentAsString();
		List<Boolean> blockedFlags = JsonPath.read(blocked, "$.activities[?(@.id == " + activityId + ")].blockedFlag");
		assertThat(blockedFlags).containsExactly(true);
		List<List<String>> blockedActions = JsonPath.read(blocked,
				"$.activities[?(@.id == " + activityId + ")].actions");
		assertThat(blockedActions).hasSize(1);
		// ERGO Admin/NPD's already-broader rights also cover the caller's-own-company actions (3b)
		assertThat(blockedActions.getFirst()).containsExactly("EDIT_ACTIVITY", "UNBLOCK", "UPDATE_OWN_ACTIVITY",
				"RESPOND_TO_PROMPT");

		String unblocked = call(post("/api/npd/activities/{id}/unblock", activityId), ergoNpd, "{}").andReturn()
			.getResponse().getContentAsString();
		List<Boolean> unblockedFlags = JsonPath.read(unblocked,
				"$.activities[?(@.id == " + activityId + ")].blockedFlag");
		assertThat(unblockedFlags).containsExactly(false);

		assertThat(jdbc.queryForObject("""
				SELECT action_reason FROM audit_logs WHERE entity_name = 'NpdActivity' AND record_id = ?
				AND field_name = 'blockedFlag' AND new_value = 'true'""", String.class, activityId))
			.isEqualTo("Waiting for tool steel");
	}

	@Test
	void customerRfqIsViewOnlyAndSupplierRfqReachesNothing() throws Exception {
		// role sheet: CUSTOMER_RFQ has V on NPD (its own company's projects, read-only); SUPPLIER_RFQ has no NPD access
		long projectId = convert(approvedRfq(buyerA, "90"));

		call(get("/api/npd/{id}", projectId), buyerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CUSTOMER"))
			.andExpect(jsonPath("$.actions", org.hamcrest.Matchers.empty()));
		call(get("/api/npd/{id}", projectId), buyerB, null).andExpect(status().isNotFound()); // another company's
		call(get("/api/npd/{id}", projectId), seller, null).andExpect(status().isForbidden());
		call(get("/api/npd/mine"), seller, null).andExpect(status().isForbidden());

		for (Login outsider : List.of(buyerA, seller)) {
			call(get("/api/npd/conversion-queue"), outsider, null).andExpect(status().isForbidden());
			call(get("/api/npd"), outsider, null).andExpect(status().isForbidden());
			call(put("/api/npd/{id}", projectId), outsider, "{}").andExpect(status().isForbidden());
			call(post("/api/npd/{id}/close-stage", projectId), outsider, "{}").andExpect(status().isForbidden());
			call(post("/api/npd/{id}/activities", projectId), outsider, activityForm("Sneak in", true))
				.andExpect(status().isForbidden());
		}
	}

	@Test
	void updateProjectSetsHeaderFieldsAndOwnerMustExist() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));

		call(put("/api/npd/{id}", projectId), ergoRfq, """
				{"ownerUserId": null, "riskLevel": "HIGH", "targetSampleDate": "%s"}"""
			.formatted(LocalDate.now().plusDays(30))).andExpect(status().isForbidden()); // view-only role

		call(put("/api/npd/{id}", projectId), ergoNpd, "{\"ownerUserId\": 999999999, \"riskLevel\": \"HIGH\"}")
			.andExpect(status().isUnprocessableContent());

		call(put("/api/npd/{id}", projectId), ergoNpd, """
				{"ownerUserId": %d, "riskLevel": "HIGH", "targetSampleDate": "%s", "actualSampleSubmissionDate": "%s"}"""
			.formatted(ergoNpd.userId(), LocalDate.now().plusDays(30), LocalDate.now()))
			.andExpect(jsonPath("$.riskLevel").value("HIGH"))
			.andExpect(jsonPath("$.owner").exists())
			.andExpect(jsonPath("$.actualSampleSubmissionDate").value(LocalDate.now().toString()));

		// the ERGO-internal list row carries the same fields (SRS §12.8.1)
		call(get("/api/npd"), ergoNpd, null)
			.andExpect(jsonPath("$[?(@.id == " + projectId + ")].riskLevel", contains("HIGH")));
	}

	// ---------------------------------------------------------------- Phase 3b

	@Test
	void supplierUpdatesOwnActivityProgressButNotAnotherCompanysAndOnlyItsOwnFields() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		long supplierActivityId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/activities", projectId), ergoNpd,
							ownedActivityForm("Tooling trial", "SUPPLIER", supplier))
						.andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.activities[?(@.activityName == 'Tooling trial')].id")
			.getFirst()
			.longValue();
		long customerActivityId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/activities", projectId), ergoNpd,
							ownedActivityForm("Customer sign-off", "CUSTOMER", customerA))
						.andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.activities[?(@.activityName == 'Customer sign-off')].id")
			.getFirst()
			.longValue();

		// the supplier's own activity: succeeds
		String progressed = call(post("/api/npd/activities/{id}/progress", supplierActivityId), npdSupplier, """
				{"status": "IN_PROGRESS", "issueFlag": false, "riskFlag": false, "remarks": "underway"}""")
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(
				JsonPath.<List<String>>read(progressed, "$.activities[?(@.id == " + supplierActivityId + ")].status"))
			.containsExactly("IN_PROGRESS");
		assertThat(JsonPath.<List<String>>read(progressed,
				"$.activities[?(@.id == " + supplierActivityId + ")].remarks")).containsExactly("underway");

		// another company's activity in the same (otherwise visible) project: 404, not revealed
		call(post("/api/npd/activities/{id}/progress", customerActivityId), npdSupplier, """
				{"status": "DONE", "issueFlag": false, "riskFlag": false}""").andExpect(status().isNotFound());

		// a different supplier company (not this project's own supplier) reaches neither the project nor the activity
		call(get("/api/npd/{id}", projectId), npdSupplierB, null).andExpect(status().isNotFound());
		call(post("/api/npd/activities/{id}/progress", supplierActivityId), npdSupplierB, """
				{"status": "DONE", "issueFlag": false, "riskFlag": false}""").andExpect(status().isNotFound());

		// ActivityProgressForm has no activityName/owner/mandatory component at all: a real call through this path
		// leaves them exactly as ERGO set them, proven here rather than merely left untested
		String afterUpdate = call(post("/api/npd/activities/{id}/progress", supplierActivityId), npdSupplier, """
				{"status": "DONE", "actualDate": "%s", "issueFlag": true, "riskFlag": false, "remarks": "done"}"""
			.formatted(LocalDate.now())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(afterUpdate,
				"$.activities[?(@.id == " + supplierActivityId + ")].activityName")).containsExactly("Tooling trial");
		assertThat(JsonPath.<List<Boolean>>read(afterUpdate,
				"$.activities[?(@.id == " + supplierActivityId + ")].mandatoryFlag")).containsExactly(true);
		assertThat(JsonPath.<List<String>>read(afterUpdate,
				"$.activities[?(@.id == " + supplierActivityId + ")].ownerOrganizationType")).containsExactly("SUPPLIER");
	}

	@Test
	void customerCannotRaiseAnIssueButCanUpdateItsOwnAndNotTheSuppliersOnTheSameProject() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));

		// create is ERGO-only for every customer/supplier role (SRS §10.6's literal Create=X)
		call(post("/api/npd/{id}/issues", projectId), npdCustomerA,
				issueForm("ISSUE", "Late drawing", "CUSTOMER", customerA)).andExpect(status().isForbidden());

		long customerIssueId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/issues", projectId), ergoNpd,
							issueForm("ISSUE", "Late drawing", "CUSTOMER", customerA)).andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.issuesRisks[?(@.title == 'Late drawing')].id")
			.getFirst()
			.longValue();
		long supplierIssueId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/issues", projectId), ergoNpd,
							issueForm("RISK", "Tooling delay", "SUPPLIER", supplier)).andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.issuesRisks[?(@.title == 'Tooling delay')].id")
			.getFirst()
			.longValue();

		call(put("/api/npd/issues/{id}/status", customerIssueId), npdCustomerA, "{\"status\": \"IN_PROGRESS\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.issuesRisks[?(@.id == " + customerIssueId + ")].status", contains("IN_PROGRESS")));

		// owned by the supplier's company on the same project: 404, not revealed
		call(put("/api/npd/issues/{id}/status", supplierIssueId), npdCustomerA, "{\"status\": \"CLOSED\"}")
			.andExpect(status().isNotFound());
	}

	@Test
	void reopeningAClosedIssueRequiresAReason() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		long issueId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/issues", projectId), ergoNpd,
							issueForm("ISSUE", "Late drawing", "CUSTOMER", customerA)).andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.issuesRisks[?(@.title == 'Late drawing')].id")
			.getFirst()
			.longValue();
		call(put("/api/npd/issues/{id}/status", issueId), npdCustomerA, "{\"status\": \"CLOSED\"}")
			.andExpect(status().isOk());

		call(put("/api/npd/issues/{id}/status", issueId), npdCustomerA, "{\"status\": \"OPEN\"}")
			.andExpect(status().isUnprocessableContent());
		call(put("/api/npd/issues/{id}/status", issueId), npdCustomerA,
				"{\"status\": \"OPEN\", \"comment\": \"Drawing has now arrived\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.issuesRisks[?(@.id == " + issueId + ")].status", contains("OPEN")));
		assertThat(jdbc.queryForObject("""
				SELECT action_reason FROM audit_logs WHERE entity_name = 'NpdIssueRisk' AND record_id = ?
				AND field_name = 'status' ORDER BY audit_log_id DESC LIMIT 1""", String.class, issueId))
			.isEqualTo("Drawing has now arrived");
	}

	@Test
	void aClosedProjectFreezesIssueAndApprovalWritesToo() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		long issueId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/issues", projectId), ergoNpd,
							issueForm("ISSUE", "Late drawing", "CUSTOMER", customerA)).andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.issuesRisks[?(@.title == 'Late drawing')].id")
			.getFirst()
			.longValue();
		long approvalId = approvalId(projectId, "STAGE_GATE");
		closeUntilClosed(projectId);

		call(put("/api/npd/issues/{id}/status", issueId), ergoNpd, "{\"status\": \"IN_PROGRESS\"}")
			.andExpect(status().isConflict());
		call(post("/api/npd/{id}/issues", projectId), ergoNpd,
				issueForm("ISSUE", "Too late now", "CUSTOMER", customerA)).andExpect(status().isConflict());
		call(post("/api/npd/{id}/approvals", projectId), ergoNpd, "{\"approvalType\": \"STAGE_GATE\"}")
			.andExpect(status().isConflict());
		call(post("/api/npd/approvals/{id}/decide", approvalId), ergoNpd, "{\"approved\": true}")
			.andExpect(status().isConflict());
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isConflict());
	}

	@Test
	void onlyTheMatchingApproverRoleCanDecideAndNotTwice() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		Login npdCustomerB = users.login(customerB, "CUSTOMER_NPD");

		long sampleApprovalId = approvalId(projectId, "CUSTOMER_SAMPLE_APPROVAL");

		// wrong company: visible() already confines a customer decider to their own project (404, not revealed)
		call(post("/api/npd/approvals/{id}/decide", sampleApprovalId), npdCustomerB, "{\"approved\": true}")
			.andExpect(status().isNotFound());
		// supplier: never admitted at the controller layer for any approval
		call(post("/api/npd/approvals/{id}/decide", sampleApprovalId), npdSupplier, "{\"approved\": true}")
			.andExpect(status().isForbidden());
		// wrong ERGO role: ERGO_NPD (STAGE_GATE's own approver) may not decide a customer-only approval type; 404
		// (not 409), so a role that could never decide this type can't tell PENDING apart from not-existing (L2)
		call(post("/api/npd/approvals/{id}/decide", sampleApprovalId), ergoNpd, "{\"approved\": true}")
			.andExpect(status().isNotFound());

		// the right company, right role: succeeds
		call(post("/api/npd/approvals/{id}/decide", sampleApprovalId), npdCustomerA,
				"{\"approved\": true, \"comments\": \"Sample ok\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.approvals[?(@.id == " + sampleApprovalId + ")].approvalStatus",
					contains("APPROVED")));

		// a decided (non-PENDING) approval can't be decided again
		call(post("/api/npd/approvals/{id}/decide", sampleApprovalId), npdCustomerA, "{\"approved\": true}")
			.andExpect(status().isConflict());

		// rejecting requires a reason
		long milestoneApprovalId = approvalId(projectId, "CUSTOMER_MILESTONE_ACKNOWLEDGMENT");
		call(post("/api/npd/approvals/{id}/decide", milestoneApprovalId), npdCustomerA, "{\"approved\": false}")
			.andExpect(status().isUnprocessableContent());
		call(post("/api/npd/approvals/{id}/decide", milestoneApprovalId), npdCustomerA,
				"{\"approved\": false, \"comments\": \"Sample failed dimensional check\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.approvals[?(@.id == " + milestoneApprovalId + ")].approvalStatus",
					contains("REJECTED")));

		// ERGO Management may decide an ERGO-owned type on top of the named approver role (requested by ergoNpd, decided
		// by a different user: ergoManagement, so this isn't also exercising the self-decide guard)
		long stageGateApprovalId = approvalId(projectId, "STAGE_GATE");
		call(post("/api/npd/approvals/{id}/decide", stageGateApprovalId), ergoManagement, "{\"approved\": true}")
			.andExpect(status().isOk());
	}

	@Test
	void ergoManagementsBlanketOverrideNeverExtendsToACustomerFacingApprovalType() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		long sampleApprovalId = approvalId(projectId, "CUSTOMER_SAMPLE_APPROVAL");
		long qualityApprovalId = approvalId(projectId, "CUSTOMER_QUALITY_ACCEPTANCE");

		// CUSTOMER_* stays the customer's own gate (SRS §10.6 E**/A**); ERGO Admin gets 404, same as any other
		// role that could never decide this type
		call(post("/api/npd/approvals/{id}/decide", sampleApprovalId), ergoManagement, "{\"approved\": true}")
			.andExpect(status().isNotFound());
		call(post("/api/npd/approvals/{id}/decide", qualityApprovalId), ergoManagement, "{\"approved\": true}")
			.andExpect(status().isNotFound());
	}

	@Test
	void theUserWhoRequestedAnApprovalCannotDecideItThemselvesEvenIfTheyHoldTheApproverRole() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		// approvalId() always requests as ergoNpd, STAGE_GATE's own approver role
		long stageGateApprovalId = approvalId(projectId, "STAGE_GATE");

		call(post("/api/npd/approvals/{id}/decide", stageGateApprovalId), ergoNpd, "{\"approved\": true}")
			.andExpect(status().isConflict());

		// a different person, ERGO Management (an ERGO-owned type), can still decide it
		call(post("/api/npd/approvals/{id}/decide", stageGateApprovalId), ergoManagement, "{\"approved\": true}")
			.andExpect(status().isOk());
	}

	@Test
	void closeStageIsBlockedByAPendingApprovalEvenWhenTheActivityIsDoneAndUnblocksOnceApproved() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		long activityId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/activities", projectId), ergoNpd,
							approvalGatedActivityForm("Customer sample gate", "CUSTOMER_SAMPLE_APPROVAL"))
						.andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.activities[?(@.activityName == 'Customer sample gate')].id")
			.getFirst()
			.longValue();
		call(put("/api/npd/activities/{id}", activityId), ergoNpd,
				approvalGatedActivityFormWithStatus("Customer sample gate", "DONE", "CUSTOMER_SAMPLE_APPROVAL"))
			.andExpect(status().isOk());

		// DONE, but the mandatory approval hasn't even been requested yet: still blocked
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail", containsString("pending approval")));

		// M2 bypass attempt: this activity requires CUSTOMER_SAMPLE_APPROVAL, but ERGO requests and approves an
		// unrelated STAGE_GATE approval linked to the very same activity instead. Must NOT satisfy the gate.
		// Decided by ergoManagement rather than ergoNpd (the requester) since the M-1 self-approval rule (ARCHITECTURE.md
		// §8 2026-09-27) forbids the same user from both requesting and deciding any approval, STAGE_GATE included.
		long wrongTypeApprovalId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/approvals", projectId), ergoNpd,
							"{\"approvalType\": \"STAGE_GATE\", \"linkedActivityId\": %d}".formatted(activityId))
						.andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.approvals[?(@.linkedActivityId == " + activityId + " && @.approvalType == 'STAGE_GATE')].id")
			.getFirst()
			.longValue();
		call(post("/api/npd/approvals/{id}/decide", wrongTypeApprovalId), ergoManagement, "{\"approved\": true}")
			.andExpect(status().isOk());
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail", containsString("pending approval")));

		long approvalId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/approvals", projectId), ergoNpd,
							"{\"approvalType\": \"CUSTOMER_SAMPLE_APPROVAL\", \"linkedActivityId\": %d}"
								.formatted(activityId)).andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.approvals[?(@.linkedActivityId == " + activityId
							+ " && @.approvalType == 'CUSTOMER_SAMPLE_APPROVAL')].id")
			.getFirst()
			.longValue();

		// requested but still PENDING: still blocked
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isConflict());

		call(post("/api/npd/approvals/{id}/decide", approvalId), npdCustomerA, "{\"approved\": true}")
			.andExpect(status().isOk());

		// now APPROVED with the *right* type: unblocked
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{\"comment\": \"gate cleared\"}")
			.andExpect(jsonPath("$.stage").value("COMMERCIAL_SOURCING_READINESS"));
	}

	// ---------------------------------------------------------------- Phase 5d: Final Sample Readiness quality gate

	/** Closing Final Sample Readiness now additionally needs a real Quality sign-off (SRS's "Final" inspection
	 * against the project), read through {@code quality.api.QualityApi} - mirrors OFP's dispatch gate matrix
	 * (`OfpTests`). One inspection is driven through every state: zero inspections, OPEN, IN_INSPECTION, REJECTED,
	 * then REJECTED-then-CLOSED (the audit-history recovery of a CLOSED inspection's pre-close disposition) are all
	 * still blocked; only once it is reopened and driven to PASSED does the gate open. */
	@Test
	void closeStageAtFinalSampleReadinessIsGatedOnQualityInspectionsResolvingToPassOrDeviation() throws Exception {
		long projectId = advanceToFinalSampleReadiness(convert(approvedRfq(buyerA, "90")));

		// zero inspections: still blocked
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(
					"A final sample inspection must pass (or be released with deviation) before Final Sample Readiness can be closed"));

		// OPEN: still blocked
		long inspectionId = JsonPath
			.<Number>read(call(post("/api/quality/inspections"), ergoQuality, """
					{"linkedModule": "NPD", "linkedRecordId": %d, "partNumber": "PN-STD", "customerVisible": false}"""
				.formatted(projectId)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
					"$.id")
			.longValue();
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isConflict());

		// IN_INSPECTION: still blocked
		call(post("/api/quality/inspections/{id}/submit", inspectionId), ergoQuality, """
				{"partNumber": "PN-STD", "quantityChecked": 5, "quantityRejected": 0, "inspectionDate": "%s",
				 "customerVisible": false}""".formatted(LocalDate.now())).andExpect(status().isOk());
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isConflict());

		// REJECTED: still blocked
		call(post("/api/quality/inspections/{id}/reject", inspectionId), ergoManagement,
				"{\"reason\": \"Sample out of tolerance\"}").andExpect(status().isOk());
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isConflict());

		// CLOSED with REJECTED as its last pre-close disposition: still blocked
		call(post("/api/quality/inspections/{id}/close", inspectionId), ergoManagement, "{}").andExpect(status().isOk());
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isConflict());

		// reopen, resubmit and pass it: gate now met
		call(post("/api/quality/inspections/{id}/reopen", inspectionId), ergoManagement,
				"{\"reason\": \"Re-inspecting after rework\"}").andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/submit", inspectionId), ergoQuality, """
				{"partNumber": "PN-STD", "quantityChecked": 5, "quantityRejected": 0, "inspectionDate": "%s",
				 "customerVisible": false}""".formatted(LocalDate.now())).andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/pass", inspectionId), ergoManagement, "{}").andExpect(status().isOk());
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}")
			.andExpect(jsonPath("$.stage").value("CUSTOMER_FEEDBACK"));
	}

	/** RELEASED_WITH_DEVIATION also satisfies the gate, same as PASSED. */
	@Test
	void closeStageAtFinalSampleReadinessIsAllowedWhenTheOnlyInspectionWasReleasedWithDeviation() throws Exception {
		long projectId = advanceToFinalSampleReadiness(convert(approvedRfq(buyerA, "90")));
		long inspectionId = JsonPath
			.<Number>read(call(post("/api/quality/inspections"), ergoQuality, """
					{"linkedModule": "NPD", "linkedRecordId": %d, "partNumber": "PN-STD", "customerVisible": false}"""
				.formatted(projectId)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
					"$.id")
			.longValue();
		call(post("/api/quality/inspections/{id}/submit", inspectionId), ergoQuality, """
				{"partNumber": "PN-STD", "quantityChecked": 5, "quantityRejected": 0, "inspectionDate": "%s",
				 "customerVisible": false}""".formatted(LocalDate.now())).andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/release-with-deviation", inspectionId), ergoManagement,
				"{\"reason\": \"Minor deviation, customer concession granted\"}").andExpect(status().isOk());
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}")
			.andExpect(jsonPath("$.stage").value("CUSTOMER_FEEDBACK"));
	}

	/** A CLOSED inspection whose last pre-close disposition was PASSED still satisfies the gate. */
	@Test
	void closeStageAtFinalSampleReadinessIsAllowedWhenTheOnlyInspectionIsClosedAfterPassing() throws Exception {
		long projectId = advanceToFinalSampleReadiness(convert(approvedRfq(buyerA, "90")));
		passNpdInspection(projectId);
		long inspectionId = jdbc.queryForObject("""
				SELECT inspection_id FROM inspections WHERE linked_module = 'NPD' AND linked_record_id = ?""",
				Long.class, projectId);
		call(post("/api/quality/inspections/{id}/close", inspectionId), ergoManagement, "{}").andExpect(status().isOk());
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}")
			.andExpect(jsonPath("$.stage").value("CUSTOMER_FEEDBACK"));
	}

	/** Closes PLANNING through Trial Execution (5 close-stage calls, no mandatory activities), leaving the project
	 * sitting at FINAL_SAMPLE_READINESS without closing that stage itself. */
	private long advanceToFinalSampleReadiness(long projectId) throws Exception {
		for (int i = 0; i < 5; i++) {
			call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isOk());
		}
		return projectId;
	}

	@Test
	void approvalRequiredActivityMustNameARequiredApprovalTypeAndItIsForcedToNullWhenNotRequired() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));

		call(post("/api/npd/{id}/activities", projectId), ergoNpd, """
				{"activityName": "No type given", "status": "NOT_STARTED", "mandatory": true, "riskFlag": false,
				 "issueFlag": false, "ownerOrganizationType": "ERGO", "approvalRequired": true}""")
			.andExpect(status().isUnprocessableContent());

		// approvalRequired: false, with a requiredApprovalType sent anyway: server forces it to null regardless
		String created = call(post("/api/npd/{id}/activities", projectId), ergoNpd, """
				{"activityName": "Type ignored", "status": "NOT_STARTED", "mandatory": true, "riskFlag": false,
				 "issueFlag": false, "ownerOrganizationType": "ERGO", "approvalRequired": false,
				 "requiredApprovalType": "STAGE_GATE"}""").andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(created).doesNotContain("STAGE_GATE");
	}

	@Test
	void customerAndSupplierViewsNeverCarryTheOtherCompanyOrItsActivities() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		call(post("/api/npd/{id}/activities", projectId), ergoNpd, ownedActivityForm("ERGO task", "ERGO", null))
			.andExpect(status().isCreated());
		long customerActivityId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/activities", projectId), ergoNpd,
							ownedActivityForm("Customer task", "CUSTOMER", customerA)).andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.activities[?(@.activityName == 'Customer task')].id")
			.getFirst()
			.longValue();
		call(post("/api/npd/{id}/activities", projectId), ergoNpd,
				ownedActivityForm("Supplier task", "SUPPLIER", supplier)).andExpect(status().isCreated());

		// an issue owned by the supplier but linked to the customer's own activity: raising it must not let it
		// surface in the customer's view, where IssueRiskView.ownerCompanyName would otherwise leak the supplier's
		// name through the linked-activity side of the customer's own visibility
		call(post("/api/npd/{id}/issues", projectId), ergoNpd, """
				{"type": "ISSUE", "title": "Supplier-side blocker", "severity": "MEDIUM",
				 "ownerOrganizationType": "SUPPLIER", "ownerCompanyId": %d, "linkedActivityId": %d}"""
			.formatted(supplier, customerActivityId)).andExpect(status().isCreated());
		String supplierName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?",
				String.class, supplier);

		String customerView = call(get("/api/npd/{id}", projectId), npdCustomerA, null).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(customerView).doesNotContain("\"supplier\":");
		assertThat(customerView).doesNotContain(supplierName);
		assertThat(JsonPath.<List<String>>read(customerView, "$.activities[*].activityName"))
			.containsExactly("Customer task");
		assertThat(JsonPath.<List<String>>read(customerView, "$.issuesRisks[*].title"))
			.doesNotContain("Supplier-side blocker");
		assertThat((String) JsonPath.read(customerView, "$.audience")).isEqualTo("CUSTOMER");

		String supplierView = call(get("/api/npd/{id}", projectId), npdSupplier, null).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(supplierView).doesNotContain("\"customer\":");
		// no approvals data at all for a supplier (SRS §10.6: X on Approve)
		assertThat(supplierView).doesNotContain("\"approvals\":");
		assertThat(JsonPath.<List<String>>read(supplierView, "$.activities[*].activityName"))
			.containsExactly("Supplier task");
		assertThat((String) JsonPath.read(supplierView, "$.audience")).isEqualTo("SUPPLIER");
	}

	@Test
	void myProjectsListsOnlyTheCallersOwnCompanyProjects() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));

		call(get("/api/npd/mine"), npdCustomerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value((int) projectId));
		call(get("/api/npd/mine"), npdSupplier, null).andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value((int) projectId));

		// a company with no project at all sees an empty list, not another company's project
		call(get("/api/npd/mine"), npdSupplierB, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

		// ERGO roles use list()/get(), not this customer/supplier-only entry point
		call(get("/api/npd/mine"), ergoNpd, null).andExpect(status().isForbidden());
	}

	@Test
	void stageHistoryRemarksNeverReachCustomerOrSupplierViewsButRiskLevelIsAlsoErgoOnly() throws Exception {
		long rfqId = approvedRfq(buyerA, "90");
		String supplierName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?",
				String.class, supplier);
		long projectId = JsonPath
			.<Number>read(
					call(post("/api/npd/convert/{id}", rfqId), ergoNpd,
							"{\"comment\": \"Kickoff with %s\"}".formatted(supplierName)).andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.id")
			.longValue();
		call(post("/api/npd/{id}/close-stage", projectId), ergoNpd,
				"{\"comment\": \"Confirmed with %s at a tight price\"}".formatted(supplierName))
			.andExpect(jsonPath("$.stage").value("COMMERCIAL_SOURCING_READINESS"));

		String customerView = call(get("/api/npd/{id}", projectId), npdCustomerA, null).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(customerView).doesNotContain(supplierName);
		assertThat(customerView).doesNotContain("\"remarks\"");
		assertThat(customerView).doesNotContain("riskLevel");
		assertThat(JsonPath.<List<String>>read(customerView, "$.stageHistory[*].stage"))
			.containsExactly("PLANNING", "COMMERCIAL_SOURCING_READINESS");

		String supplierView = call(get("/api/npd/{id}", projectId), npdSupplier, null).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(supplierView).doesNotContain("\"remarks\"");
		assertThat(supplierView).doesNotContain("riskLevel");

		// ERGO's own view still carries the full remarks and riskLevel
		String ergoView = call(get("/api/npd/{id}", projectId), ergoNpd, null).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(ergoView).contains(supplierName);
		assertThat(ergoView).contains("riskLevel");
	}

	@Test
	void reassigningAnActivitysOwnerClearsThePreviousOwnersRemarksAndChecklistAnswers() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		// responsibleUserId set at creation (the supplier's own employee, valid per M1) so clearing it on
		// reassignment is a meaningful assertion below, not vacuously true because it was already null.
		long activityId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/activities", projectId), ergoNpd, """
							{"activityName": "Tooling trial", "status": "NOT_STARTED", "mandatory": true,
							 "riskFlag": false, "issueFlag": false, "ownerOrganizationType": "SUPPLIER",
							 "ownerCompanyId": %d, "responsibleUserId": %d, "approvalRequired": false}"""
								.formatted(supplier, npdSupplier.userId())).andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.activities[?(@.activityName == 'Tooling trial')].id")
			.getFirst()
			.longValue();
		long checklistId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/prompt-checklists", projectId), ergoNpd, """
							{"activityId": %d, "questionText": "Is the tool steel confirmed?",
							 "responseType": "YES_NO", "mandatory": true, "sequenceNo": 1}""".formatted(activityId))
						.andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.activities[?(@.id == " + activityId + ")].promptChecklists[*].id")
			.getFirst()
			.longValue();
		call(post("/api/npd/prompt-checklists/{id}/response", checklistId), npdSupplier,
				"{\"responseValue\": \"Yes\", \"remarks\": \"Confirmed with our tool room\"}")
			.andExpect(status().isOk());
		call(post("/api/npd/activities/{id}/progress", activityId), npdSupplier, """
				{"status": "IN_PROGRESS", "issueFlag": false, "riskFlag": false,
				 "remarks": "Waiting on our own steel supplier"}""").andExpect(status().isOk());

		// ERGO reassigns the activity from the supplier to the customer
		String reassigned = call(put("/api/npd/activities/{id}", activityId), ergoNpd,
				ownedActivityForm("Tooling trial", "CUSTOMER", customerA)).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(reassigned).doesNotContain("Confirmed with our tool room");
		assertThat(reassigned).doesNotContain("Waiting on our own steel supplier");
		// the question itself (ERGO's template) survives the reassignment; only the answer was the leak
		assertThat(reassigned).contains("Is the tool steel confirmed?");
		assertThat(jdbc.queryForList("SELECT * FROM npd_prompt_responses WHERE checklist_id = ?", checklistId))
			.isEmpty();
		assertThat(jdbc.queryForObject("SELECT remarks FROM npd_activities WHERE npd_activity_id = ?", String.class,
				activityId)).isNull();
		assertThat(jdbc.queryForObject("SELECT responsible_user_id FROM npd_activities WHERE npd_activity_id = ?",
				Long.class, activityId)).isNull();

		String customerView = call(get("/api/npd/{id}", projectId), npdCustomerA, null).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(customerView).doesNotContain("Confirmed with our tool room");
		assertThat(customerView).doesNotContain("Waiting on our own steel supplier");

		// the supplier no longer reaches this activity at all now that it's been reassigned away from them
		call(post("/api/npd/activities/{id}/progress", activityId), npdSupplier, """
				{"status": "DONE", "issueFlag": false, "riskFlag": false}""").andExpect(status().isNotFound());
	}

	@Test
	void responsibleUserMustBelongToTheActivitysOwnOwnerCompany() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));

		// npdSupplier's own user belongs to `supplier`, not `customerA`: rejected even though the user exists
		call(post("/api/npd/{id}/activities", projectId), ergoNpd, """
				{"activityName": "Customer sign-off", "status": "NOT_STARTED", "mandatory": true, "riskFlag": false,
				 "issueFlag": false, "ownerOrganizationType": "CUSTOMER", "ownerCompanyId": %d,
				 "responsibleUserId": %d, "approvalRequired": false}"""
			.formatted(customerA, npdSupplier.userId())).andExpect(status().isUnprocessableContent());

		// the project's own customer's own user: accepted
		call(post("/api/npd/{id}/activities", projectId), ergoNpd, """
				{"activityName": "Customer sign-off", "status": "NOT_STARTED", "mandatory": true, "riskFlag": false,
				 "issueFlag": false, "ownerOrganizationType": "CUSTOMER", "ownerCompanyId": %d,
				 "responsibleUserId": %d, "approvalRequired": false}"""
			.formatted(customerA, npdCustomerA.userId())).andExpect(status().isCreated());
	}

	@Test
	void onlyCustomerNpdGivesTheCustomersFinalApprovalAndCustomerQualityOnlyActs() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		Login qualityCustomerA = users.login(customerA, "CUSTOMER_QUALITY");
		long sampleApprovalId = approvalId(projectId, "CUSTOMER_SAMPLE_APPROVAL");
		long milestoneApprovalId = approvalId(projectId, "CUSTOMER_MILESTONE_ACKNOWLEDGMENT");
		long qualityApprovalId = approvalId(projectId, "CUSTOMER_QUALITY_ACCEPTANCE");

		// role sheet: Customer Quality is "A" on NPD (acts, no final approval), Customer NPD is "CO"
		for (long approval : List.of(qualityApprovalId, sampleApprovalId, milestoneApprovalId)) {
			call(post("/api/npd/approvals/{id}/decide", approval), qualityCustomerA, "{\"approved\": true}")
				.andExpect(status().isForbidden());
		}

		// Customer NPD decides every customer-facing type, quality acceptance included
		call(post("/api/npd/approvals/{id}/decide", qualityApprovalId), npdCustomerA, "{\"approved\": true}")
			.andExpect(status().isOk());
		call(post("/api/npd/approvals/{id}/decide", sampleApprovalId), npdCustomerA, "{\"approved\": true}")
			.andExpect(status().isOk());
	}

	@Test
	void customerViewNeverContainsErgoOnlyApprovalTypesOnlyTheThreeCustomerFacingOnes() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		approvalId(projectId, "STAGE_GATE");
		approvalId(projectId, "QUALITY_GATE");
		approvalId(projectId, "CUSTOMER_SAMPLE_APPROVAL");

		String customerView = call(get("/api/npd/{id}", projectId), npdCustomerA, null).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(JsonPath.<List<String>>read(customerView, "$.approvals[*].approvalType"))
			.containsExactly("CUSTOMER_SAMPLE_APPROVAL");
	}

	@Test
	void customerQualityCanViewItsOwnProjectTooNotJustCustomerNpd() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		Login qualityCustomerA = users.login(customerA, "CUSTOMER_QUALITY");

		call(get("/api/npd/{id}", projectId), qualityCustomerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CUSTOMER"));
		call(get("/api/npd/mine"), qualityCustomerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value((int) projectId));
	}

	@Test
	void respondingToAPromptOnAnotherCompanysActivityIsRejected() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		long supplierActivityId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/activities", projectId), ergoNpd,
							ownedActivityForm("Tooling trial", "SUPPLIER", supplier)).andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.activities[?(@.activityName == 'Tooling trial')].id")
			.getFirst()
			.longValue();
		long checklistId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/prompt-checklists", projectId), ergoNpd, """
							{"activityId": %d, "questionText": "Confirmed?", "responseType": "YES_NO",
							 "mandatory": true, "sequenceNo": 1}""".formatted(supplierActivityId))
						.andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.activities[?(@.id == " + supplierActivityId + ")].promptChecklists[*].id")
			.getFirst()
			.longValue();

		// this project's own customer, not the activity's own (supplier's) company: rejected, not revealed
		call(post("/api/npd/prompt-checklists/{id}/response", checklistId), npdCustomerA,
				"{\"responseValue\": \"Yes\"}").andExpect(status().isNotFound());
		// a different supplier company entirely: rejected too
		call(post("/api/npd/prompt-checklists/{id}/response", checklistId), npdSupplierB,
				"{\"responseValue\": \"Yes\"}").andExpect(status().isNotFound());
		// the activity's own supplier: fine
		call(post("/api/npd/prompt-checklists/{id}/response", checklistId), npdSupplier,
				"{\"responseValue\": \"Yes\"}").andExpect(status().isOk());
	}

	@Test
	void blockAndUnblockOnAnotherCompanysActivityIsRejected() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		long supplierActivityId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/activities", projectId), ergoNpd,
							ownedActivityForm("Tooling trial", "SUPPLIER", supplier)).andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.activities[?(@.activityName == 'Tooling trial')].id")
			.getFirst()
			.longValue();

		call(post("/api/npd/activities/{id}/block", supplierActivityId), npdCustomerA, "{\"comment\": \"not mine\"}")
			.andExpect(status().isNotFound());
		call(post("/api/npd/activities/{id}/block", supplierActivityId), npdSupplier,
				"{\"comment\": \"waiting on steel\"}").andExpect(status().isOk());
		call(post("/api/npd/activities/{id}/unblock", supplierActivityId), npdCustomerA, "{}")
			.andExpect(status().isNotFound());
		call(post("/api/npd/activities/{id}/unblock", supplierActivityId), npdSupplier, "{}")
			.andExpect(status().isOk());
	}

	@Test
	void supplierQualityAndKamNotJustSupplierNpdCanUpdateItsOwnCompanysActivityButSupplierOfpHasNoNpdAccess()
			throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		Login ofpSupplier = users.login(supplier, "SUPPLIER_OFP");
		Login qualitySupplier = users.login(supplier, "SUPPLIER_QUALITY");
		long activityId = JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/activities", projectId), ergoNpd,
							ownedActivityForm("Tooling trial", "SUPPLIER", supplier)).andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.activities[?(@.activityName == 'Tooling trial')].id")
			.getFirst()
			.longValue();

		call(post("/api/npd/activities/{id}/progress", activityId), qualitySupplier, """
				{"status": "IN_PROGRESS", "issueFlag": false, "riskFlag": false}""").andExpect(status().isOk());
		// role sheet: Supplier OFP has no NPD access at all
		call(post("/api/npd/activities/{id}/progress", activityId), ofpSupplier, """
				{"status": "DONE", "issueFlag": false, "riskFlag": false}""").andExpect(status().isForbidden());
		call(get("/api/npd/{id}", projectId), ofpSupplier, null).andExpect(status().isForbidden());
	}

	@Test
	void externalRolesCannotUseErgoOnlyNpdEndpoints() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));
		for (Login outsider : List.of(npdCustomerA, npdSupplier)) {
			call(post("/api/npd/{id}/activities", projectId), outsider, activityForm("Sneak in", true))
				.andExpect(status().isForbidden());
			call(post("/api/npd/{id}/prompt-checklists", projectId), outsider, """
					{"questionText": "Sneaky?", "responseType": "YES_NO", "mandatory": true, "sequenceNo": 1}""")
				.andExpect(status().isForbidden());
			call(post("/api/npd/{id}/approvals", projectId), outsider, "{\"approvalType\": \"STAGE_GATE\"}")
				.andExpect(status().isForbidden());
			call(post("/api/npd/{id}/close-stage", projectId), outsider, "{}").andExpect(status().isForbidden());
		}
	}

	// ---------------------------------------------------------------- helpers

	/** Full RFQ->quotation flow up to CUSTOMER_APPROVED with one supplier's quote approved (SRS §14.2-14.4). */
	private long approvedRfq(Login buyer, String price) throws Exception {
		long id = JsonPath
			.<Number>read(call(post("/api/rfqs"), buyer, """
					{"partNumber": "PN-NPD", "description": "Bracket", "materialSpec": "EN8",
					 "targetQuoteDate": "%s", "currencyId": %d, "targetProductionPiecePrice": 99.5,
					 "processSteps": [{"stepName": "Casting"}]}""".formatted(LocalDate.now().plusDays(10), inr))
				.andReturn().getResponse().getContentAsString(), "$.id")
			.longValue();
		call(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		call(post("/api/rfqs/{id}/validate", id), ergoRfq, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/quotes", id), ergoRfq,
				"{\"supplierIds\": [%d], \"responseDueDate\": \"%s\"}".formatted(supplier, LocalDate.now().plusDays(7)))
			.andExpect(status().isOk());
		long invitationId = invitationOf(id, seller);
		call(put("/api/quotes/{id}", invitationId), seller, """
				{"validityDate": "%s", "currencyId": %d, "productionPiecePrice": %s, "toolingCostTotal": 50000,
				 "rawMaterialCost": 40, "processingCost": 20, "overheadCost": 8, "packagingCost": 2, "profit": 6,
				 "moq": 500, "productionLeadTimeDays": 45}""".formatted(LocalDate.now().plusDays(60), inr, price))
			.andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", invitationId), seller, null).andExpect(status().isOk());
		call(post("/api/quotes/{id}/decision", invitationId), ergoRfq, "{\"decision\": \"SELECT_FOR_CUSTOMER\"}")
			.andExpect(status().isOk());
		call(post("/api/rfqs/{id}/customer-quotes", id), ergoRfq,
				"{\"quoteId\": %d, \"type\": \"ERGO_QUOTE\", \"remarks\": \"ok\", \"ergoQuote\": {\"currencyId\": %d, \"productionPiecePrice\": 120, \"validityDate\": \"%s\"}}".formatted(invitationId, inr, java.time.LocalDate.now().plusDays(30)))
			.andExpect(status().isOk());
		long sent = JsonPath
			.<Number>read(
					call(get("/api/rfqs/{id}/customer-quotes", id), buyer, null).andReturn().getResponse()
						.getContentAsString(),
					"$.quotes[0].id")
			.longValue();
		call(post("/api/customer-quotes/{id}/respond", sent), buyer, "{\"response\": \"APPROVED\", \"comment\": \"Go ahead\"}")
			.andExpect(status().isOk());
		return id;
	}

	private long convert(long rfqId) throws Exception {
		return JsonPath
			.<Number>read(call(post("/api/npd/convert/{id}", rfqId), ergoNpd, "{}").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id")
			.longValue();
	}

	/** Repeatedly closes stages (none of these stages have any activity in these tests, so every mandatory list is
	 * empty and each close-stage call succeeds outright) until the project reaches CLOSED. Phase 5d: closing Final
	 * Sample Readiness additionally needs a real Quality sign-off, so one PASSED inspection is created against the
	 * project right before that particular close-stage call. */
	private void closeUntilClosed(long projectId) throws Exception {
		String stage;
		do {
			String current = JsonPath.read(
					call(get("/api/npd/{id}", projectId), ergoNpd, null).andReturn().getResponse().getContentAsString(),
					"$.stage");
			if (current.equals("FINAL_SAMPLE_READINESS")) {
				passNpdInspection(projectId);
			}
			stage = JsonPath.read(
					call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isOk())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.stage");
		}
		while (!stage.equals("CLOSED"));
	}

	// ---------------------------------------------------------------- Phase 5d: Quality inspection gate helper

	/** A fresh Quality inspection linked to this NPD project (SRS's "Final" inspection against it), driven straight
	 * through to PASSED - the simplest shape that satisfies the real Final Sample Readiness gate. Create/submit by
	 * ERGO Quality, Pass by ERGO Admin (a different user - the submitter may not decide its own inspection). */
	private void passNpdInspection(long projectId) throws Exception {
		long inspectionId = JsonPath
			.<Number>read(call(post("/api/quality/inspections"), ergoQuality, """
					{"linkedModule": "NPD", "linkedRecordId": %d, "partNumber": "PN-STD", "customerVisible": false}"""
				.formatted(projectId)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
					"$.id")
			.longValue();
		call(post("/api/quality/inspections/{id}/submit", inspectionId), ergoQuality, """
				{"partNumber": "PN-STD", "quantityChecked": 5, "quantityRejected": 0, "inspectionDate": "%s",
				 "customerVisible": false}""".formatted(LocalDate.now())).andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/pass", inspectionId), ergoManagement, "{}").andExpect(status().isOk());
	}

	private long invitationOf(long rfqId, Login supplierLogin) throws Exception {
		String number = JsonPath.read(call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andReturn().getResponse()
			.getContentAsString(), "$.number");
		List<Number> ids = JsonPath.read(call(get("/api/quotes"), supplierLogin, null).andReturn().getResponse()
			.getContentAsString(), "$[?(@.rfqNumber == '" + number + "')].id");
		return ids.get(0).longValue();
	}

	private static String activityForm(String name, boolean mandatory) {
		return activityFormWithStatus(name, mandatory, "NOT_STARTED");
	}

	private static String activityFormWithStatus(String name, boolean mandatory, String status) {
		return """
				{"activityName": "%s", "status": "%s", "mandatory": %s, "riskFlag": false, "issueFlag": false,
				 "ownerOrganizationType": "ERGO", "approvalRequired": false}"""
			.formatted(name, status, mandatory);
	}

	/** An ERGO/CUSTOMER/SUPPLIER-owned activity (SRS §17.4 ownership); {@code ownerCompanyId} may be null for ERGO
	 * (server-derived regardless of what's sent). */
	private static String ownedActivityForm(String name, String ownerOrganizationType, Long ownerCompanyId) {
		return """
				{"activityName": "%s", "status": "NOT_STARTED", "mandatory": true, "riskFlag": false,
				 "issueFlag": false, "ownerOrganizationType": "%s", "ownerCompanyId": %s, "approvalRequired": false}"""
			.formatted(name, ownerOrganizationType, ownerCompanyId == null ? "null" : ownerCompanyId);
	}

	/** An activity whose mandatory flag also requires an APPROVED approval of the given type before it counts
	 * toward Close Stage (M2: not just any approved approval linked to it). */
	private static String approvalGatedActivityForm(String name, String requiredApprovalType) {
		return approvalGatedActivityFormWithStatus(name, "NOT_STARTED", requiredApprovalType);
	}

	private static String approvalGatedActivityFormWithStatus(String name, String status,
			String requiredApprovalType) {
		return """
				{"activityName": "%s", "status": "%s", "mandatory": true, "riskFlag": false, "issueFlag": false,
				 "ownerOrganizationType": "ERGO", "approvalRequired": true, "requiredApprovalType": "%s"}"""
			.formatted(name, status, requiredApprovalType);
	}

	private static String issueForm(String type, String title, String ownerOrganizationType, long ownerCompanyId) {
		return """
				{"type": "%s", "title": "%s", "severity": "MEDIUM", "ownerOrganizationType": "%s",
				 "ownerCompanyId": %d}"""
			.formatted(type, title, ownerOrganizationType, ownerCompanyId);
	}

	/** Requests a fresh approval of the given type on this project and returns its id. */
	private long approvalId(long projectId, String approvalType) throws Exception {
		return JsonPath
			.<List<Number>>read(
					call(post("/api/npd/{id}/approvals", projectId), ergoNpd,
							"{\"approvalType\": \"%s\"}".formatted(approvalType)).andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.approvals[?(@.approvalType == '" + approvalType + "' && @.approvalStatus == 'PENDING')].id")
			.getFirst()
			.longValue();
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

}
