package com.ergo.connect.npd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
import com.ergo.connect.TestNpd;
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;

/**
 * Flow doc step 8: the supplier fills in the plan ERGO NPD released to it and submits it, ERGO NPD agrees it or sends
 * it back with a comment, and only an agreed plan goes to execution. Who may do what in each plan state, what the
 * customer and the other supplier never see, the plan's trail, notifications and the progress numbers.
 */
@IntegrationTest
class PlanAgreementTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long ergo;

	long customerA;

	long supplier;

	long supplierB;

	long inr;

	Login ergoRfq;

	Login ergoNpd;

	Login ergoNpd2;

	Login ergoQuality;

	Login ergoManagement;

	Login kam;

	Login buyerA;

	Login npdCustomerA;

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
		supplier = users.company("SUPPLIER");
		supplierB = users.company("SUPPLIER");
		inr = users.currency("INR");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		ergoNpd = users.login(ergo, "ERGO_NPD");
		ergoNpd2 = users.login(ergo, "ERGO_NPD");
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		ergoManagement = users.login(ergo, "ERGO_MANAGEMENT");
		kam = users.login(ergo, "ERGO_KAM");
		users.assignAccounts(kam.userId(), customerA);
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		npdCustomerA = users.login(customerA, "CUSTOMER_NPD");
		seller = users.login(supplier, "SUPPLIER_RFQ");
		npdSupplier = users.login(supplier, "SUPPLIER_NPD");
		qualitySupplier = users.login(supplier, "SUPPLIER_QUALITY");
		ofpSupplier = users.login(supplier, "SUPPLIER_OFP");
		npdSupplierB = users.login(supplierB, "SUPPLIER_NPD");
	}

	// ---------------------------------------------------------------- the happy path and the trail

	@Test
	void theSupplierFillsInAndSubmitsTheStepsAndErgoNpdAgreesThenExecutionStarts() throws Exception {
		long project = released("PN-HAPPY");
		long step = firstActivity(project);
		assertThat(planStatus(project)).isEqualTo("ASSIGNED_TO_SUPPLIER");

		// the supplier's team can open the plan and fill in its step; the buttons say so
		call(get("/api/npd/{id}", project), npdSupplier, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.planStatus").value("ASSIGNED_TO_SUPPLIER"))
			.andExpect(jsonPath("$.actions", hasItem("SUBMIT_PLAN")))
			.andExpect(jsonPath("$.activities[0].actions", hasItem("EDIT_PLAN_STEP")))
			.andExpect(jsonPath("$.activities[0].actions", not(hasItem("UPDATE_OWN_ACTIVITY"))));
		call(put("/api/npd/activities/{id}/plan", step), npdSupplier, planStep("2027-03-15", npdSupplier.userId(), "Tool ready"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activities[0].plannedDate").value("2027-03-15"))
			.andExpect(jsonPath("$.activities[0].remarks").value("Tool ready"));

		// before the plan is agreed nobody can work a step
		call(post("/api/npd/activities/{id}/progress", step), npdSupplier, progress("IN_PROGRESS")).andExpect(status().isConflict());
		call(post("/api/npd/activities/{id}/progress", step), ergoNpd, progress("IN_PROGRESS")).andExpect(status().isConflict());
		call(post("/api/npd/activities/{id}/block", step), npdSupplier, "{\"comment\": \"x\"}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isConflict());

		// submit, then ERGO NPD agrees
		call(post("/api/npd/{id}/plan/submit", project), npdSupplier, "{\"comment\": \"Dates confirmed\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.planStatus").value("SUBMITTED"))
			.andExpect(jsonPath("$.actions", not(hasItem("SUBMIT_PLAN"))));
		call(get("/api/npd/{id}", project), ergoNpd, null)
			.andExpect(jsonPath("$.actions", hasItem("AGREE_PLAN")))
			.andExpect(jsonPath("$.actions", hasItem("SEND_BACK_PLAN")));
		call(post("/api/npd/{id}/plan/agree", project), ergoNpd, "{\"comment\": \"Looks good\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.planStatus").value("AGREED"))
			.andExpect(jsonPath("$.actions", not(hasItem("AGREE_PLAN"))));
		assertThat(planStatus(project)).isEqualTo("AGREED");

		// execution has started: the supplier works its step, ERGO closes the stage
		call(post("/api/npd/activities/{id}/progress", step), npdSupplier, progress("DONE")).andExpect(status().isOk());
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk())
			.andExpect(jsonPath("$.stage").value("COMMERCIAL_SOURCING_READINESS"));

		// the whole negotiation is in the trail, in order, with who and what was said
		call(get("/api/npd/{id}", project), ergoNpd, null)
			.andExpect(jsonPath("$.planHistory[*].event", contains("RELEASED", "SUBMITTED", "AGREED")))
			.andExpect(jsonPath("$.planHistory[1].comment").value("Dates confirmed"))
			.andExpect(jsonPath("$.planHistory[2].comment").value("Looks good"));
	}

	@Test
	void sendingThePlanBackNeedsACommentReturnsItToTheSupplierAndEveryRoundIsKept() throws Exception {
		long project = released("PN-BACK");
		long step = firstActivity(project);
		TestNpd.submitPlan(mvc, npdSupplier, project);

		// a comment is mandatory
		call(post("/api/npd/{id}/plan/send-back", project), ergoNpd, "{}").andExpect(status().isBadRequest());
		call(post("/api/npd/{id}/plan/send-back", project), ergoNpd, "{\"comment\": \"  \"}").andExpect(status().isBadRequest());
		assertThat(planStatus(project)).isEqualTo("SUBMITTED");

		call(post("/api/npd/{id}/plan/send-back", project), ergoNpd, "{\"comment\": \"Tooling date is too late\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.planStatus").value("CHANGES_REQUESTED"));
		assertThat(planStatus(project)).isEqualTo("CHANGES_REQUESTED");

		// the supplier sees why, fixes the date and submits again
		call(get("/api/npd/{id}", project), npdSupplier, null)
			.andExpect(jsonPath("$.planStatus").value("CHANGES_REQUESTED"))
			.andExpect(jsonPath("$.planHistory[?(@.event == 'SENT_BACK')].comment", contains("Tooling date is too late")))
			.andExpect(jsonPath("$.actions", hasItem("SUBMIT_PLAN")))
			.andExpect(jsonPath("$.activities[0].actions", hasItem("EDIT_PLAN_STEP")));
		call(put("/api/npd/activities/{id}/plan", step), npdSupplier, planStep("2027-01-10", null, null)).andExpect(status().isOk());
		TestNpd.submitPlan(mvc, npdSupplier, project);
		TestNpd.agreePlan(mvc, ergoNpd, project);

		assertThat(planStatus(project)).isEqualTo("AGREED");
		call(get("/api/npd/{id}", project), ergoNpd, null).andExpect(jsonPath("$.planHistory[*].event",
				contains("RELEASED", "SUBMITTED", "SENT_BACK", "SUBMITTED", "AGREED")));
		// the trail can never be rewritten
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> jdbc.update("UPDATE npd_plan_history SET comment = 'x' WHERE npd_project_id = ?", project))
			.isInstanceOf(org.springframework.dao.DataAccessException.class);
	}

	// ---------------------------------------------------------------- who may do what

	@Test
	void onlySupplierNpdSubmitsAndOnlyErgoNpdOwnersDecide() throws Exception {
		long project = released("PN-ROLES");

		// submit: not the other supplier roles, not another supplier, not the customer, not ERGO
		for (Login other : List.of(seller, qualitySupplier, ofpSupplier)) {
			call(post("/api/npd/{id}/plan/submit", project), other, "{}").andExpect(status().is4xxClientError());
		}
		call(post("/api/npd/{id}/plan/submit", project), npdSupplierB, "{}").andExpect(status().isNotFound());
		for (Login other : List.of(npdCustomerA, buyerA, ergoNpd, ergoManagement, kam)) {
			call(post("/api/npd/{id}/plan/submit", project), other, "{}").andExpect(status().is4xxClientError());
		}
		assertThat(planStatus(project)).isEqualTo("ASSIGNED_TO_SUPPLIER");

		// agree / send back: nothing to decide yet, whoever asks
		call(post("/api/npd/{id}/plan/agree", project), ergoNpd, "{}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/plan/send-back", project), ergoNpd, "{\"comment\": \"x\"}").andExpect(status().isConflict());

		TestNpd.submitPlan(mvc, npdSupplier, project);

		// submitted: the supplier, the KAM, the customer and other ERGO roles cannot decide it
		for (Login other : List.of(npdSupplier, qualitySupplier, npdCustomerA, ergoQuality, ergoRfq, kam)) {
			call(post("/api/npd/{id}/plan/agree", project), other, "{}").andExpect(status().is4xxClientError());
			call(post("/api/npd/{id}/plan/send-back", project), other, "{\"comment\": \"x\"}").andExpect(status().is4xxClientError());
		}
		call(post("/api/npd/{id}/plan/agree", project), npdSupplierB, "{}").andExpect(status().isForbidden());
		assertThat(planStatus(project)).isEqualTo("SUBMITTED");

		// Management (an owning role) may decide
		call(post("/api/npd/{id}/plan/agree", project), ergoManagement, "{}").andExpect(status().isOk());
		assertThat(planStatus(project)).isEqualTo("AGREED");

		// an agreed plan cannot be submitted, agreed or sent back again
		call(post("/api/npd/{id}/plan/submit", project), npdSupplier, "{}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/plan/agree", project), ergoNpd, "{}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/plan/send-back", project), ergoNpd, "{\"comment\": \"x\"}").andExpect(status().isConflict());
	}

	@Test
	void everyStepNeedsAPlannedDateBeforeTheSupplierCanSubmit() throws Exception {
		long project = TestNpd.start(mvc, npdCustomerA, kam, ergoNpd, ergoQuality, approvedPart("PN-DATES"));

		// ERGO leaves a supplier step without a date; the supplier has to give one
		call(post("/api/npd/{id}/plan/assign", project), ergoNpd, "{}").andExpect(status().isConflict()); // no supplier step yet
		call(post("/api/npd/{id}/activities", project), ergoNpd, undatedStep("Raw material arrives")).andExpect(status().isCreated());
		TestNpd.assignPlan(mvc, ergoNpd, project);
		long undated = jdbcStep(project, "Raw material arrives");

		call(post("/api/npd/{id}/plan/submit", project), npdSupplier, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail", containsString("Raw material arrives")));
		assertThat(planStatus(project)).isEqualTo("ASSIGNED_TO_SUPPLIER");

		// the date is required on the step form itself
		call(put("/api/npd/activities/{id}/plan", undated), npdSupplier, "{\"remarks\": \"soon\"}").andExpect(status().isBadRequest());
		call(put("/api/npd/activities/{id}/plan", undated), npdSupplier, planStep("2027-02-01", null, null)).andExpect(status().isOk());
		TestNpd.submitPlan(mvc, npdSupplier, project);
	}

	// ---------------------------------------------------------------- the supplier only fills in what is its own

	@Test
	void theSupplierCanOnlyFillInItsOwnStepsWhileThePlanIsWithIt() throws Exception {
		long project = released("PN-OWN");
		long supplierStep = firstActivity(project);
		call(post("/api/npd/{id}/activities", project), ergoNpd, ergoStep("ERGO internal review")).andExpect(status().isCreated());
		long ergoStep = jdbcStep(project, "ERGO internal review");

		// not an ERGO-owned step, not another supplier's team, not the customer
		call(put("/api/npd/activities/{id}/plan", ergoStep), npdSupplier, planStep("2027-03-01", null, null)).andExpect(status().isNotFound());
		call(put("/api/npd/activities/{id}/plan", supplierStep), npdSupplierB, planStep("2027-03-01", null, null)).andExpect(status().isNotFound());
		call(put("/api/npd/activities/{id}/plan", supplierStep), npdCustomerA, planStep("2027-03-01", null, null)).andExpect(status().isForbidden());
		// the view-only RFQ team reads the plan but does not fill it in
		call(put("/api/npd/activities/{id}/plan", supplierStep), seller, planStep("2027-03-01", null, null)).andExpect(status().is4xxClientError());
		// a responsible person must be a colleague of the supplier, not someone from another company
		call(put("/api/npd/activities/{id}/plan", supplierStep), npdSupplier, planStep("2027-03-01", ergoNpd.userId(), null))
			.andExpect(status().isUnprocessableContent());
		call(put("/api/npd/activities/{id}/plan", supplierStep), npdSupplier, planStep("2027-03-01", npdSupplierB.userId(), null))
			.andExpect(status().isUnprocessableContent());
		// the plan form changes dates, never the template: name and owner stay as ERGO set them
		call(put("/api/npd/activities/{id}/plan", supplierStep), npdSupplier, planStep("2027-03-01", qualitySupplier.userId(), "ok"))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT activity_name FROM npd_activities WHERE npd_activity_id = ?", String.class, supplierStep))
			.isEqualTo("Supplier plan step");

		// once submitted the plan is frozen for everyone: the supplier, and ERGO too until it sends it back
		TestNpd.submitPlan(mvc, npdSupplier, project);
		call(put("/api/npd/activities/{id}/plan", supplierStep), npdSupplier, planStep("2027-03-02", null, null)).andExpect(status().isConflict());
		call(put("/api/npd/activities/{id}", supplierStep), ergoNpd, ownedStep("Supplier plan step", "SUPPLIER", supplier))
			.andExpect(status().isConflict());
		call(post("/api/npd/{id}/activities", project), ergoNpd, ergoStep("Late addition")).andExpect(status().isConflict());
		call(get("/api/npd/{id}", project), ergoNpd, null).andExpect(jsonPath("$.actions", not(hasItem("ADD_ACTIVITY"))));

		// back with the supplier it can be filled in again, and ERGO may adjust the template
		call(post("/api/npd/{id}/plan/send-back", project), ergoNpd, "{\"comment\": \"Please re-check\"}").andExpect(status().isOk());
		call(put("/api/npd/activities/{id}/plan", supplierStep), npdSupplier, planStep("2027-03-02", null, null)).andExpect(status().isOk());
		call(post("/api/npd/{id}/activities", project), ergoNpd, ergoStep("Late addition")).andExpect(status().isCreated());
	}

	// ---------------------------------------------------------------- who sees the plan's trail

	@Test
	void theCustomerNeverSeesThePlanAndTheOtherSupplierNeverSeesTheProject() throws Exception {
		long project = released("PN-SEE");
		TestNpd.submitPlan(mvc, npdSupplier, project);
		call(post("/api/npd/{id}/plan/send-back", project), ergoNpd, "{\"comment\": \"Move the tooling date\"}").andExpect(status().isOk());

		call(get("/api/npd/{id}", project), npdCustomerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.planStatus").doesNotExist())
			.andExpect(jsonPath("$.planHistory").doesNotExist());
		String customerJson = call(get("/api/npd/{id}", project), npdCustomerA, null).andReturn().getResponse().getContentAsString();
		assertThat(customerJson).doesNotContain("Move the tooling date");
		call(get("/api/npd/{id}", project), npdSupplierB, null).andExpect(status().isNotFound());
		// the supplier's trail carries ERGO NPD's comment, not the customer
		call(get("/api/npd/{id}", project), npdSupplier, null)
			.andExpect(jsonPath("$.planHistory[?(@.event == 'SENT_BACK')].comment", contains("Move the tooling date")))
			.andExpect(jsonPath("$.customer").doesNotExist());
	}

	@Test
	void whatErgoTypesWhenAssigningOrAgreeingStaysInternalButTheSendBackReasonReachesTheSupplier() throws Exception {
		long project = TestNpd.start(mvc, npdCustomerA, kam, ergoNpd, ergoQuality, approvedPart("PN-INTERNAL"));
		TestNpd.addSupplierStep(mvc, ergoNpd, project, supplier, "Tooling", true);
		call(post("/api/npd/{id}/plan/assign", project), ergoNpd, "{\"comment\": \"Acme needs samples by 12 Jan, stay under target\"}")
			.andExpect(status().isOk());
		call(post("/api/npd/{id}/plan/submit", project), npdSupplier, "{\"comment\": \"Dates are firm\"}").andExpect(status().isOk());
		call(post("/api/npd/{id}/plan/send-back", project), ergoNpd, "{\"comment\": \"Move tooling earlier\"}").andExpect(status().isOk());
		call(post("/api/npd/{id}/plan/submit", project), npdSupplier, "{}").andExpect(status().isOk());
		call(post("/api/npd/{id}/plan/agree", project), ergoNpd, "{\"comment\": \"Customer price is tight, fine\"}").andExpect(status().isOk());

		String supplierJson = call(get("/api/npd/{id}", project), npdSupplier, null).andReturn().getResponse().getContentAsString();
		assertThat(supplierJson).doesNotContain("Acme needs samples").doesNotContain("Customer price is tight");
		assertThat(supplierJson).contains("Dates are firm").contains("Move tooling earlier");
		call(get("/api/npd/{id}", project), npdSupplier, null)
			.andExpect(jsonPath("$.planHistory[*].event", contains("RELEASED", "SUBMITTED", "SENT_BACK", "SUBMITTED", "AGREED")))
			.andExpect(jsonPath("$.planHistory[0].comment").doesNotExist());
		// ERGO keeps the whole trail
		String ergoJson = call(get("/api/npd/{id}", project), ergoNpd, null).andReturn().getResponse().getContentAsString();
		assertThat(ergoJson).contains("Acme needs samples").contains("Customer price is tight");
	}

	@Test
	void ergoCannotStartOrFinishAStepThroughTheTemplateFormBeforeThePlanIsAgreed() throws Exception {
		long project = released("PN-SECOND-DOOR");
		long step = firstActivity(project);
		String done = ownedStep("Supplier plan step", "SUPPLIER", supplier).replace("NOT_STARTED", "DONE");
		String withActual = ownedStep("Supplier plan step", "SUPPLIER", supplier).replace("\"plannedDate\"",
				"\"actualDate\": \"" + LocalDate.now() + "\", \"plannedDate\"");

		call(put("/api/npd/activities/{id}", step), ergoNpd, done).andExpect(status().isUnprocessableContent());
		call(put("/api/npd/activities/{id}", step), ergoNpd, withActual).andExpect(status().isUnprocessableContent());
		call(post("/api/npd/{id}/activities", project), ergoNpd, done.replace("Supplier plan step", "Extra")).andExpect(status().isUnprocessableContent());
		assertThat(jdbc.queryForObject("SELECT status FROM npd_activities WHERE npd_activity_id = ?", String.class, step))
			.isEqualTo("NOT_STARTED");

		// once agreed the template form works as before
		TestNpd.submitPlan(mvc, npdSupplier, project);
		TestNpd.agreePlan(mvc, ergoNpd, project);
		call(put("/api/npd/activities/{id}", step), ergoNpd, done).andExpect(status().isOk());
	}

	@Test
	void checklistsApprovalsAndAnswersWaitForTheAgreedPlan() throws Exception {
		long project = released("PN-WAIT");
		long step = firstActivity(project);
		String checklist = "{\"activityId\": %d, \"questionText\": \"Tool steel confirmed?\", \"responseType\": \"YES_NO\", \"mandatory\": true, \"sequenceNo\": 1}"
			.formatted(step);
		call(post("/api/npd/{id}/prompt-checklists", project), ergoNpd, checklist).andExpect(status().isCreated());
		long question = jdbc.queryForObject("SELECT max(npd_prompt_checklist_id) FROM npd_prompt_checklists WHERE npd_activity_id = ?", Long.class, step);

		// nobody answers, unblocks or blocks before the plan is agreed; sample/quality approvals cannot be requested yet
		call(post("/api/npd/prompt-checklists/{id}/response", question), npdSupplier, "{\"responseValue\": \"Yes\"}").andExpect(status().isConflict());
		call(post("/api/npd/activities/{id}/unblock", step), npdSupplier, "{}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/approvals", project), ergoNpd, "{\"approvalType\": \"CUSTOMER_SAMPLE_APPROVAL\"}").andExpect(status().isConflict());

		// while ERGO reviews the submitted plan the questions are frozen too
		TestNpd.submitPlan(mvc, npdSupplier, project);
		call(post("/api/npd/{id}/prompt-checklists", project), ergoNpd, checklist).andExpect(status().isConflict());

		TestNpd.agreePlan(mvc, ergoNpd, project);
		call(post("/api/npd/prompt-checklists/{id}/response", question), npdSupplier, "{\"responseValue\": \"Yes\"}").andExpect(status().isOk());
		call(post("/api/npd/{id}/approvals", project), ergoNpd, "{\"approvalType\": \"CUSTOMER_SAMPLE_APPROVAL\"}").andExpect(status().isCreated());
	}

	@Test
	void theSupplierKamCanFillInStepsButOnlySupplierNpdSubmits() throws Exception {
		long project = released("PN-KAM-SUP");
		Login supplierKam = users.login(supplier, "SUPPLIER_KAM");
		call(put("/api/npd/activities/{id}/plan", firstActivity(project)), supplierKam, planStep("2027-04-01", null, null)).andExpect(status().isOk());
		call(post("/api/npd/{id}/plan/submit", project), supplierKam, "{}").andExpect(status().isConflict());
		call(get("/api/npd/{id}", project), supplierKam, null).andExpect(jsonPath("$.actions", not(hasItem("SUBMIT_PLAN"))));
		assertThat(planStatus(project)).isEqualTo("ASSIGNED_TO_SUPPLIER");
	}

	// ---------------------------------------------------------------- notifications

	@Test
	void everyoneWhoMustActHearsOfEachPlanStepAndNoOneElseDoes() throws Exception {
		long project = released("PN-NOTIFY");

		// submitted: the project's ERGO NPD owner and the owning roles that can decide it, nobody else
		TestNpd.submitPlan(mvc, npdSupplier, project);
		assertThat(heard(ergoNpd, "NPD_PLAN_SUBMITTED", project)).isEqualTo(1);
		assertThat(heard(ergoNpd2, "NPD_PLAN_SUBMITTED", project)).isEqualTo(1);
		assertThat(heard(ergoManagement, "NPD_PLAN_SUBMITTED", project)).isEqualTo(1);
		for (Login not : List.of(ergoQuality, kam, npdSupplier, npdCustomerA, npdSupplierB)) {
			assertThat(heard(not, "NPD_PLAN_SUBMITTED", project)).isZero();
		}

		// sent back: the supplier's NPD team only
		call(post("/api/npd/{id}/plan/send-back", project), ergoNpd, "{\"comment\": \"Fix it\"}").andExpect(status().isOk());
		assertThat(heard(npdSupplier, "NPD_PLAN_RETURNED", project)).isEqualTo(1);
		for (Login not : List.of(seller, qualitySupplier, ofpSupplier, npdSupplierB, npdCustomerA, ergoNpd, ergoQuality)) {
			assertThat(heard(not, "NPD_PLAN_RETURNED", project)).isZero();
		}

		// agreed: the supplier's NPD, RFQ and Quality teams
		TestNpd.submitPlan(mvc, npdSupplier, project);
		TestNpd.agreePlan(mvc, ergoNpd, project);
		for (Login team : List.of(npdSupplier, seller, qualitySupplier)) {
			assertThat(heard(team, "NPD_PLAN_AGREED", project)).isEqualTo(1);
		}
		for (Login not : List.of(ofpSupplier, npdSupplierB, npdCustomerA, ergoNpd2)) {
			assertThat(heard(not, "NPD_PLAN_AGREED", project)).isZero();
		}
	}

	// ---------------------------------------------------------------- progress

	@Test
	void progressCountsDoneActivitiesPerAudienceAndPerStage() throws Exception {
		long project = TestNpd.start(mvc, npdCustomerA, kam, ergoNpd, ergoQuality, approvedPart("PN-PROGRESS"));
		call(post("/api/npd/{id}/activities", project), ergoNpd, ergoStep("ERGO review")).andExpect(status().isCreated());
		long supplierStep = TestNpd.addSupplierStep(mvc, ergoNpd, project, supplier, "Tooling", true);
		long supplierStep2 = TestNpd.addSupplierStep(mvc, ergoNpd, project, supplier, "Samples", true);
		TestNpd.assignPlan(mvc, ergoNpd, project);
		TestNpd.submitPlan(mvc, npdSupplier, project);
		TestNpd.agreePlan(mvc, ergoNpd, project);

		call(get("/api/npd/{id}", project), ergoNpd, null)
			.andExpect(jsonPath("$.progress.total").value(3))
			.andExpect(jsonPath("$.progress.done").value(0))
			.andExpect(jsonPath("$.progress.percent").value(0))
			.andExpect(jsonPath("$.progress.stages[?(@.stage == 'PLANNING')].state", contains("CURRENT")))
			.andExpect(jsonPath("$.progress.stages[?(@.stage == 'PLANNING')].total", contains(3)));

		call(post("/api/npd/activities/{id}/progress", supplierStep), npdSupplier, progress("DONE")).andExpect(status().isOk());
		call(get("/api/npd/{id}", project), ergoNpd, null)
			.andExpect(jsonPath("$.progress.done").value(1))
			.andExpect(jsonPath("$.progress.percent").value(33));
		// the supplier sees progress over its own steps (2) only; the customer owns none
		call(get("/api/npd/{id}", project), npdSupplier, null)
			.andExpect(jsonPath("$.progress.total").value(2))
			.andExpect(jsonPath("$.progress.done").value(1))
			.andExpect(jsonPath("$.progress.percent").value(50));
		call(get("/api/npd/{id}", project), npdCustomerA, null)
			.andExpect(jsonPath("$.progress.total").value(0))
			.andExpect(jsonPath("$.progress.percent").value(0));

		call(post("/api/npd/activities/{id}/progress", supplierStep2), npdSupplier, progress("DONE")).andExpect(status().isOk());
		long ergoStep = jdbcStep(project, "ERGO review");
		call(post("/api/npd/activities/{id}/progress", ergoStep), ergoNpd, progress("DONE")).andExpect(status().isOk());
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());
		call(get("/api/npd/{id}", project), ergoNpd, null)
			.andExpect(jsonPath("$.progress.percent").value(100))
			.andExpect(jsonPath("$.progress.stages[?(@.stage == 'PLANNING')].state", contains("COMPLETED")))
			.andExpect(jsonPath("$.progress.stages[?(@.stage == 'COMMERCIAL_SOURCING_READINESS')].state", contains("CURRENT")))
			.andExpect(jsonPath("$.progress.stages[?(@.stage == 'CLOSED')]", empty()));
	}

	// ---------------------------------------------------------------- projects from before the plan flow

	@Test
	void projectsThatAlreadyRanBeforeThePlanFlowCountAsAgreed() throws Exception {
		long project = released("PN-LEGACY");
		jdbc.update("UPDATE npd_projects SET plan_status = 'AGREED' WHERE npd_project_id = ?", project);
		long step = firstActivity(project);
		call(post("/api/npd/activities/{id}/progress", step), npdSupplier, progress("IN_PROGRESS")).andExpect(status().isOk());
		call(get("/api/npd/{id}", project), npdSupplier, null).andExpect(jsonPath("$.planStatus").value("AGREED"))
			.andExpect(jsonPath("$.actions", not(hasItem("SUBMIT_PLAN"))))
			.andExpect(jsonPath("$.planHistory").value(hasSize(1)));
	}

	// ---------------------------------------------------------------- helpers

	/** A project whose plan is released to the supplier (one dated supplier step). */
	private long released(String partNumber) throws Exception {
		return TestNpd.release(mvc, npdCustomerA, kam, ergoNpd, ergoQuality, supplier, approvedPart(partNumber));
	}

	/** A part of the customer's RFQ whose quotation the customer approved; returns the part (RFQ item) id. */
	private long approvedPart(String partNumber) throws Exception {
		long id = TestRfq.create(mvc, buyerA, TestRfq.form(inr, partNumber));
		call(post("/api/rfqs/{id}/submit", id), buyerA, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		TestRfq.approveWhole(mvc, buyerA, ergoRfq, seller, supplier, inr, id, "90");
		return TestRfq.itemIds(mvc, ergoRfq, id).getFirst();
	}

	private static String planStep(String plannedDate, Long responsibleUserId, String remarks) {
		return "{\"plannedDate\": \"%s\", \"responsibleUserId\": %s, \"remarks\": %s}".formatted(plannedDate,
				responsibleUserId == null ? "null" : responsibleUserId, remarks == null ? "null" : "\"" + remarks + "\"");
	}

	private static String progress(String status) {
		return "{\"status\": \"%s\", \"issueFlag\": false, \"riskFlag\": false}".formatted(status);
	}

	private String ergoStep(String name) {
		return ownedStep(name, "ERGO", ergo);
	}

	private static String ownedStep(String name, String ownerType, Long ownerCompanyId) {
		return """
				{"activityName": "%s", "status": "NOT_STARTED", "mandatory": true, "riskFlag": false, "issueFlag": false,
				 "ownerOrganizationType": "%s", "ownerCompanyId": %s, "approvalRequired": false,
				 "plannedDate": "%s"}"""
			.formatted(name, ownerType, ownerCompanyId, LocalDate.now().plusDays(10));
	}

	/** A supplier-owned step with no planned date: the supplier has to give one. */
	private String undatedStep(String name) {
		return """
				{"activityName": "%s", "status": "NOT_STARTED", "mandatory": true, "riskFlag": false, "issueFlag": false,
				 "ownerOrganizationType": "SUPPLIER", "ownerCompanyId": %d, "approvalRequired": false}"""
			.formatted(name, supplier);
	}

	private long firstActivity(long project) {
		return jdbc.queryForObject("SELECT min(npd_activity_id) FROM npd_activities WHERE npd_project_id = ?", Long.class,
				project);
	}

	/** The activity of that name, or 0 when the project has none (yet). */
	private long jdbcStep(long project, String name) {
		return jdbc.queryForObject(
				"SELECT coalesce(min(npd_activity_id), 0) FROM npd_activities WHERE npd_project_id = ? AND activity_name = ?",
				Long.class, project, name);
	}

	private String planStatus(long project) {
		return jdbc.queryForObject("SELECT plan_status FROM npd_projects WHERE npd_project_id = ?", String.class, project);
	}

	private long heard(Login user, String kind, long record) {
		return jdbc.queryForObject("""
				SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_kind = ? AND linked_module = 'npd'
				AND linked_record_id = ?""", Long.class, user.userId(), kind, record);
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

}
