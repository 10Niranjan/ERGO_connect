package com.ergo.connect.npd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.jayway.jsonpath.JsonPath;

/**
 * Flow doc steps 9-10: Supplier Quality approves internally and submits, ERGO Quality and Supplier Quality both
 * approve, the customer's NPD and Quality teams both approve; a rejection sends the project back with the reason. Who
 * may decide what, what each audience is allowed to read of the quality history, the stage gates and notifications.
 */
@IntegrationTest
class ValidationFlowTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long ergo;

	long customerA;

	long customerB;

	long supplier;

	long supplierB;

	long inr;

	Login ergoRfq;

	Login ergoNpd;

	Login ergoNpd2;

	Login ergoQuality;

	Login ergoQuality2;

	Login ergoManagement;

	Login kam;

	Login buyerA;

	Login npdCustomerA;

	Login qualityCustomerA;

	Login npdCustomerB;

	Login seller;

	Login npdSupplier;

	Login qualitySupplier;

	Login qualitySupplier2;

	Login qualitySupplierB;

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
		ergoNpd = users.login(ergo, "ERGO_NPD");
		ergoNpd2 = users.login(ergo, "ERGO_NPD");
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		ergoQuality2 = users.login(ergo, "ERGO_QUALITY");
		ergoManagement = users.login(ergo, "ERGO_MANAGEMENT");
		kam = users.login(ergo, "ERGO_KAM");
		users.assignAccounts(kam.userId(), customerA);
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		npdCustomerA = users.login(customerA, "CUSTOMER_NPD");
		qualityCustomerA = users.login(customerA, "CUSTOMER_QUALITY");
		npdCustomerB = users.login(customerB, "CUSTOMER_NPD");
		seller = users.login(supplier, "SUPPLIER_RFQ");
		npdSupplier = users.login(supplier, "SUPPLIER_NPD");
		qualitySupplier = users.login(supplier, "SUPPLIER_QUALITY");
		qualitySupplier2 = users.login(supplier, "SUPPLIER_QUALITY");
		qualitySupplierB = users.login(supplierB, "SUPPLIER_QUALITY");
	}

	// ---------------------------------------------------------------- the whole loop

	@Test
	void supplierQualitySubmitsBothQualityTeamsApproveThenBothCustomerTeamsAndThePartMovesOn() throws Exception {
		long project = atFinalSampleReadiness("PN-LOOP");
		passInspection(project);

		// the buttons: only Supplier Quality may submit
		call(get("/api/npd/{id}", project), qualitySupplier, null).andExpect(jsonPath("$.actions", hasItem("SUBMIT_VALIDATION")));
		for (Login other : List.of(npdSupplier, seller, ergoQuality, ergoNpd, npdCustomerA, qualityCustomerA)) {
			call(get("/api/npd/{id}", project), other, null).andExpect(jsonPath("$.actions", not(hasItem("SUBMIT_VALIDATION"))));
		}
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{\"comment\": \"Internal checks passed\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.validation.status").value("IN_VALIDATION"))
			.andExpect(jsonPath("$.validation.round").value(1));

		// Supplier Quality and ERGO Quality each get their button, once
		call(get("/api/npd/{id}", project), ergoQuality, null).andExpect(jsonPath("$.actions", hasItem("DECIDE_VALIDATION")));
		call(get("/api/npd/{id}", project), qualitySupplier2, null).andExpect(jsonPath("$.actions", hasItem("DECIDE_VALIDATION")))
			.andExpect(jsonPath("$.actions", not(hasItem("SUBMIT_VALIDATION"))));
		call(post("/api/npd/{id}/validation/decide", project), ergoQuality, "{\"approved\": true, \"comment\": \"Within tolerance\"}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.validation.status").value("IN_VALIDATION"));
		call(get("/api/npd/{id}", project), ergoQuality, null).andExpect(jsonPath("$.actions", not(hasItem("DECIDE_VALIDATION"))));

		// ERGO Quality alone is not enough: the stage cannot close and the customer has nothing to review
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/validation/decide", project), qualitySupplier2, "{\"approved\": true}").andExpect(status().isOk())
			.andExpect(jsonPath("$.validation.status").value("VALIDATED"));
		call(get("/api/npd/{id}", project), npdCustomerA, null).andExpect(jsonPath("$.validation.status").value("NOT_STARTED"))
			.andExpect(jsonPath("$.actions", not(hasItem("DECIDE_VALIDATION"))));

		// closing Final Sample Readiness submits the project to the customer
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk())
			.andExpect(jsonPath("$.stage").value("CUSTOMER_FEEDBACK"))
			.andExpect(jsonPath("$.validation.status").value("IN_CUSTOMER_REVIEW"));
		call(get("/api/npd/{id}", project), npdCustomerA, null).andExpect(jsonPath("$.validation.status").value("IN_CUSTOMER_REVIEW"))
			.andExpect(jsonPath("$.actions", hasItem("DECIDE_VALIDATION")));
		call(get("/api/npd/{id}", project), qualityCustomerA, null).andExpect(jsonPath("$.actions", hasItem("DECIDE_VALIDATION")));
		// the supplier-side and ERGO teams no longer decide; Customer Feedback cannot close yet
		call(post("/api/npd/{id}/validation/decide", project), ergoQuality2, "{\"approved\": true}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isConflict());

		// both customer teams must approve
		call(post("/api/npd/{id}/validation/decide", project), npdCustomerA, "{\"approved\": true, \"comment\": \"Samples fit\"}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.validation.status").value("IN_CUSTOMER_REVIEW"));
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/validation/decide", project), qualityCustomerA, "{\"approved\": true}").andExpect(status().isOk())
			.andExpect(jsonPath("$.validation.status").value("CUSTOMER_APPROVED"));
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk())
			.andExpect(jsonPath("$.stage").value("APPROVED_FOR_PRODUCTION_HANDOVER"));

		// the quality history, in order, for ERGO
		call(get("/api/npd/{id}", project), ergoNpd, null)
			.andExpect(jsonPath("$.validation.decisions[*].party",
					contains("SUPPLIER_INTERNAL", "ERGO_QUALITY", "SUPPLIER_QUALITY", "CUSTOMER_NPD", "CUSTOMER_QUALITY")))
			.andExpect(jsonPath("$.validation.decisions[1].comment").value("Within tolerance"));
	}

	// ---------------------------------------------------------------- who may do what, and when

	@Test
	void onlyTheRightPartyDecidesOnceAndOnlyInItsOwnPhase() throws Exception {
		long project = atFinalSampleReadiness("PN-PARTY");
		passInspection(project);

		// nothing to decide before the submission
		call(post("/api/npd/{id}/validation/decide", project), ergoQuality, "{\"approved\": true}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/validation/decide", project), qualitySupplier2, "{\"approved\": true}").andExpect(status().isConflict());

		// submit: Supplier Quality only; another supplier's team does not even see the project
		for (Login other : List.of(npdSupplier, seller, ergoQuality, ergoNpd, ergoManagement, kam, npdCustomerA)) {
			call(post("/api/npd/{id}/validation/submit", project), other, "{}").andExpect(status().is4xxClientError());
		}
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplierB, "{}").andExpect(status().isNotFound());
		assertThat(validationStatus(project)).isEqualTo("NOT_STARTED");
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isOk());
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isConflict());

		// decide: not the supplier's NPD team, not ERGO NPD / Management / KAM, not the customer (yet), not another supplier
		for (Login other : List.of(npdSupplier, ergoNpd, ergoManagement, kam, npdCustomerA, qualityCustomerA)) {
			call(post("/api/npd/{id}/validation/decide", project), other, "{\"approved\": true}").andExpect(status().is4xxClientError());
		}
		call(post("/api/npd/{id}/validation/decide", project), qualitySupplierB, "{\"approved\": true}").andExpect(status().isNotFound());
		call(post("/api/npd/{id}/validation/decide", project), npdCustomerB, "{\"approved\": true}").andExpect(status().isNotFound());

		// once per round, per party (a second ERGO Quality colleague does not get a second vote either)
		call(post("/api/npd/{id}/validation/decide", project), ergoQuality, "{\"approved\": true}").andExpect(status().isOk());
		call(post("/api/npd/{id}/validation/decide", project), ergoQuality, "{\"approved\": true}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/validation/decide", project), ergoQuality2, "{\"approved\": true}").andExpect(status().isConflict());
		assertThat(count("SELECT count(*) FROM npd_validation_decisions WHERE npd_project_id = ? AND party = 'ERGO_QUALITY'", project))
			.isEqualTo(1);
		// the history cannot be rewritten
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> jdbc.update("UPDATE npd_validation_decisions SET comment = 'x' WHERE npd_project_id = ?", project))
			.isInstanceOf(org.springframework.dao.DataAccessException.class);
	}

	@Test
	void submissionNeedsFinalSampleReadinessAnAgreedPlanAndFinishedMandatorySteps() throws Exception {
		long project = TestNpd.agreed(mvc, npdCustomerA, kam, ergoNpd, ergoQuality, npdSupplier, supplier, approvedPart("PN-EARLY"));

			// too early: the project is still in Planning
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isConflict());

		// at Final Sample Readiness, a mandatory step of the stage still open blocks the submission, then it goes through
		for (int i = 0; i < 5; i++) {
			call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());
		}
		call(post("/api/npd/{id}/activities", project), ergoNpd, ergoStep("Final fit check", "NOT_STARTED")).andExpect(status().isCreated());
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Final fit check")));
		assertThat(validationStatus(project)).isEqualTo("NOT_STARTED");
		long step = jdbc.queryForObject("SELECT npd_activity_id FROM npd_activities WHERE npd_project_id = ? AND activity_name = 'Final fit check'", Long.class, project);
		call(post("/api/npd/activities/{id}/progress", step), ergoNpd, "{\"status\": \"DONE\", \"issueFlag\": false, \"riskFlag\": false}")
			.andExpect(status().isOk());
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isOk());
	}

	@Test
	void nothingIsSubmittedWhileThePlanIsNotAgreed() throws Exception {
		long project = TestNpd.release(mvc, npdCustomerA, kam, ergoNpd, ergoQuality, supplier, approvedPart("PN-NOPLAN"));
		jdbc.update("UPDATE npd_projects SET stage = 'FINAL_SAMPLE_READINESS' WHERE npd_project_id = ?", project);
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isConflict());
		assertThat(validationStatus(project)).isEqualTo("NOT_STARTED");
	}

	// ---------------------------------------------------------------- rejection: ERGO Quality or Supplier Quality

	@Test
	void ergoQualityRejectingSendsTheProjectBackToADevelopmentStageWithTheReasonAndStartsANewRound() throws Exception {
		long project = TestNpd.agreed(mvc, npdCustomerA, kam, ergoNpd, ergoQuality, npdSupplier, supplier, approvedPart("PN-REWORK"));
		call(post("/api/npd/{id}/activities", project), ergoNpd, ergoStep("Trial run", "NOT_STARTED", "TRIAL_EXECUTION")).andExpect(status().isCreated());
		long trial = jdbc.queryForObject("SELECT npd_activity_id FROM npd_activities WHERE npd_project_id = ? AND activity_name = 'Trial run'", Long.class, project);
		call(post("/api/npd/activities/{id}/progress", trial), ergoNpd, "{\"status\": \"DONE\", \"issueFlag\": false, \"riskFlag\": false}")
			.andExpect(status().isOk());
		for (int i = 0; i < 5; i++) {
			call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());
		}
		assertThat(stage(project)).isEqualTo("FINAL_SAMPLE_READINESS");
		passInspection(project);
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isOk());

		// a rejection needs a reason and a development stage to go back to
		call(post("/api/npd/{id}/validation/decide", project), ergoQuality, "{\"approved\": false, \"returnStage\": \"TRIAL_EXECUTION\"}")
			.andExpect(status().isUnprocessableContent());
		call(post("/api/npd/{id}/validation/decide", project), ergoQuality, "{\"approved\": false, \"comment\": \"Bore too tight\"}")
			.andExpect(status().isUnprocessableContent());
		for (String bad : List.of("FINAL_SAMPLE_READINESS", "CUSTOMER_FEEDBACK", "CLOSED", "PENDING_CONVERSION")) {
			call(post("/api/npd/{id}/validation/decide", project), ergoQuality,
					"{\"approved\": false, \"comment\": \"Bore too tight\", \"returnStage\": \"%s\"}".formatted(bad))
				.andExpect(status().isUnprocessableContent());
		}
		assertThat(validationStatus(project)).isEqualTo("IN_VALIDATION");

		call(post("/api/npd/{id}/validation/decide", project), ergoQuality,
				"{\"approved\": false, \"comment\": \"Bore too tight on sample 3\", \"returnStage\": \"TRIAL_EXECUTION\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stage").value("TRIAL_EXECUTION"))
			.andExpect(jsonPath("$.validation.status").value("NOT_STARTED"))
			.andExpect(jsonPath("$.validation.round").value(2));
		// the finished step of that stage is back in progress, the stage history says why, the stage cannot close until it is redone
		assertThat(jdbc.queryForObject("SELECT status FROM npd_activities WHERE npd_activity_id = ?", String.class, trial)).isEqualTo("IN_PROGRESS");
		call(get("/api/npd/{id}", project), ergoNpd, null)
			.andExpect(jsonPath("$.stageHistory[-1:].remarks", contains("Sent back by quality validation: Bore too tight on sample 3")));
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isConflict());
		call(get("/api/npd/{id}", project), qualitySupplier, null).andExpect(jsonPath("$.actions", not(hasItem("DECIDE_VALIDATION"))));

		// the supplier reads the reason (it has to rework); the customer sees nothing of it
		String supplierJson = call(get("/api/npd/{id}", project), npdSupplier, null).andReturn().getResponse().getContentAsString();
		assertThat(supplierJson).contains("Bore too tight on sample 3");
		String customerJson = call(get("/api/npd/{id}", project), npdCustomerA, null).andReturn().getResponse().getContentAsString();
		assertThat(customerJson).doesNotContain("Bore too tight").doesNotContain("ERGO_QUALITY").doesNotContain("SUPPLIER_INTERNAL");
		call(get("/api/npd/{id}", project), npdCustomerA, null).andExpect(jsonPath("$.validation.decisions", empty()));

		// rework, back to Final Sample Readiness, a new submission in round 2: the earlier decisions stay in the history
		call(post("/api/npd/activities/{id}/progress", trial), npdSupplier, "{\"status\": \"DONE\", \"issueFlag\": false, \"riskFlag\": false}")
			.andExpect(status().is4xxClientError()); // it is ERGO's step, the supplier cannot work it
		call(post("/api/npd/activities/{id}/progress", trial), ergoNpd, "{\"status\": \"DONE\", \"issueFlag\": false, \"riskFlag\": false}")
			.andExpect(status().isOk());
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());
		assertThat(stage(project)).isEqualTo("FINAL_SAMPLE_READINESS");
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isOk());
		call(post("/api/npd/{id}/validation/decide", project), ergoQuality, "{\"approved\": true}").andExpect(status().isOk());
		call(post("/api/npd/{id}/validation/decide", project), qualitySupplier2, "{\"approved\": true}").andExpect(status().isOk())
			.andExpect(jsonPath("$.validation.status").value("VALIDATED"));
		call(get("/api/npd/{id}", project), ergoNpd, null)
			.andExpect(jsonPath("$.validation.decisions[*].round", contains(1, 1, 2, 2, 2)))
			.andExpect(jsonPath("$.validation.decisions[1].decision").value("REJECTED"));
	}

	@Test
	void supplierQualityCanRejectToo() throws Exception {
		long project = atFinalSampleReadiness("PN-SUP-REJECT");
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isOk());
		call(post("/api/npd/{id}/validation/decide", project), qualitySupplier2,
				"{\"approved\": false, \"comment\": \"Our own re-check failed\", \"returnStage\": \"PLANNING\"}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("PLANNING"))
			.andExpect(jsonPath("$.validation.round").value(2));
	}

	// ---------------------------------------------------------------- rejection: the customer

	@Test
	void aCustomerRejectionSendsItBackToFinalSampleReadinessAndTheSupplierNeverReadsTheCustomersWords() throws Exception {
		long project = atFinalSampleReadiness("PN-CUST-REJECT");
		passInspection(project);
		TestNpd.validate(mvc, qualitySupplier, qualitySupplier2, ergoQuality, project);
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());

		call(post("/api/npd/{id}/validation/decide", project), npdCustomerA, "{\"approved\": false}").andExpect(status().isUnprocessableContent());
		call(post("/api/npd/{id}/validation/decide", project), npdCustomerA, "{\"approved\": true, \"comment\": \"Dimensions fine\"}")
			.andExpect(status().isOk());
		call(post("/api/npd/{id}/validation/decide", project), qualityCustomerA,
				"{\"approved\": false, \"comment\": \"Surface finish below Acme spec 12\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stage").value("FINAL_SAMPLE_READINESS"))
			.andExpect(jsonPath("$.validation.status").value("NOT_STARTED"))
			.andExpect(jsonPath("$.validation.round").value(2));

		// the customer keeps its own words; ERGO sees everything
		call(get("/api/npd/{id}", project), npdCustomerA, null)
			.andExpect(jsonPath("$.validation.decisions[*].party", contains("CUSTOMER_NPD", "CUSTOMER_QUALITY")))
			.andExpect(jsonPath("$.validation.decisions[1].comment").value("Surface finish below Acme spec 12"));
		call(get("/api/npd/{id}", project), ergoNpd, null).andExpect(jsonPath("$.validation.decisions[4].comment").value("Surface finish below Acme spec 12"));
		// the supplier learns the outcome only: no comment, no customer person, no customer party
		String supplierJson = call(get("/api/npd/{id}", project), qualitySupplier, null).andReturn().getResponse().getContentAsString();
		assertThat(supplierJson).doesNotContain("Surface finish").doesNotContain("Dimensions fine").doesNotContain("CUSTOMER_NPD")
			.doesNotContain("CUSTOMER_QUALITY").doesNotContain("Acme");
		call(get("/api/npd/{id}", project), qualitySupplier, null)
			.andExpect(jsonPath("$.validation.decisions[?(@.party == 'CUSTOMER' && @.decision == 'REJECTED')]").isNotEmpty())
			.andExpect(jsonPath("$.actions", hasItem("SUBMIT_VALIDATION")));

		// resubmitted by Supplier Quality: a second round, then both customer teams approve
		TestNpd.validate(mvc, qualitySupplier, qualitySupplier2, ergoQuality, project);
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());
		TestNpd.customerApprove(mvc, npdCustomerA, qualityCustomerA, project);
		assertThat(validationStatus(project)).isEqualTo("CUSTOMER_APPROVED");
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk())
			.andExpect(jsonPath("$.stage").value("APPROVED_FOR_PRODUCTION_HANDOVER"));
	}

	// ---------------------------------------------------------------- review hardening

	@Test
	void aSubmitterCannotValidateTheirOwnSubmissionAndAColleagueCan() throws Exception {
		long project = atFinalSampleReadiness("PN-SELF");
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isOk());
		call(get("/api/npd/{id}", project), qualitySupplier, null).andExpect(jsonPath("$.actions", not(hasItem("DECIDE_VALIDATION"))));
		call(post("/api/npd/{id}/validation/decide", project), qualitySupplier, "{\"approved\": true}").andExpect(status().isConflict());
		assertThat(count("SELECT count(*) FROM npd_validation_decisions WHERE npd_project_id = ? AND party = 'SUPPLIER_QUALITY'", project)).isZero();
		call(post("/api/npd/{id}/validation/decide", project), qualitySupplier2, "{\"approved\": true}").andExpect(status().isOk());
	}

	@Test
	void theOtherExternalPartysPeopleNeverAppearInTheStageHistoryOrTheCustomersRounds() throws Exception {
		long project = atFinalSampleReadiness("PN-NAMES");
		passInspection(project);
		String supplierPerson = userName(qualitySupplier2);
		String customerPerson = userName(qualityCustomerA);

		// Supplier Quality rejects to a development stage: the customer sees neither the person, nor the move, nor a second round
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isOk());
		call(post("/api/npd/{id}/validation/decide", project), qualitySupplier2,
				"{\"approved\": false, \"comment\": \"Rework\", \"returnStage\": \"TRIAL_EXECUTION\"}").andExpect(status().isOk());
		String customerJson = call(get("/api/npd/{id}", project), npdCustomerA, null).andReturn().getResponse().getContentAsString();
		assertThat(customerJson).doesNotContain(supplierPerson);
		call(get("/api/npd/{id}", project), npdCustomerA, null).andExpect(jsonPath("$.validation.round").value(1))
			.andExpect(jsonPath("$.stageHistory[?(@.stage == 'TRIAL_EXECUTION' && @.previousStage == 'FINAL_SAMPLE_READINESS')]", empty()));
		// ERGO sees the person and the move
		String ergoJson = call(get("/api/npd/{id}", project), ergoNpd, null).andReturn().getResponse().getContentAsString();
		assertThat(ergoJson).contains(supplierPerson);

		// the customer rejects: the supplier sees the move but not the customer person
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());
		TestNpd.validate(mvc, qualitySupplier, qualitySupplier2, ergoQuality, project);
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());
		call(post("/api/npd/{id}/validation/decide", project), qualityCustomerA, "{\"approved\": false, \"comment\": \"No\"}").andExpect(status().isOk());
		String supplierJson = call(get("/api/npd/{id}", project), qualitySupplier, null).andReturn().getResponse().getContentAsString();
		assertThat(supplierJson).doesNotContain(customerPerson);
		call(get("/api/npd/{id}", project), npdCustomerA, null).andExpect(jsonPath("$.validation.round").value(2));
	}

	@Test
	void theFinalSampleWorkIsFrozenWhileItIsBeingValidated() throws Exception {
		long project = TestNpd.agreed(mvc, npdCustomerA, kam, ergoNpd, ergoQuality, npdSupplier, supplier, approvedPart("PN-FROZEN"));
		call(post("/api/npd/{id}/activities", project), ergoNpd, ergoStep("Final fit check", "NOT_STARTED", "FINAL_SAMPLE_READINESS")).andExpect(status().isCreated());
		long step = jdbc.queryForObject("SELECT npd_activity_id FROM npd_activities WHERE npd_project_id = ? AND activity_name = 'Final fit check'", Long.class, project);
		for (int i = 0; i < 5; i++) {
			call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());
		}
		call(post("/api/npd/activities/{id}/progress", step), ergoNpd, "{\"status\": \"DONE\", \"issueFlag\": false, \"riskFlag\": false}").andExpect(status().isOk());
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isOk());

		// nothing about the submitted work changes meanwhile; new steps cannot be added to the stage either
		call(post("/api/npd/activities/{id}/progress", step), ergoNpd, "{\"status\": \"IN_PROGRESS\", \"issueFlag\": false, \"riskFlag\": false}").andExpect(status().isConflict());
		call(post("/api/npd/activities/{id}/block", step), ergoNpd, "{\"comment\": \"x\"}").andExpect(status().isConflict());
		call(post("/api/npd/{id}/activities", project), ergoNpd, ergoStep("Late", "NOT_STARTED")).andExpect(status().isConflict());
		call(get("/api/npd/{id}", project), ergoNpd, null).andExpect(jsonPath("$.activities[?(@.id == %d)].actions".formatted(step), contains(empty())));

		// a rejection releases it again
		call(post("/api/npd/{id}/validation/decide", project), ergoQuality,
				"{\"approved\": false, \"comment\": \"Redo\", \"returnStage\": \"PLANNING\"}").andExpect(status().isOk());
		call(post("/api/npd/activities/{id}/progress", step), ergoNpd, "{\"status\": \"IN_PROGRESS\", \"issueFlag\": false, \"riskFlag\": false}").andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- notifications

	@Test
	void everyoneWhoMustActHearsOfEachStepOfTheLoopAndNoOneElseDoes() throws Exception {
		long project = atFinalSampleReadiness("PN-NOTIFY5");
		passInspection(project);

		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isOk());
		assertThat(heard(ergoQuality, "NPD_VALIDATION_REQUESTED", project)).isEqualTo(1); // the assigned quality owner
		for (Login not : List.of(ergoQuality2, ergoNpd, npdSupplier, npdCustomerA, qualityCustomerA, qualitySupplierB)) {
			assertThat(heard(not, "NPD_VALIDATION_REQUESTED", project)).isZero();
		}
		// a colleague of the submitter hears, the submitter does not
		assertThat(heard(qualitySupplier2, "NPD_VALIDATION_REQUESTED", project)).isEqualTo(1);
		assertThat(heard(qualitySupplier, "NPD_VALIDATION_REQUESTED", project)).isZero();

		call(post("/api/npd/{id}/validation/decide", project), ergoQuality, "{\"approved\": true}").andExpect(status().isOk());
		call(post("/api/npd/{id}/validation/decide", project), qualitySupplier2, "{\"approved\": true}").andExpect(status().isOk());
		assertThat(heard(ergoNpd, "NPD_VALIDATION_COMPLETE", project)).isEqualTo(1);
		assertThat(heard(ergoQuality, "NPD_VALIDATION_COMPLETE", project)).isEqualTo(1);

		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());
		assertThat(heard(npdCustomerA, "NPD_CUSTOMER_REVIEW_REQUESTED", project)).isEqualTo(1);
		assertThat(heard(qualityCustomerA, "NPD_CUSTOMER_REVIEW_REQUESTED", project)).isEqualTo(1);
		for (Login not : List.of(npdCustomerB, npdSupplier, qualitySupplier, ergoQuality)) {
			assertThat(heard(not, "NPD_CUSTOMER_REVIEW_REQUESTED", project)).isZero();
		}

		call(post("/api/npd/{id}/validation/decide", project), npdCustomerA, "{\"approved\": false, \"comment\": \"No\"}").andExpect(status().isOk());
		assertThat(heard(ergoNpd, "NPD_VALIDATION_RETURNED", project)).isEqualTo(1);
		assertThat(heard(qualitySupplier, "NPD_VALIDATION_RETURNED", project)).isEqualTo(1);
		assertThat(heard(npdSupplier, "NPD_VALIDATION_RETURNED", project)).isEqualTo(1);
		assertThat(heard(qualityCustomerA, "NPD_VALIDATION_RETURNED", project)).isZero();

		TestNpd.validate(mvc, qualitySupplier, qualitySupplier2, ergoQuality, project);
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());
		TestNpd.customerApprove(mvc, npdCustomerA, qualityCustomerA, project);
		assertThat(heard(ergoNpd, "NPD_CUSTOMER_APPROVED", project)).isEqualTo(1);
		assertThat(heard(npdSupplier, "NPD_CUSTOMER_APPROVED", project)).isZero();
	}

	// ---------------------------------------------------------------- projects from before the loop

	@Test
	void projectsThatAlreadyPassedCustomerFeedbackBeforeTheLoopCountAsApproved() throws Exception {
		long project = atFinalSampleReadiness("PN-LEGACY5");
		jdbc.update("UPDATE npd_projects SET stage = 'CUSTOMER_FEEDBACK', validation_status = 'CUSTOMER_APPROVED' WHERE npd_project_id = ?", project);
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk())
			.andExpect(jsonPath("$.stage").value("APPROVED_FOR_PRODUCTION_HANDOVER"));
	}

	// ---------------------------------------------------------------- helpers

	/** An agreed project with no mandatory steps, advanced to Final Sample Readiness. */
	private long atFinalSampleReadiness(String partNumber) throws Exception {
		long project = TestNpd.agreed(mvc, npdCustomerA, kam, ergoNpd, ergoQuality, npdSupplier, supplier, approvedPart(partNumber));
		for (int i = 0; i < 5; i++) {
			call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());
		}
		assertThat(stage(project)).isEqualTo("FINAL_SAMPLE_READINESS");
		return project;
	}

	/** The real Quality inspection the stage close still needs: created and submitted by ERGO Quality, passed by Management. */
	private void passInspection(long projectId) throws Exception {
		long inspectionId = JsonPath
			.<Number>read(call(post("/api/quality/inspections"), ergoQuality, """
					{"linkedModule": "NPD", "linkedRecordId": %d, "partNumber": "PN-STD", "customerVisible": false}"""
				.formatted(projectId)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")
			.longValue();
		call(post("/api/quality/inspections/{id}/submit", inspectionId), ergoQuality, """
				{"partNumber": "PN-STD", "quantityChecked": 5, "quantityRejected": 0, "inspectionDate": "%s",
				 "customerVisible": false}""".formatted(LocalDate.now())).andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/pass", inspectionId), ergoManagement, "{}").andExpect(status().isOk());
	}

	private long approvedPart(String partNumber) throws Exception {
		long id = TestRfq.create(mvc, buyerA, TestRfq.form(inr, partNumber));
		call(post("/api/rfqs/{id}/submit", id), buyerA, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		TestRfq.approveWhole(mvc, buyerA, ergoRfq, seller, supplier, inr, id, "90");
		return TestRfq.itemIds(mvc, ergoRfq, id).getFirst();
	}

	private String ergoStep(String name, String status) {
		return ergoStep(name, status, null);
	}

	private String ergoStep(String name, String status, String stage) {
		return """
				{"activityName": "%s", "status": "%s", "mandatory": true, "riskFlag": false, "issueFlag": false,
				 "ownerOrganizationType": "ERGO", "ownerCompanyId": %d, "approvalRequired": false, "stage": %s}"""
			.formatted(name, status, ergo, stage == null ? "null" : "\"" + stage + "\"");
	}

	private String userName(Login user) {
		return jdbc.queryForObject("SELECT first_name || ' ' || last_name FROM users WHERE user_id = ?", String.class, user.userId());
	}

	private String validationStatus(long project) {
		return jdbc.queryForObject("SELECT validation_status FROM npd_projects WHERE npd_project_id = ?", String.class, project);
	}

	private String stage(long project) {
		return jdbc.queryForObject("SELECT stage FROM npd_projects WHERE npd_project_id = ?", String.class, project);
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private long heard(Login user, String kind, long record) {
		return count("""
				SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_kind = ? AND linked_module = 'npd'
				AND linked_record_id = ?""", user.userId(), kind, record);
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

}
