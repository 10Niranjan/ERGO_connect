package com.ergo.connect.npd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
import com.ergo.connect.common.security.DefaultAccessSheet;
import com.ergo.connect.common.security.Role;

/**
 * Flow doc step 11: the customer releases a production order under OFP, the ERGO KAM creates the OFP project for it,
 * ERGO OFP runs the same plan / execution / quality validation / approval workflow with only the relevant stages, the
 * Customer OFP and Supplier OFP teams take part, and the order is created from the finished OFP project.
 */
@IntegrationTest
class ProductionFlowTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AccessPolicy policy;

	TestUsers users;

	long ergo;

	long customerA;

	long customerB;

	long supplier;

	long supplierB;

	long inr;

	Login ergoRfq;

	Login ergoNpd;

	Login ergoOfp;

	Login ergoOfp2;

	Login ergoQuality;

	Login ergoManagement;

	Login kamA;

	Login kamB;

	Login buyerA;

	Login npdCustomerA;

	Login qualityCustomerA;

	Login ofpCustomerA;

	Login ofpCustomerB;

	Login seller;

	Login npdSupplier;

	Login qualitySupplier;

	Login qualitySupplier2;

	Login ofpSupplier;

	Login ofpSupplierB;

	TestNpd.Team team;

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
		ergoOfp = users.login(ergo, "ERGO_OFP");
		ergoOfp2 = users.login(ergo, "ERGO_OFP");
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		ergoManagement = users.login(ergo, "ERGO_MANAGEMENT");
		kamA = users.login(ergo, "ERGO_KAM");
		users.assignAccounts(kamA.userId(), customerA);
		kamB = users.login(ergo, "ERGO_KAM");
		users.assignAccounts(kamB.userId(), customerB);
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		npdCustomerA = users.login(customerA, "CUSTOMER_NPD");
		qualityCustomerA = users.login(customerA, "CUSTOMER_QUALITY");
		ofpCustomerA = users.login(customerA, "CUSTOMER_OFP");
		ofpCustomerB = users.login(customerB, "CUSTOMER_OFP");
		seller = users.login(supplier, "SUPPLIER_RFQ");
		npdSupplier = users.login(supplier, "SUPPLIER_NPD");
		qualitySupplier = users.login(supplier, "SUPPLIER_QUALITY");
		qualitySupplier2 = users.login(supplier, "SUPPLIER_QUALITY");
		ofpSupplier = users.login(supplier, "SUPPLIER_OFP");
		ofpSupplierB = users.login(supplierB, "SUPPLIER_OFP");
		team = new TestNpd.Team(npdCustomerA, qualityCustomerA, ofpCustomerA, kamA, ergoNpd, ergoOfp, ergoQuality, ergoManagement,
				npdSupplier, qualitySupplier, qualitySupplier2, ofpSupplier, supplier);
	}

	@AfterEach
	void restoreShippedSheet() {
		jdbc.update("UPDATE app_modules SET access_cap = 'O'");
		for (Role role : Role.values()) {
			for (String module : List.of("NPD", "OFP")) {
				jdbc.update("UPDATE role_module_permissions SET access_level = ? WHERE role_code = ? AND module_code = ?",
						DefaultAccessSheet.code(role, module), role.name(), module);
			}
		}
		jdbc.update("UPDATE access_config SET version = version + 1");
		policy.refresh();
	}

	// ---------------------------------------------------------------- the whole path

	@Test
	void theCustomerReleasesAProductionOrderTheKamCreatesTheOfpProjectAndTheOrderComesOutOfIt() throws Exception {
		long sample = TestNpd.approvedSample(mvc, team, approvedPart("PN-FULL"));

		// Customer OFP sees the approved sample as the place to start; the production order is submitted to the KAM
		call(get("/api/ofp/production-pos/eligible-projects"), ofpCustomerA, null).andExpect(jsonPath("$[*].projectId", hasItem((int) sample)))
			.andExpect(jsonPath("$[0].actions", hasItem("RAISE")));
		long po = TestNpd.raiseProductionPo(mvc, ofpCustomerA, sample);
		assertThat(heard(kamA, "PRODUCTION_PO_SUBMITTED", "production-po", po)).isEqualTo(1);
		call(get("/api/ofp/production-pos/eligible-projects"), ofpCustomerA, null).andExpect(jsonPath("$[*].projectId", not(hasItem((int) sample))));
		call(get("/api/ofp/production-pos/{id}", po), kamA, null).andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.actions", hasItem("CREATE_PROJECT")));

		// accepting creates the OFP project for that production order, assigned to ERGO OFP and ERGO Quality
		long project = TestNpd.createOfpProject(mvc, kamA, po, ergoOfp, ergoQuality);
		call(get("/api/npd/{id}", project), ergoOfp, null).andExpect(jsonPath("$.kind").value("PRODUCTION"))
			.andExpect(jsonPath("$.npdNumber").value(org.hamcrest.Matchers.startsWith("OFP-")))
			.andExpect(jsonPath("$.stage").value("PLANNING"))
			.andExpect(jsonPath("$.planStatus").value("AWAITING_PLAN"))
			.andExpect(jsonPath("$.owner").isNotEmpty())
			.andExpect(jsonPath("$.actions", hasItem("ADD_ACTIVITY")));
		call(get("/api/ofp/production-pos/{id}", po), kamA, null).andExpect(jsonPath("$.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.projectId").value((int) project));
		assertThat(heard(ergoOfp, "NPD_PROJECT_ASSIGNED", "npd", project)).isEqualTo(1);
		assertThat(heard(ergoQuality, "NPD_PROJECT_ASSIGNED", "npd", project)).isEqualTo(1);

		// the plan: ERGO OFP builds it, the supplier's OFP team sees it only once released, submits, ERGO OFP agrees
		call(get("/api/npd/{id}", project), ofpSupplier, null).andExpect(status().isNotFound());
		TestNpd.addSupplierStep(mvc, ergoOfp, project, supplier, "Production run", true);
		TestNpd.assignPlan(mvc, ergoOfp, project);
		assertThat(heard(ofpSupplier, "NPD_PLAN_ASSIGNED", "npd", project)).isEqualTo(1);
		call(get("/api/npd/{id}", project), ofpSupplier, null).andExpect(status().isOk()).andExpect(jsonPath("$.actions", hasItem("SUBMIT_PLAN")));
		TestNpd.submitPlan(mvc, ofpSupplier, project);
		TestNpd.agreePlan(mvc, ergoOfp, project);

		// execution: the supplier's OFP team works its step, only the relevant stages are walked
		long step = jdbc.queryForObject("SELECT npd_activity_id FROM npd_activities WHERE npd_project_id = ?", Long.class, project);
		call(post("/api/npd/activities/{id}/progress", step), ofpSupplier, "{\"status\": \"DONE\", \"issueFlag\": false, \"riskFlag\": false}")
			.andExpect(status().isOk());
		call(post("/api/npd/{id}/close-stage", project), ergoOfp, "{}").andExpect(jsonPath("$.stage").value("COMMERCIAL_SOURCING_READINESS"));
		call(post("/api/npd/{id}/close-stage", project), ergoOfp, "{}").andExpect(jsonPath("$.stage").value("TRIAL_EXECUTION"));
		call(post("/api/npd/{id}/close-stage", project), ergoOfp, "{}").andExpect(jsonPath("$.stage").value("FINAL_SAMPLE_READINESS"));

		// the same quality validation and customer final approval
		TestNpd.passInspection(mvc, ergoQuality, ergoManagement, project);
		TestNpd.validate(mvc, qualitySupplier, qualitySupplier2, ergoQuality, project);
		call(post("/api/npd/{id}/close-stage", project), ergoOfp, "{}").andExpect(jsonPath("$.stage").value("CUSTOMER_FEEDBACK"));
		TestNpd.customerApprove(mvc, npdCustomerA, qualityCustomerA, project);
		call(post("/api/npd/{id}/close-stage", project), ergoOfp, "{}").andExpect(jsonPath("$.stage").value("APPROVED_FOR_PRODUCTION_HANDOVER"));

		// the order is created from the finished OFP project, carrying the customer's production PO
		String poNumber = jdbc.queryForObject("SELECT customer_po_number FROM production_pos WHERE production_po_id = ?", String.class, po);
		call(post("/api/ofp/orders/handover/{id}", project), ergoOfp, "{\"orderedQuantity\": 1000}").andExpect(status().isCreated())
			.andExpect(jsonPath("$.linkedNpdProjectId").value((int) project))
			.andExpect(jsonPath("$.customerPoNo").value(poNumber));
		call(post("/api/ofp/orders/handover/{id}", project), ergoOfp, "{\"orderedQuantity\": 1000}").andExpect(status().isConflict());
		// a sample project never yields an order
		call(post("/api/ofp/orders/handover/{id}", sample), ergoOfp, "{\"orderedQuantity\": 1000}").andExpect(status().isConflict());
	}

	// ---------------------------------------------------------------- the production order: who, which, and the KAM's decision

	@Test
	void onlyCustomerOfpReleasesAnOrderForItsOwnApprovedSampleAndOnlyOnceAtATime() throws Exception {
		long sample = TestNpd.approvedSample(mvc, team, approvedPart("PN-RAISE6"));
		String form = TestNpd.productionPoForm(sample);

		for (Login other : List.of(npdCustomerA, qualityCustomerA, buyerA, seller, npdSupplier, ofpSupplier, ergoManagement, kamA, ergoOfp, ergoNpd)) {
			call(post("/api/ofp/production-pos"), other, form).andExpect(status().isForbidden());
		}
		call(post("/api/ofp/production-pos"), ofpCustomerB, form).andExpect(status().isNotFound()); // another customer's project
		call(post("/api/ofp/production-pos"), ofpCustomerA, form.replace("\"quantity\": 1000", "\"quantity\": 0")).andExpect(status().isBadRequest());
		call(post("/api/ofp/production-pos"), ofpCustomerA, form.replace(LocalDate.now().plusDays(60).toString(), "2020-01-01")).andExpect(status().isBadRequest());

		// a sample that is not yet approved cannot continue into production
		long unfinished = TestNpd.release(mvc, npdCustomerA, kamA, ergoNpd, ergoQuality, supplier, approvedPart("PN-UNFINISHED"));
		call(post("/api/ofp/production-pos"), ofpCustomerA, TestNpd.productionPoForm(unfinished)).andExpect(status().isUnprocessableContent());
		assertThat(count("SELECT count(*) FROM production_pos WHERE source_project_id = ?", unfinished)).isZero();

		long po = TestNpd.raiseProductionPo(mvc, ofpCustomerA, sample);
		call(post("/api/ofp/production-pos"), ofpCustomerA, form).andExpect(status().isConflict()); // one open order per part
		// the supplier never reaches a production order
		call(get("/api/ofp/production-pos/{id}", po), ofpSupplier, null).andExpect(status().isForbidden());
		call(get("/api/ofp/production-pos"), ofpSupplier, null).andExpect(status().isForbidden());
		// another customer does not know it exists
		call(get("/api/ofp/production-pos/{id}", po), ofpCustomerB, null).andExpect(status().isNotFound());
		call(get("/api/ofp/production-pos"), ofpCustomerB, null).andExpect(jsonPath("$[*].id", not(hasItem((int) po))));
	}

	@Test
	void theKamReturnsWithAReasonTheCustomerCorrectsAndOnlyTheRightDeskCreatesTheProject() throws Exception {
		long sample = TestNpd.approvedSample(mvc, team, approvedPart("PN-DESK"));
		long po = TestNpd.raiseProductionPo(mvc, ofpCustomerA, sample);
		String assign = "{\"ergoOfpUserId\": %d, \"ergoQualityUserId\": %d}".formatted(ergoOfp.userId(), ergoQuality.userId());

		// not the other KAM (404), not other ERGO roles, not the customer or the supplier
		call(post("/api/ofp/production-pos/{id}/create-project", po), kamB, assign).andExpect(status().isNotFound());
		for (Login other : List.of(ergoOfp, ergoNpd, ergoQuality, ofpCustomerA, ofpSupplier, qualitySupplier)) {
			call(post("/api/ofp/production-pos/{id}/create-project", po), other, assign).andExpect(status().is4xxClientError());
		}
		assertThat(count("SELECT count(*) FROM npd_projects WHERE production_po_id = ?", po)).isZero();
		// assignees must hold the named roles
		call(post("/api/ofp/production-pos/{id}/create-project", po), kamA,
				"{\"ergoOfpUserId\": %d, \"ergoQualityUserId\": %d}".formatted(ergoNpd.userId(), ergoQuality.userId())).andExpect(status().isUnprocessableContent());
		call(get("/api/ofp/production-pos/assignees"), kamA, null).andExpect(jsonPath("$.ofp[*].id", hasItem((int) ergoOfp.userId())))
			.andExpect(jsonPath("$.quality[*].id", hasItem((int) ergoQuality.userId())));

		// a return needs a reason and sends it to Customer OFP, who corrects and resubmits
		call(post("/api/ofp/production-pos/{id}/return", po), kamA, "{}").andExpect(status().isBadRequest());
		call(post("/api/ofp/production-pos/{id}/return", po), kamA, "{\"comment\": \"Quantity does not match the sample lot\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RETURNED"));
		assertThat(heard(ofpCustomerA, "PRODUCTION_PO_RETURNED", "production-po", po)).isEqualTo(1);
		call(post("/api/ofp/production-pos/{id}/create-project", po), kamA, assign).andExpect(status().isConflict());
		call(put("/api/ofp/production-pos/{id}", po), ofpCustomerA, """
				{"customerPoNumber": "PPO-FIXED", "quantity": 800, "requiredDate": "%s", "remarks": "Corrected"}"""
			.formatted(LocalDate.now().plusDays(70))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.actions", empty()));

		// Management may decide too
		call(post("/api/ofp/production-pos/{id}/create-project", po), ergoManagement, assign).andExpect(status().isCreated());
		assertThat(count("SELECT count(*) FROM npd_projects WHERE production_po_id = ? AND kind = 'PRODUCTION'", po)).isEqualTo(1);
		// it is then open again for a repeat order
		call(get("/api/ofp/production-pos/eligible-projects"), ofpCustomerA, null).andExpect(jsonPath("$[*].projectId", hasItem((int) sample)));
	}

	// ---------------------------------------------------------------- the OFP project: stages, teams, rights

	@Test
	void anOfpProjectSkipsToolingAndTrialPreparationAndEveryStageShowsOnlyTheRelevantOnes() throws Exception {
		long project = productionProject("PN-STAGES");

		call(get("/api/npd/{id}", project), ergoOfp, null)
			.andExpect(jsonPath("$.progress.stages[*].stage",
					contains("PLANNING", "COMMERCIAL_SOURCING_READINESS", "TRIAL_EXECUTION", "FINAL_SAMPLE_READINESS", "CUSTOMER_FEEDBACK",
							"APPROVED_FOR_PRODUCTION_HANDOVER")));
		// a step cannot be planned into a stage the project does not go through
		call(post("/api/npd/{id}/activities", project), ergoOfp, step("Tool build", "TOOLING_MATERIAL_RELEASE")).andExpect(status().isUnprocessableContent());
		call(post("/api/npd/{id}/activities", project), ergoOfp, step("Trial prep", "TRIAL_PREPARATION")).andExpect(status().isUnprocessableContent());
		call(post("/api/npd/{id}/activities", project), ergoOfp, step("Run", "TRIAL_EXECUTION")).andExpect(status().isCreated());

		// a rejection cannot send it back to a stage it skips
		for (int i = 0; i < 3; i++) {
			call(post("/api/npd/{id}/close-stage", project), ergoOfp, "{}").andExpect(status().is2xxSuccessful());
			if (i == 1) {
				jdbc.update("UPDATE npd_activities SET status = 'DONE' WHERE npd_project_id = ? AND activity_name = 'Run'", project);
			}
		}
		assertThat(stage(project)).isEqualTo("FINAL_SAMPLE_READINESS");
		call(post("/api/npd/{id}/validation/submit", project), qualitySupplier, "{}").andExpect(status().isOk());
		call(post("/api/npd/{id}/validation/decide", project), ergoQuality,
				"{\"approved\": false, \"comment\": \"No\", \"returnStage\": \"TOOLING_MATERIAL_RELEASE\"}").andExpect(status().isUnprocessableContent());
		call(post("/api/npd/{id}/validation/decide", project), ergoQuality,
				"{\"approved\": false, \"comment\": \"No\", \"returnStage\": \"TRIAL_EXECUTION\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.stage").value("TRIAL_EXECUTION"));
	}

	@Test
	void theOfpTeamsTakeWhatTheyNeedAndNothingMoreAndASampleProjectStaysClosedToThem() throws Exception {
		long sample = TestNpd.approvedSample(mvc, team, approvedPart("PN-TEAMS"));
		long project = TestNpd.productionProject(mvc, team, sample);

		// Customer OFP and Supplier OFP reach the OFP project of their own company, not another's, and never the sample project
		call(get("/api/npd/{id}", project), ofpCustomerA, null).andExpect(status().isOk()).andExpect(jsonPath("$.audience").value("CUSTOMER"));
		call(get("/api/npd/{id}", project), ofpSupplier, null).andExpect(status().isOk()).andExpect(jsonPath("$.audience").value("SUPPLIER"));
		call(get("/api/npd/{id}", project), ofpCustomerB, null).andExpect(status().isNotFound());
		call(get("/api/npd/{id}", project), ofpSupplierB, null).andExpect(status().isNotFound());
		call(get("/api/npd/{id}", sample), ofpCustomerA, null).andExpect(status().isNotFound());
		call(get("/api/npd/{id}", sample), ofpSupplier, null).andExpect(status().isNotFound());
		call(get("/api/npd/mine"), ofpCustomerA, null).andExpect(jsonPath("$[*].id", hasItem((int) project)))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) sample))))
			.andExpect(jsonPath("$[?(@.id == %d)].kind".formatted(project), contains("PRODUCTION")));
		call(get("/api/npd/mine"), ofpSupplier, null).andExpect(jsonPath("$[*].id", hasItem((int) project)))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) sample))));
		// no customer name for the supplier, no supplier name for the customer
		String supplierJson = call(get("/api/npd/{id}", project), ofpSupplier, null).andReturn().getResponse().getContentAsString();
		String customerJson = call(get("/api/npd/{id}", project), ofpCustomerA, null).andReturn().getResponse().getContentAsString();
		assertThat(supplierJson).doesNotContain(name(customerA));
		assertThat(customerJson).doesNotContain(name(supplier));

		// the owners and editors: ERGO OFP and ERGO NPD act on it, the KAM and ERGO Quality do not release or edit
		call(post("/api/npd/{id}/activities", project), kamA, step("KAM step", null)).andExpect(status().isConflict());
		call(post("/api/npd/{id}/activities", project), ergoOfp2, step("ERGO step", null)).andExpect(status().isCreated());
		call(post("/api/npd/{id}/activities", project), ergoNpd, step("ERGO NPD step", null)).andExpect(status().isCreated());
		// Customer OFP works a step of its own company, and cannot touch the supplier's
		call(post("/api/npd/{id}/activities", project), ergoOfp, customerStep("Drawing sign-off")).andExpect(status().isCreated());
		long customerStep = jdbc.queryForObject("SELECT npd_activity_id FROM npd_activities WHERE npd_project_id = ? AND activity_name = 'Drawing sign-off'", Long.class, project);
		long supplierStep = jdbc.queryForObject("SELECT min(npd_activity_id) FROM npd_activities WHERE npd_project_id = ? AND owner_organization_type = 'SUPPLIER'", Long.class, project);
		call(post("/api/npd/activities/{id}/progress", customerStep), ofpCustomerA, "{\"status\": \"DONE\", \"issueFlag\": false, \"riskFlag\": false}").andExpect(status().isOk());
		call(post("/api/npd/activities/{id}/progress", supplierStep), ofpCustomerA, "{\"status\": \"DONE\", \"issueFlag\": false, \"riskFlag\": false}").andExpect(status().isNotFound());
		call(post("/api/npd/activities/{id}/progress", supplierStep), ofpSupplier, "{\"status\": \"DONE\", \"issueFlag\": false, \"riskFlag\": false}").andExpect(status().isOk());
		call(post("/api/npd/activities/{id}/progress", customerStep), ofpSupplier, "{\"status\": \"DONE\", \"issueFlag\": false, \"riskFlag\": false}").andExpect(status().isNotFound());

		// ERGO OFP is not an owner of a sample project
		call(post("/api/npd/{id}/activities", sample), ergoOfp, step("Sneaky", null)).andExpect(status().isConflict());
	}

	// ---------------------------------------------------------------- module switches

	@Test
	void theOfpAndNpdSwitchesBindTheProductionOrderAndItsProject() throws Exception {
		long sample = TestNpd.approvedSample(mvc, team, approvedPart("PN-SWITCH"));
		cap("OFP", "V");
		call(post("/api/ofp/production-pos"), ofpCustomerA, TestNpd.productionPoForm(sample)).andExpect(status().isForbidden());
		cap("OFP", "O");
		long po = TestNpd.raiseProductionPo(mvc, ofpCustomerA, sample);

		String assign = "{\"ergoOfpUserId\": %d, \"ergoQualityUserId\": %d}".formatted(ergoOfp.userId(), ergoQuality.userId());
		for (String level : List.of("V", "NV")) {
			cap("NPD", level); // the project it creates belongs to NPD
			call(post("/api/ofp/production-pos/{id}/create-project", po), kamA, assign).andExpect(status().isConflict());
			assertThat(count("SELECT count(*) FROM npd_projects WHERE production_po_id = ?", po)).isZero();
		}
		cap("NPD", "O");
		long project = TestNpd.createOfpProject(mvc, kamA, po, ergoOfp, ergoQuality);

		// OFP at V: the OFP project reads, but nothing changes; NPD hidden: it does not exist for the OFP people
		cap("OFP", "V");
		call(post("/api/npd/{id}/activities", project), ergoOfp, step("Late", null)).andExpect(status().is4xxClientError());
		cap("OFP", "O");
		cap("NPD", "NV");
		call(get("/api/npd/{id}", project), ergoOfp, null).andExpect(status().isNotFound());
		call(get("/api/npd/{id}", project), ofpCustomerA, null).andExpect(status().isNotFound());
	}

	@Test
	void theOfpSwitchFreezesCommentsOnAnOfpProjectAndTheDashboardAndApprovalsFollowTheKind() throws Exception {
		long sample = TestNpd.approvedSample(mvc, team, approvedPart("PN-COMMENTS"));
		long project = TestNpd.productionProject(mvc, team, sample);
		String onProject = "{\"linkedModule\": \"npd\", \"linkedRecordId\": %d, \"commentType\": null, \"text\": \"hello\", \"parentCommentId\": null, \"query\": false, \"mentionedUserIds\": []}";
		call(post("/api/comments"), ergoOfp, onProject.formatted(project)).andExpect(status().isCreated());

		// OFP read-only: the OFP project takes no comment, a sample project still does
		cap("OFP", "V");
		call(post("/api/comments"), ergoOfp, onProject.formatted(project)).andExpect(status().isConflict());
		call(post("/api/comments"), ergoNpd, onProject.formatted(sample)).andExpect(status().isCreated());

		// OFP hidden: the OFP project is gone from every list and from the dashboard, the sample project stays
		cap("OFP", "NV");
		call(get("/api/npd?size=100"), ergoNpd, null).andExpect(jsonPath("$.items[*].id", not(hasItem((int) project)))).andExpect(jsonPath("$.items[*].id", hasItem((int) sample)));
		call(get("/api/dashboard"), ergoManagement, null).andExpect(status().isOk());
		assertThat(call(get("/api/dashboard"), ergoManagement, null).andReturn().getResponse().getContentAsString()).doesNotContain("OFP-");
	}

	// ---------------------------------------------------------------- helpers

	/** An OFP project with its plan agreed, at its first stage. */
	private long productionProject(String partNumber) throws Exception {
		long sample = TestNpd.approvedSample(mvc, team, approvedPart(partNumber));
		return TestNpd.productionProject(mvc, team, sample);
	}

	private long approvedPart(String partNumber) throws Exception {
		long id = TestRfq.create(mvc, buyerA, TestRfq.form(inr, partNumber));
		call(post("/api/rfqs/{id}/submit", id), buyerA, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		TestRfq.approveWhole(mvc, buyerA, ergoRfq, seller, supplier, inr, id, "90");
		return TestRfq.itemIds(mvc, ergoRfq, id).getFirst();
	}

	private String step(String name, String stage) {
		return """
				{"activityName": "%s", "status": "NOT_STARTED", "mandatory": true, "riskFlag": false, "issueFlag": false,
				 "ownerOrganizationType": "ERGO", "ownerCompanyId": %d, "approvalRequired": false, "stage": %s}"""
			.formatted(name, ergo, stage == null ? "null" : "\"" + stage + "\"");
	}

	private String customerStep(String name) {
		return """
				{"activityName": "%s", "status": "NOT_STARTED", "mandatory": false, "riskFlag": false, "issueFlag": false,
				 "ownerOrganizationType": "CUSTOMER", "ownerCompanyId": %d, "approvalRequired": false}"""
			.formatted(name, customerA);
	}

	private String name(long companyId) {
		return jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?", String.class, companyId);
	}

	private String stage(long project) {
		return jdbc.queryForObject("SELECT stage FROM npd_projects WHERE npd_project_id = ?", String.class, project);
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private long heard(Login user, String kind, String module, long record) {
		return count("""
				SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_kind = ? AND linked_module = ?
				AND linked_record_id = ?""", user.userId(), kind, module, record);
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
