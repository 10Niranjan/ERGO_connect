package com.ergo.connect.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.AccessPolicy;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.ModuleSwitch;
import com.jayway.jsonpath.JsonPath;

/**
 * Strict module switches (docs/PERMISSIONS.md §9): a switch binds the whole backend, so every operation that crosses
 * modules checks the switch of <i>every</i> module it touches before it changes anything - and a refusal leaves
 * nothing half done. Covers Quotation&rarr;RFQ, NPD conversion, OFP&rarr;NPD handover, Quality&rarr;NPD/OFP links and the
 * dispatch / Final Sample gates, and proves that switching back to O restores normal operation with the data and the
 * role grid untouched. V = read-only, NV = hidden; every test puts the shipped sheet back.
 */
@IntegrationTest
class StrictModuleSwitchTests {

	private static final AtomicInteger SEQ = new AtomicInteger(700_000);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AccessPolicy policy;

	@Autowired
	PlatformTransactionManager transactions;

	TestUsers users;

	long customer;

	long supplier;

	long inr;

	Login ergoRfq;

	Login ergoNpd;

	Login ergoOfp;

	Login ergoQuality;

	Login ergoManagement;

	Login buyer;

	Login seller;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		customer = users.company("CUSTOMER");
		supplier = users.company("SUPPLIER");
		inr = users.currency("INR");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		ergoNpd = users.login(ergo, "ERGO_NPD");
		ergoOfp = users.login(ergo, "ERGO_OFP");
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		ergoManagement = users.login(ergo, "ERGO_MANAGEMENT");
		buyer = users.login(customer, "CUSTOMER_RFQ");
		seller = users.login(supplier, "SUPPLIER_RFQ");
	}

	@AfterEach
	void restoreShippedSheet() {
		SwitchTestSupport.restore(jdbc, policy);
	}

	// ---------------------------------------------------------------- Quotation -> RFQ

	@Test
	void quotationCannotMoveAnRfqThatIsReadOnlyOrHiddenAndLeavesNothingHalfDone() throws Exception {
		long rfq = validatedRfq();
		var gridBefore = SwitchTestSupport.grid(jdbc);

		for (String cap : List.of("V", "NV")) {
			cap("RFQ", cap);
			// inviting suppliers creates quotations AND moves the RFQ: refused whole, whatever the caller's own rights
			call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, invite(supplier)).andExpect(status().isConflict());
			assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_id = ?", rfq)).isZero();
			assertThat(rfqStatus(rfq)).isEqualTo("VALIDATED");
		}
		cap("RFQ", "O");
		cap("QUOTATION", "V"); // the other module of the pair: refused by its own level (403) and nothing is created
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, invite(supplier)).andExpect(status().isForbidden());
		cap("QUOTATION", "NV");
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, invite(supplier)).andExpect(status().isForbidden());
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_id = ?", rfq)).isZero();

		// back to O: exactly the normal operation, and no role was touched
		cap("QUOTATION", "O");
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, invite(supplier)).andExpect(status().isOk());
		assertThat(rfqStatus(rfq)).isEqualTo("SENT_TO_SUPPLIERS");
		assertThat(SwitchTestSupport.grid(jdbc)).isEqualTo(gridBefore);
	}

	@Test
	void aSupplierQuoteIsFrozenWhileTheRfqIsReadOnlyAndHiddenWhileItIsHidden() throws Exception {
		long rfq = validatedRfq();
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, invite(supplier)).andExpect(status().isOk());
		long quote = invitationOf(rfq);
		call(put("/api/quotes/{id}", quote), seller, quote("80")).andExpect(status().isOk());

		cap("RFQ", "V"); // read-only: the supplier can still read it, but nothing it does may move the RFQ
		call(get("/api/quotes/{id}", quote), seller, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.rfq.quotingOpen").value(false));
		call(put("/api/quotes/{id}", quote), seller, quote("70")).andExpect(status().is4xxClientError());
		call(post("/api/quotes/{id}/submit", quote), seller, null).andExpect(status().is4xxClientError());
		assertThat(assignmentStatus(quote)).isEqualTo("DRAFT_SAVED");
		assertThat(rfqStatus(rfq)).isEqualTo("SENT_TO_SUPPLIERS");

		cap("RFQ", "NV"); // hidden: every quotation screen that embeds the RFQ is empty / not found
		call(get("/api/quotes"), seller, null).andExpect(status().isForbidden()); // the supplier's assigned-RFQ page is RFQ's
		call(get("/api/quotes/{id}", quote), seller, null).andExpect(status().isForbidden()); // RFQ-gated for a supplier
		call(get("/api/rfqs/{id}/quotes", rfq), ergoRfq, null).andExpect(status().isNotFound());
		call(post("/api/quotes/{id}/submit", quote), seller, null).andExpect(status().is4xxClientError());
		assertThat(assignmentStatus(quote)).isEqualTo("DRAFT_SAVED");

		cap("RFQ", "O"); // the draft survived; submitting now works and moves the RFQ
		call(get("/api/quotes/{id}", quote), seller, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.quote.productionPiecePrice").value(80.0));
		call(post("/api/quotes/{id}/submit", quote), seller, null).andExpect(status().isOk());
		assertThat(assignmentStatus(quote)).isEqualTo("SUBMITTED");
		assertThat(rfqStatus(rfq)).isIn("QUOTES_PARTIAL", "QUOTES_COMPLETE");
	}

	@Test
	void theCustomerQuoteCycleCannotMoveAReadOnlyOrHiddenRfq() throws Exception {
		long rfq = validatedRfq();
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, invite(supplier)).andExpect(status().isOk());
		long quote = invitationOf(rfq);
		call(put("/api/quotes/{id}", quote), seller, quote("80")).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", quote), seller, null).andExpect(status().isOk());

		// ERGO's selection moves the RFQ to "under comparison"
		cap("RFQ", "V");
		call(post("/api/quotes/{id}/decision", quote), ergoRfq, "{\"decision\": \"SELECT_FOR_CUSTOMER\"}")
			.andExpect(status().is4xxClientError());
		assertThat(assignmentStatus(quote)).isEqualTo("SUBMITTED");
		cap("RFQ", "O");
		call(post("/api/quotes/{id}/decision", quote), ergoRfq, "{\"decision\": \"SELECT_FOR_CUSTOMER\"}")
			.andExpect(status().isOk());

		// sending the quote to the customer
		String send = "{\"quoteId\": %d, \"type\": \"SUPPLIER_QUOTE\", \"remarks\": \"ok\"}".formatted(quote);
		for (String cap : List.of("V", "NV")) {
			cap("RFQ", cap);
			call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, send).andExpect(status().is4xxClientError());
			assertThat(count("SELECT count(*) FROM customer_quote_submissions WHERE rfq_id = ?", rfq)).isZero();
		}
		cap("RFQ", "O");
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, send).andExpect(status().isOk());
		assertThat(rfqStatus(rfq)).isEqualTo("SENT_TO_CUSTOMER");

		// the customer's answer (the approval that moves the RFQ on) waits for the RFQ module
		long sent = JsonPath.<Number>read(call(get("/api/rfqs/{id}/customer-quotes", rfq), buyer, null).andReturn()
			.getResponse().getContentAsString(), "$.quotes[0].id").longValue();
		for (String cap : List.of("V", "NV")) {
			cap("RFQ", cap);
			call(post("/api/customer-quotes/{id}/respond", sent), buyer, "{\"response\": \"APPROVED\"}")
				.andExpect(status().is4xxClientError());
			assertThat(rfqStatus(rfq)).isEqualTo("SENT_TO_CUSTOMER");
			assertThat(jdbc.queryForObject("SELECT response_status FROM customer_quote_submissions WHERE rfq_id = ?",
					String.class, rfq)).isEqualTo("WAITING");
		}
		cap("RFQ", "O");
		call(post("/api/customer-quotes/{id}/respond", sent), buyer, "{\"response\": \"APPROVED\"}")
			.andExpect(status().isOk());
		assertThat(rfqStatus(rfq)).isEqualTo("CUSTOMER_APPROVED");
	}

	// ---------------------------------------------------------------- NPD conversion and the OFP handover

	@Test
	void convertingAnRfqNeedsTheRfqAndQuotationModulesVisibleAndNpdAcceptingChanges() throws Exception {
		long rfq = approvedRfq();
		call(get("/api/npd/conversion-queue"), ergoNpd, null).andExpect(jsonPath("$[?(@.rfqId == %d)]".formatted(rfq))
			.isNotEmpty());

		// the queue is closed when the RFQ is hidden; conversion is refused with nothing created
		cap("RFQ", "NV");
		call(get("/api/npd/conversion-queue"), ergoNpd, null).andExpect(jsonPath("$[?(@.rfqId == %d)]".formatted(rfq))
			.isEmpty());
		call(post("/api/npd/convert/{id}", rfq), ergoNpd, "{}").andExpect(status().isConflict());
		cap("RFQ", "O");
		cap("QUOTATION", "NV");
		call(post("/api/npd/convert/{id}", rfq), ergoNpd, "{}").andExpect(status().isConflict());
		assertThat(count("SELECT count(*) FROM npd_projects WHERE source_rfq_id = ?", rfq)).isZero();

		// a read-only RFQ / Quotation is still readable, so conversion goes through (it only reads them)
		cap("QUOTATION", "V");
		cap("RFQ", "V");
		long project = JsonPath.<Number>read(call(post("/api/npd/convert/{id}", rfq), ergoNpd, "{}")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id").longValue();
		assertThat(project).isPositive();

		// the project shows the RFQ number only while the RFQ module is visible
		call(get("/api/npd/{id}", project), ergoNpd, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.rfqNumber").isNotEmpty());
		cap("RFQ", "NV");
		call(get("/api/npd/{id}", project), ergoNpd, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.rfqNumber").value(nullValue()));
		call(get("/api/npd"), ergoNpd, null).andExpect(status().isOk());
	}

	@Test
	void anOfpHandoverChangesNpdAndOfpTogetherOrNotAtAll() throws Exception {
		long project = npdProject("APPROVED_FOR_PRODUCTION_HANDOVER");
		String handover = "{\"orderedQuantity\": 100, \"dueDate\": \"%s\"}".formatted(LocalDate.now().plusDays(30));

		for (String cap : List.of("V", "NV")) {
			cap("NPD", cap); // marking the project handed over is a change to NPD: refused, no order is created
			call(post("/api/ofp/orders/handover/{id}", project), ergoOfp, handover).andExpect(status().isConflict());
			assertThat(handedOver(project)).isFalse();
			assertThat(count("SELECT count(*) FROM orders WHERE linked_npd_project_id = ?", project)).isZero();
		}
		cap("NPD", "O");
		cap("OFP", "V"); // the order side: refused by its own level
		call(post("/api/ofp/orders/handover/{id}", project), ergoOfp, handover).andExpect(status().isForbidden());
		assertThat(handedOver(project)).isFalse();

		cap("OFP", "O"); // back on: the normal operation, once
		call(post("/api/ofp/orders/handover/{id}", project), ergoOfp, handover).andExpect(status().isCreated());
		assertThat(handedOver(project)).isTrue();
		assertThat(count("SELECT count(*) FROM orders WHERE linked_npd_project_id = ?", project)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- Quality links and gates

	@Test
	void aQualityRecordLinksToNpdAndOfpOnlyWhileTheyAreVisible() throws Exception {
		long order = order();
		long project = npdProject("PLANNING");

		cap("NPD", "NV");
		cap("OFP", "NV");
		call(post("/api/quality/inspections"), ergoQuality, inspection("NPD", project)).andExpect(status().isUnprocessableContent());
		call(post("/api/quality/inspections"), ergoQuality, inspection("OFP", order)).andExpect(status().isUnprocessableContent());
		call(post("/api/quality/ncrs"), ergoQuality, ncr("OFP", order)).andExpect(status().isUnprocessableContent());
		call(post("/api/quality/ncrs"), ergoQuality, ncr("NPD", project)).andExpect(status().isUnprocessableContent());
		assertThat(count("SELECT count(*) FROM inspections WHERE linked_record_id IN (?, ?)", order, project)).isZero();

		// read-only is enough to link (the link changes Quality, it only reads NPD/OFP)
		cap("NPD", "V");
		cap("OFP", "V");
		call(post("/api/quality/inspections"), ergoQuality, inspection("NPD", project)).andExpect(status().isCreated());
		call(post("/api/quality/inspections"), ergoQuality, inspection("OFP", order)).andExpect(status().isCreated());
		call(post("/api/quality/ncrs"), ergoQuality, ncr("OFP", order)).andExpect(status().isCreated());

		// ... but not while Quality itself is read-only or hidden (the record being created belongs to Quality)
		for (String cap : List.of("V", "NV")) {
			cap("QUALITY", cap);
			call(post("/api/quality/inspections"), ergoQuality, inspection("OFP", order)).andExpect(status().isForbidden());
		}
	}

	@Test
	void theDispatchAndFinalSampleGatesFailClosedWhileQualityIsHidden() throws Exception {
		long project = npdProject("FINAL_SAMPLE_READINESS");
		jdbc.update("""
				INSERT INTO inspections (linked_module, linked_record_id, customer_company_id, supplier_company_id,
				                         part_number, result_status, created_by_user_id, created_by_company_id)
				VALUES ('NPD', ?, ?, ?, 'PN-GATE', 'PASSED', ?, ?)""", project, customer, supplier, ergoQuality.userId(),
				ergoQuality.companyId());

		// the sign-off cannot be evaluated while Quality is hidden: the stage does not close, nothing changes
		cap("QUALITY", "NV");
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isConflict());
		assertThat(stage(project)).isEqualTo("FINAL_SAMPLE_READINESS");

		// read-only Quality is still readable, so the gate is evaluated normally (the inspection passed)
		cap("QUALITY", "V");
		call(post("/api/npd/{id}/close-stage", project), ergoNpd, "{}").andExpect(status().isOk());
		assertThat(stage(project)).isNotEqualTo("FINAL_SAMPLE_READINESS");
	}

	// ---------------------------------------------------------------- the switch holds on every instance, in the transaction

	@Test
	void aSwitchSavedByAnotherInstanceAppliesToTheNextRequestWithoutWaitingForTheRefresh() throws Exception {
		call(get("/api/rfqs"), ergoRfq, null).andExpect(status().isOk());
		// another instance saves the switch: only the database changes here, this instance's snapshot is stale
		jdbc.update("UPDATE app_modules SET access_cap = 'NV' WHERE module_code = 'RFQ'");
		jdbc.update("UPDATE access_config SET version = version + 1");

		call(get("/api/rfqs"), ergoRfq, null).andExpect(status().isForbidden()); // no 5-second window
		// the write guard reads the database itself, so even a thread that never saw the new snapshot is refused
		assertThatThrownBy(() -> ModuleSwitch.requireWritable(Module.RFQ)).isInstanceOf(ApiException.class);
	}

	@Test
	void anOperationHoldsTheSwitchItCheckedUntilItEndsSoASwitchChangeNeverLandsInTheMiddle() throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		Future<?>[] otherInstance = new Future<?>[1];
		try {
			new TransactionTemplate(transactions).executeWithoutResult(status -> {
				ModuleSwitch.requireWritable(Module.RFQ); // checks the RFQ switch and keeps it locked for this transaction
				// another instance now saves the switch: it must wait for this operation, not land in the middle of it
				otherInstance[0] = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(
						s -> jdbc.update("UPDATE app_modules SET access_cap = 'V' WHERE module_code = 'RFQ'")));
				assertThatThrownBy(() -> otherInstance[0].get(700, TimeUnit.MILLISECONDS))
					.isInstanceOf(TimeoutException.class);
				assertThat(jdbc.queryForObject("SELECT access_cap FROM app_modules WHERE module_code = 'RFQ'",
						String.class)).isEqualTo("O");
			});
			otherInstance[0].get(10, TimeUnit.SECONDS); // the operation ended: the change goes through
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject("SELECT access_cap FROM app_modules WHERE module_code = 'RFQ'", String.class))
			.isEqualTo("V");
	}

	// ---------------------------------------------------------------- fixtures

	private void cap(String module, String cap) {
		SwitchTestSupport.cap(jdbc, policy, module, cap);
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private String rfqStatus(long rfq) {
		return jdbc.queryForObject("SELECT status FROM rfqs WHERE rfq_id = ?", String.class, rfq);
	}

	private String assignmentStatus(long assignment) {
		return jdbc.queryForObject("SELECT status FROM rfq_supplier_assignments WHERE assignment_id = ?", String.class,
				assignment);
	}

	private boolean handedOver(long project) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT handover_ready_flag FROM npd_projects WHERE npd_project_id = ?", Boolean.class, project));
	}

	private String stage(long project) {
		return jdbc.queryForObject("SELECT stage FROM npd_projects WHERE npd_project_id = ?", String.class, project);
	}

	private long validatedRfq() throws Exception {
		long id = idOf(call(post("/api/rfqs"), buyer, rfqForm()).andExpect(status().isCreated()));
		call(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		call(post("/api/rfqs/{id}/validate", id), ergoRfq, "{}").andExpect(status().isOk());
		return id;
	}

	/** RFQ -&gt; invitation -&gt; submitted quote -&gt; selected -&gt; sent -&gt; approved by the customer (SRS §14.2-14.4). */
	private long approvedRfq() throws Exception {
		long id = validatedRfq();
		call(post("/api/rfqs/{id}/quotes", id), ergoRfq, invite(supplier)).andExpect(status().isOk());
		long quote = invitationOf(id);
		call(put("/api/quotes/{id}", quote), seller, quote("80")).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", quote), seller, null).andExpect(status().isOk());
		call(post("/api/quotes/{id}/decision", quote), ergoRfq, "{\"decision\": \"SELECT_FOR_CUSTOMER\"}")
			.andExpect(status().isOk());
		call(post("/api/rfqs/{id}/customer-quotes", id), ergoRfq,
				"{\"quoteId\": %d, \"type\": \"SUPPLIER_QUOTE\", \"remarks\": \"ok\"}".formatted(quote))
			.andExpect(status().isOk());
		long sent = JsonPath.<Number>read(call(get("/api/rfqs/{id}/customer-quotes", id), buyer, null).andReturn()
			.getResponse().getContentAsString(), "$.quotes[0].id").longValue();
		call(post("/api/customer-quotes/{id}/respond", sent), buyer, "{\"response\": \"APPROVED\"}")
			.andExpect(status().isOk());
		return id;
	}

	private long order() {
		return jdbc.queryForObject("""
				INSERT INTO orders (order_number, customer_company_id, supplier_company_id, part_number,
				                    ordered_quantity, current_status)
				VALUES (?, ?, ?, 'PN-ORDER', 10, 'ORDER_CREATED') RETURNING order_id""", Long.class,
				"SO" + SEQ.incrementAndGet(), customer, supplier);
	}

	private long npdProject(String stage) {
		long rfq = jdbc.queryForObject("""
				INSERT INTO rfqs (rfq_number, source_type, customer_company_id, created_by_user_id, status, priority,
				                  part_number, currency_id)
				VALUES (?, 'CUSTOMER', ?, ?, 'CUSTOMER_APPROVED', 'NORMAL', 'PN-RFQ', ?) RETURNING rfq_id""", Long.class,
				"SR" + SEQ.incrementAndGet(), customer, ergoManagement.userId(), inr);
		return jdbc.queryForObject("""
				INSERT INTO npd_projects (npd_number, source_rfq_id, customer_company_id, supplier_company_id,
				                          part_number, stage, created_by_user_id)
				VALUES (?, ?, ?, ?, 'PN-NPD', ?, ?) RETURNING npd_project_id""", Long.class, "SN" + SEQ.incrementAndGet(),
				rfq, customer, supplier, stage, ergoManagement.userId());
	}

	private static String inspection(String linkedModule, long linkedRecordId) {
		return """
				{"linkedModule": "%s", "linkedRecordId": %d, "partNumber": "PN-STD", "customerVisible": false}"""
			.formatted(linkedModule, linkedRecordId);
	}

	private String ncr(String linkedModule, long linkedRecordId) {
		return """
				{"linkedModule": "%s", "linkedRecordId": %d, "issueCategory": "Dimensional",
				 "issueDescription": "part out of tolerance", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "ownerUserId": null}"""
			.formatted(linkedModule, linkedRecordId, LocalDate.now().plusDays(30), customer, supplier);
	}

	private String rfqForm() {
		return """
				{"partNumber": "PN-SW1", "description": "Flange", "materialSpec": "EN8", "targetQuoteDate": "%s",
				 "currencyId": %d, "targetProductionPiecePrice": 99.5,
				 "processSteps": [{"stepName": "Forging", "cycleTimeSeconds": 55, "sourcingType": "INHOUSE"},
				                  {"stepName": "Machining"}]}"""
			.formatted(LocalDate.now().plusDays(10), inr);
	}

	private String quote(String price) {
		return """
				{"quoteReference": "Q-77", "validityDate": "%s", "currencyId": %d, "productionPiecePrice": %s,
				 "ppapPiecePrice": 95, "tooling": [{"toolName": "Forging die", "toolingCost": 120000},
				                                   {"toolName": "Fixture", "toolingCost": 30000}],
				 "rawMaterialCost": 40, "processingCost": 20, "overheadCost": 8, "packagingCost": 2, "profit": 6,
				 "moq": 500, "productionLeadTimeDays": 45,
				 "processSteps": [{"stepName": "Forging", "cycleTimeSeconds": 42, "sourcingType": "OUTSOURCED",
				                   "outsourcedSupplierName": "Forge partner"}, {"stepName": "Machining",
				                   "cycleTimeSeconds": 180, "sourcingType": "INHOUSE"}]}"""
			.formatted(LocalDate.now().plusDays(60), inr, price);
	}

	private String invite(long... suppliers) {
		return "{\"supplierIds\": %s, \"responseDueDate\": \"%s\"}".formatted(java.util.Arrays.toString(suppliers),
				LocalDate.now().plusDays(7));
	}

	private long invitationOf(long rfq) throws Exception {
		String number = JsonPath.read(call(get("/api/rfqs/{id}", rfq), ergoRfq, null).andReturn().getResponse()
			.getContentAsString(), "$.number");
		List<Number> ids = JsonPath.read(call(get("/api/quotes"), seller, null).andReturn().getResponse()
			.getContentAsString(), "$[?(@.rfqNumber == '" + number + "')].id");
		return ids.get(0).longValue();
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private static long idOf(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

}
