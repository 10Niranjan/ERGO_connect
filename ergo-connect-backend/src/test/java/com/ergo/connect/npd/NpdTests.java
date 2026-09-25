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

	long inr;

	Login ergoRfq;

	Login ergoAdmin;

	Login ergoNpd;

	Login buyerA;

	Login buyerB;

	Login seller;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		customerA = users.company("CUSTOMER");
		customerB = users.company("CUSTOMER");
		supplier = users.company("SUPPLIER");
		inr = users.currency("INR");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		ergoAdmin = users.login(ergo, "ERGO_ADMIN");
		ergoNpd = users.login(ergo, "ERGO_NPD");
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		buyerB = users.login(customerB, "CUSTOMER_RFQ");
		seller = users.login(supplier, "SUPPLIER_RFQ");
	}

	@Test
	void onlyAdminAndNpdConvertAndTheSameRfqCannotBeConvertedTwice() throws Exception {
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
		call(post("/api/npd/convert/{id}", rfqId), ergoAdmin, "{}").andExpect(status().isConflict());

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
		call(post("/api/npd/convert/{id}", id), ergoAdmin, "{}").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("approved customer quote")));
	}

	@Test
	void keyAccountManagersSeeOnlyTheirAssignedCustomersProjectsAndOfpQualityGetNothing() throws Exception {
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

		call(get("/api/npd"), ofp, null).andExpect(status().isForbidden());
		call(get("/api/npd"), quality, null).andExpect(status().isForbidden());
		call(get("/api/npd/conversion-queue"), ofp, null).andExpect(status().isForbidden());
		call(post("/api/npd/convert/{id}", projectA), quality, "{}").andExpect(status().isForbidden());

		// ERGO sees the supplier (its own internal view, SRS §12.8.1's own list column); no customer/supplier
		// role ever reaches this endpoint at all, checked above and in the RFQ-side anonymity tests
		String supplierName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?",
				String.class, supplier);
		call(get("/api/npd"), ergoNpd, null).andExpect(jsonPath("$[*].supplierName", hasItem(supplierName)));
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
		assertThat(blockedActions.getFirst()).containsExactly("EDIT_ACTIVITY", "UNBLOCK");

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
	void customerAndSupplierRolesReachNothingInThisPhase() throws Exception {
		long projectId = convert(approvedRfq(buyerA, "90"));

		for (Login outsider : List.of(buyerA, seller)) {
			call(get("/api/npd/conversion-queue"), outsider, null).andExpect(status().isForbidden());
			call(get("/api/npd"), outsider, null).andExpect(status().isForbidden());
			call(get("/api/npd/{id}", projectId), outsider, null).andExpect(status().isForbidden());
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
				"{\"quoteId\": %d, \"type\": \"SUPPLIER_QUOTE\", \"remarks\": \"ok\"}".formatted(invitationId))
			.andExpect(status().isOk());
		long sent = JsonPath
			.<Number>read(
					call(get("/api/rfqs/{id}/customer-quotes", id), buyer, null).andReturn().getResponse()
						.getContentAsString(),
					"$.quotes[0].id")
			.longValue();
		call(post("/api/customer-quotes/{id}/respond", sent), buyer, "{\"response\": \"APPROVED\"}")
			.andExpect(status().isOk());
		return id;
	}

	private long convert(long rfqId) throws Exception {
		return JsonPath
			.<Number>read(call(post("/api/npd/convert/{id}", rfqId), ergoNpd, "{}").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id")
			.longValue();
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
				{"activityName": "%s", "status": "%s", "mandatory": %s, "riskFlag": false, "issueFlag": false}"""
			.formatted(name, status, mandatory);
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

}
