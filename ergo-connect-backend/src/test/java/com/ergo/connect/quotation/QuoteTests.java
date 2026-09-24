package com.ergo.connect.quotation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

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
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/** Phase 2b: supplier float and quotes (SRS §5.3, §10.5, §14.2, §14.3, §17.2). */
@IntegrationTest
class QuoteTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long customer;

	long supplierX;

	long supplierY;

	long inr;

	Login ergoRfq;

	Login buyer;

	Login sellerX;

	Login sellerY;

	long rfqId;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		customer = users.company("CUSTOMER");
		supplierX = users.company("SUPPLIER");
		supplierY = users.company("SUPPLIER");
		inr = users.currency("INR");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		buyer = users.login(customer, "CUSTOMER_RFQ");
		sellerX = users.login(supplierX, "SUPPLIER_RFQ");
		sellerY = users.login(supplierY, "SUPPLIER_KAM");
		rfqId = validatedRfq();
	}

	@Test
	void invitingSendsTheRfqAndSuppliersSeeOnlyPartAndProcess() throws Exception {
		long unvalidated = idOf(call(post("/api/rfqs"), buyer, rfqForm()).andReturn());
		call(post("/api/rfqs/{id}/quotes", unvalidated), ergoRfq, invite(supplierX)).andExpect(status().isConflict());

		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, invite(supplierX, supplierY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quotes", hasSize(2)));
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("SENT_TO_SUPPLIERS"));
		call(get("/api/rfqs/{id}", rfqId), buyer, null).andExpect(jsonPath("$.status").value("IN_PROGRESS"));

		long x = invitationOf(sellerX);
		call(get("/api/quotes/{id}", x), sellerX, null)
			.andExpect(jsonPath("$.audience").value("SUPPLIER"))
			.andExpect(jsonPath("$.rfq.partNumber").value("PN-Q1"))
			.andExpect(jsonPath("$.rfq.processSteps[0].stepName").value("Forging"))
			.andExpect(jsonPath("$.rfq.customer").doesNotExist())
			.andExpect(jsonPath("$.rfq.targetProductionPiecePrice").doesNotExist())
			.andExpect(content().string(not(containsString("Test CUSTOMER")))) // the customer's name
			.andExpect(content().string(not(containsString("Keep target secret"))))
			.andExpect(content().string(not(containsString("99.5")))) // the customer's target price
			.andExpect(content().string(not(containsString("Rival Forge Ltd")))) // RFQ step sub-supplier
			.andExpect(content().string(not(containsString("AS-12")))) // RFQ step remark
			.andExpect(jsonPath("$.rfq.processSteps[0].cycleTimeSeconds").doesNotExist());

		call(get("/api/quotes/{id}", invitationOf(sellerY)), sellerX, null).andExpect(status().isNotFound());
		call(get("/api/rfqs"), sellerX, null).andExpect(status().isForbidden()); // never the RFQ module itself
		call(get("/api/quotes"), users.login(supplierX, "SUPPLIER_NPD"), null).andExpect(status().isForbidden());
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, invite(customer)).andExpect(status().isUnprocessableContent());
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, invite(supplierX)).andExpect(status().isUnprocessableContent());
	}

	@Test
	void submissionLocksReopeningNeedsAReasonAndEveryVersionIsKept() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, invite(supplierX, supplierY));
		long x = invitationOf(sellerX);
		long y = invitationOf(sellerY);

		call(put("/api/quotes/{id}", x), sellerX, "{\"productionPiecePrice\": 80}")
			.andExpect(status().isUnprocessableContent()); // money is always tagged with its currency
		call(put("/api/quotes/{id}", x), sellerX, "{\"currencyId\": %d, \"productionPiecePrice\": 80}".formatted(inr))
			.andExpect(jsonPath("$.status").value("DRAFT_SAVED"));
		call(post("/api/quotes/{id}/submit", x), sellerX, null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("cost breakdown")));
		call(get("/api/rfqs/{id}/quotes", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$.quotes[0].productionPiecePrice").doesNotExist()); // drafts stay private

		call(put("/api/quotes/{id}", x), sellerX, quote("81.25")).andExpect(status().isOk())
			.andExpect(jsonPath("$.quote.toolingCostTotal").value(150000.0)); // sum of the tooling lines
		call(post("/api/quotes/{id}/submit", x), sellerX, null).andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.actions", not(hasItem("EDIT"))));
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("QUOTES_PARTIAL"));
		call(put("/api/quotes/{id}", x), sellerX, quote("70")).andExpect(status().isConflict()); // locked

		call(post("/api/quotes/{id}/regret", y), sellerY, "{}").andExpect(status().isUnprocessableContent());
		call(post("/api/quotes/{id}/regret", y), sellerY, "{\"comment\": \"No forging capacity this quarter\"}")
			.andExpect(jsonPath("$.status").value("REGRETTED"));
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("QUOTES_COMPLETE"));

		call(post("/api/quotes/{id}/reopen", x), ergoRfq, "{}").andExpect(status().isUnprocessableContent());
		call(post("/api/quotes/{id}/reopen", x), sellerX, "{\"comment\": \"self\"}").andExpect(status().isForbidden());
		call(post("/api/quotes/{id}/reopen", x), ergoRfq, "{\"comment\": \"Please quote with 2 cavities\"}")
			.andExpect(jsonPath("$.status").value("REOPENED"));
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("QUOTES_PARTIAL"));

		call(put("/api/quotes/{id}", x), sellerX, quote("76.40")).andExpect(status().isOk());
		call(get("/api/quotes/{id}", x), ergoRfq, null) // ERGO still sees what was submitted, not the new draft
			.andExpect(jsonPath("$.quote.productionPiecePrice").value(81.25));
		call(post("/api/quotes/{id}/submit", x), sellerX, null).andExpect(jsonPath("$.status").value("RESUBMITTED"))
			.andExpect(jsonPath("$.version").value(2));

		call(get("/api/quotes/{id}/versions", x), ergoRfq, null)
			.andExpect(jsonPath("$[*].version", contains(1, 2)))
			.andExpect(jsonPath("$[0].quote.productionPiecePrice").value(81.25))
			.andExpect(jsonPath("$[1].quote.productionPiecePrice").value(76.40))
			.andExpect(jsonPath("$[1].reopenReason").value("Please quote with 2 cavities"));
		assertThatThrownBy(() -> jdbc.update("UPDATE supplier_quote_versions SET version_no = 9"))
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.execute("TRUNCATE supplier_quote_versions")).hasMessageContaining("append-only");
	}

	@Test
	void suppliersCanAskErgoAndGetAnAnswer() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, invite(supplierX));
		long x = invitationOf(sellerX);

		call(post("/api/quotes/{id}/request-clarification", x), sellerX, "{\"comment\": \"Is the flange face machined?\"}")
			.andExpect(jsonPath("$.status").value("CLARIFICATION_REQUESTED"));
		call(get("/api/quotes/{id}", x), ergoRfq, null)
			.andExpect(jsonPath("$.clarificationQuestion").value("Is the flange face machined?"))
			.andExpect(jsonPath("$.actions", hasItem("ANSWER_CLARIFICATION")));
		call(post("/api/quotes/{id}/answer-clarification", x), ergoRfq, "{\"comment\": \"Yes, Ra 3.2\"}")
			.andExpect(jsonPath("$.status").value("NOT_STARTED"));
		call(get("/api/quotes/{id}", x), sellerX, null).andExpect(jsonPath("$.clarificationAnswer").value("Yes, Ra 3.2"));
	}

	@Test
	void aSubmittedQuoteCannotBeWithdrawnThroughAClarification() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, invite(supplierX));
		long x = invitationOf(sellerX);
		call(put("/api/quotes/{id}", x), sellerX, quote("90"));
		call(post("/api/quotes/{id}/submit", x), sellerX, null).andExpect(status().isOk());
		call(post("/api/quotes/{id}/reopen", x), ergoRfq, "{\"comment\": \"Check the MOQ\"}").andExpect(status().isOk());
		call(post("/api/quotes/{id}/request-clarification", x), sellerX, "{\"comment\": \"Which MOQ?\"}")
			.andExpect(jsonPath("$.status").value("CLARIFICATION_REQUESTED"))
			.andExpect(jsonPath("$.actions", not(hasItem("REGRET"))));
		call(post("/api/quotes/{id}/regret", x), sellerX, "{\"comment\": \"Withdrawn\"}").andExpect(status().isConflict());
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("QUOTES_PARTIAL"));
	}

	@Test
	void ergoScopeAppliesToQuotesAndCustomersSeeNone() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, invite(supplierX));
		long x = invitationOf(sellerX);
		Login kam = users.login(users.company("ERGO"), "ERGO_KAM");
		users.assignAccounts(kam.userId(), customer);
		Login otherKam = users.login(users.company("ERGO"), "ERGO_KAM");
		Login npd = users.login(users.company("ERGO"), "ERGO_NPD");

		call(get("/api/rfqs/{id}/quotes", rfqId), kam, null).andExpect(jsonPath("$.canInvite").value(false));
		call(post("/api/rfqs/{id}/quotes", rfqId), kam, invite(supplierY)).andExpect(status().isForbidden());
		call(get("/api/rfqs/{id}/quotes", rfqId), otherKam, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}", x), otherKam, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}", x), npd, null).andExpect(status().isNotFound()); // not approved yet
		call(get("/api/rfqs/{id}/quotes", rfqId), buyer, null).andExpect(status().isForbidden());
		call(get("/api/quotes/{id}", x), buyer, null).andExpect(status().isForbidden());
		call(get("/api/quotes"), buyer, null).andExpect(status().isForbidden());

		// view-only ERGO roles never act on quotes
		call(post("/api/quotes/{id}/request-clarification", x), sellerX, "{\"comment\": \"Tolerance?\"}");
		for (Login viewer : List.of(kam, npd)) {
			call(post("/api/quotes/{id}/answer-clarification", x), viewer, "{\"comment\": \"a\"}")
				.andExpect(status().isForbidden());
			call(post("/api/quotes/{id}/reopen", x), viewer, "{\"comment\": \"r\"}").andExpect(status().isForbidden());
			upload("/api/quotes/{id}/documents", x, viewer, "note.pdf").andExpect(status().isForbidden());
		}
	}

	@Test
	void anotherSupplierCannotTouchAQuote() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, invite(supplierX, supplierY));
		long x = invitationOf(sellerX);
		call(put("/api/quotes/{id}", x), sellerX, quote("90")).andExpect(status().isOk());

		call(put("/api/quotes/{id}", x), sellerY, quote("1")).andExpect(status().isNotFound());
		call(post("/api/quotes/{id}/submit", x), sellerY, null).andExpect(status().isNotFound());
		call(post("/api/quotes/{id}/regret", x), sellerY, "{\"comment\": \"sabotage\"}").andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/versions", x), sellerY, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/documents", x), sellerY, null).andExpect(status().isNotFound());
		upload("/api/quotes/{id}/documents", x, sellerY, "y.pdf").andExpect(status().isNotFound());
	}

	@Test
	void suppliersGetOnlyTheFilesErgoSharedAndTheirOwn() throws Exception {
		long shared = idOf(upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "drawing.pdf").andReturn());
		long internal = idOf(upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "costing.xlsx").andReturn()); // INTERNAL
		call(put("/api/rfqs/{id}/documents/{doc}/visibility", rfqId, shared), ergoRfq, "{\"visibility\": \"ALL\"}");
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, invite(supplierX, supplierY));
		long x = invitationOf(sellerX);
		long y = invitationOf(sellerY);

		long sheet = idOf(upload("/api/quotes/{id}/documents", x, sellerX, "x-process-sheet.pdf")
			.andExpect(status().isCreated()).andReturn());
		call(get("/api/quotes/{id}/documents", x), sellerX, null)
			.andExpect(jsonPath("$[*].fileName", containsInAnyOrder("drawing.pdf", "x-process-sheet.pdf")));
		call(get("/api/quotes/{id}/documents", y), sellerY, null)
			.andExpect(jsonPath("$[*].fileName", contains("drawing.pdf")));
		call(get("/api/quotes/{id}/documents/{doc}/content?source=RFQ", x, shared), sellerX, null)
			.andExpect(status().isOk());
		call(get("/api/quotes/{id}/documents/{doc}/content?source=RFQ", x, internal), sellerX, null)
			.andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/documents/{doc}/content", y, sheet), sellerY, null).andExpect(status().isNotFound());

		// a draft file stays with the supplier until it is submitted with the quote
		call(get("/api/quotes/{id}/documents", x), ergoRfq, null).andExpect(jsonPath("$", hasSize(0)));
		call(get("/api/quotes/{id}/documents/{doc}/content", x, sheet), ergoRfq, null).andExpect(status().isNotFound());
		call(put("/api/quotes/{id}", x), sellerX, quote("90"));
		call(post("/api/quotes/{id}/submit", x), sellerX, null).andExpect(status().isOk());
		call(get("/api/quotes/{id}/documents", x), ergoRfq, null)
			.andExpect(jsonPath("$[*].fileName", contains("x-process-sheet.pdf")));
		call(get("/api/quotes/{id}/documents/{doc}/content", x, sheet), ergoRfq, null).andExpect(status().isOk());

		// after a re-open, submitted files stay; new ones are private again until resubmitted
		call(post("/api/quotes/{id}/reopen", x), ergoRfq, "{\"comment\": \"Add the PPAP plan\"}");
		call(delete("/api/quotes/{id}/documents/{doc}", x, sheet), sellerX, null).andExpect(status().isConflict());
		long plan = idOf(upload("/api/quotes/{id}/documents", x, sellerX, "ppap-plan.pdf").andReturn());
		call(get("/api/quotes/{id}/documents", x), ergoRfq, null)
			.andExpect(jsonPath("$[*].fileName", contains("x-process-sheet.pdf")));
		call(post("/api/quotes/{id}/submit", x), sellerX, null).andExpect(status().isOk());
		call(delete("/api/quotes/{id}/documents/{doc}", x, plan), sellerX, null).andExpect(status().isConflict());
		call(get("/api/quotes/{id}/versions", x), ergoRfq, null)
			.andExpect(jsonPath("$[0].files", contains("x-process-sheet.pdf")))
			.andExpect(jsonPath("$[1].files", contains("ppap-plan.pdf", "x-process-sheet.pdf")));
	}

	@Test
	void anRfqCanBeCancelledOnlyBeforeQuotesArriveThenOnlyClosed() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, invite(supplierX));
		long other = validatedRfq();
		call(post("/api/rfqs/{id}/quotes", other), ergoRfq, invite(supplierX));
		call(post("/api/rfqs/{id}/cancel", other), ergoRfq, "{\"comment\": \"Customer withdrew\"}")
			.andExpect(jsonPath("$.status").value("CANCELLED"));

		long x = invitationOf(sellerX, rfqId);
		call(put("/api/quotes/{id}", x), sellerX, quote("90"));
		call(post("/api/quotes/{id}/submit", x), sellerX, null);
		call(post("/api/rfqs/{id}/cancel", rfqId), ergoRfq, "{\"comment\": \"Too late\"}").andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/close", rfqId), ergoRfq, "{\"comment\": \"Customer lost the program\"}")
			.andExpect(jsonPath("$.status").value("CLOSED"));
		Login npd = users.login(users.company("ERGO"), "ERGO_NPD"); // NPD sees approved RFQs only
		call(get("/api/rfqs/{id}", rfqId), npd, null).andExpect(status().isNotFound());
		call(get("/api/rfqs/{id}/quotes", rfqId), npd, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/versions", x), npd, null).andExpect(status().isNotFound());
		call(post("/api/quotes/{id}/reopen", x), ergoRfq, "{\"comment\": \"late\"}").andExpect(status().isConflict());
	}

	// ---------------------------------------------------------------- helpers

	private long validatedRfq() throws Exception {
		long id = idOf(call(post("/api/rfqs"), buyer, rfqForm()).andReturn());
		call(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		call(post("/api/rfqs/{id}/validate", id), ergoRfq, "{}").andExpect(status().isOk());
		return id;
	}

	private String rfqForm() {
		return """
				{"partNumber": "PN-Q1", "description": "Flange", "materialSpec": "EN8", "targetQuoteDate": "%s",
				 "currencyId": %d, "targetProductionPiecePrice": 99.5, "customerRemarks": "Keep target secret",
				 "processSteps": [{"stepName": "Forging", "cycleTimeSeconds": 55, "sourcingType": "OUTSOURCED",
				                   "outsourcedSupplierName": "Rival Forge Ltd", "remarks": "per customer spec AS-12"},
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

	private static String invite(long... suppliers) {
		return "{\"supplierIds\": %s, \"responseDueDate\": \"%s\"}".formatted(
				java.util.Arrays.toString(suppliers), LocalDate.now().plusDays(7));
	}

	private long invitationOf(Login supplier) throws Exception {
		return invitationOf(supplier, rfqId);
	}

	private long invitationOf(Login supplier, long rfq) throws Exception {
		String number = JsonPath.read(call(get("/api/rfqs/{id}", rfq), ergoRfq, null).andReturn().getResponse()
			.getContentAsString(), "$.number");
		List<Number> ids = JsonPath.read(call(get("/api/quotes"), supplier, null).andReturn().getResponse()
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

	private ResultActions upload(String url, long id, Login user, String name) throws Exception {
		return mvc.perform(multipart(url, id)
			.file(new MockMultipartFile("file", name, "application/octet-stream", name.getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

	private static long idOf(org.springframework.test.web.servlet.MvcResult result) throws Exception {
		return JsonPath.<Number>read(result.getResponse().getContentAsString(), "$.id").longValue();
	}

}
