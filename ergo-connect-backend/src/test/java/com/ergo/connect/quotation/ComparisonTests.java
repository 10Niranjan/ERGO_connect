package com.ergo.connect.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

/** Phase 2c: quote comparison, quotes sent to the customer and the customer's answer (SRS §12.7, §14.4, §17.3). */
@IntegrationTest
class ComparisonTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long customer;

	long supplierX;

	long supplierY;

	long inr;

	/** A currency only these tests use, so its exchange rate cannot disturb other tests. */
	long foreign;

	Login ergoRfq;

	Login buyer;

	Login sellerX;

	Login sellerY;

	long rfqId;

	long x;

	long y;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		customer = users.company("CUSTOMER");
		supplierX = users.company("SUPPLIER");
		supplierY = users.company("SUPPLIER");
		inr = users.currency("INR");
		foreign = users.masterEntry("currencies", "currency");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		buyer = users.login(customer, "CUSTOMER_RFQ");
		sellerX = users.login(supplierX, "SUPPLIER_RFQ");
		sellerY = users.login(supplierY, "SUPPLIER_RFQ");
		rfqId = validatedRfq();
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, """
				{"supplierIds": [%d, %d], "responseDueDate": "%s"}""".formatted(supplierX, supplierY,
				LocalDate.now().plusDays(7))).andExpect(status().isOk());
		x = invitationOf(sellerX);
		y = invitationOf(sellerY);
	}

	@Test
	void ergoComparesSubmittedQuotesInTheRfqCurrencyAndScoresThem() throws Exception {
		submit(x, sellerX, inr, "90");
		call(put("/api/quotes/{id}", y), sellerY, quote(foreign, "1.2")).andExpect(status().isOk()); // draft only

		call(get("/api/rfqs/{id}/comparison", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$.currency.code").value("INR"))
			.andExpect(jsonPath("$.targets.productionPiecePrice").value(99.5))
			.andExpect(jsonPath("$.lines[*].quoteId", contains((int) x))) // drafts are never compared
			.andExpect(jsonPath("$.lines[0].converted.productionPiecePrice").value(90.0))
			.andExpect(jsonPath("$.canEdit").value(true));

		call(post("/api/quotes/{id}/submit", y), sellerY, null).andExpect(status().isOk());
		call(get("/api/rfqs/{id}/comparison", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$.lines[1].quote.currency.id").value((int) foreign))
			.andExpect(jsonPath("$.lines[1].converted.productionPiecePrice").doesNotExist()); // no rate yet
		jdbc.update("""
				INSERT INTO exchange_rates (from_currency_id, to_currency_id, rate_date, exchange_rate, created_by_user_id)
				VALUES (?, ?, ?, 85, ?)""", foreign, inr, LocalDate.now().minusDays(1), ergoRfq.userId());
		call(get("/api/rfqs/{id}/comparison", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$.lines[1].converted.rate").value(85.0))
			.andExpect(jsonPath("$.lines[1].converted.productionPiecePrice").value(102.0));

		String scores = """
				{"scores": {"commercial": 8, "technical": 7, "quality": 9, "risk": 6}, "internalRemarks": "Strong PPAP record"}""";
		call(put("/api/quotes/{id}/evaluation", x), ergoRfq, scores)
			.andExpect(jsonPath("$.lines[0].totalScore").value(7.5))
			.andExpect(jsonPath("$.lines[0].internalRemarks").value("Strong PPAP record"));
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("UNDER_COMPARISON"));
		call(get("/api/rfqs/{id}", rfqId), buyer, null).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
		call(put("/api/quotes/{id}/evaluation", x), ergoRfq, "{\"scores\": {\"commercial\": 11}}")
			.andExpect(status().isBadRequest());
		call(put("/api/rfqs/{id}/comparison", rfqId), ergoRfq,
				"{\"internalSummary\": \"X cheaper\", \"recommendationSummary\": \"Go with X\"}")
			.andExpect(jsonPath("$.recommendationSummary").value("Go with X"));

		// suppliers never see ERGO's evaluation, and still see their quote as submitted
		call(post("/api/quotes/{id}/decision", x), ergoRfq, "{\"decision\": \"SHORTLIST\"}")
			.andExpect(jsonPath("$.status").value("SHORTLISTED"));
		call(get("/api/quotes/{id}", x), sellerX, null)
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(content().string(not(containsString("Strong PPAP record"))))
			.andExpect(content().string(not(containsString("SHORTLISTED"))));
		call(get("/api/quotes"), sellerX, null).andExpect(jsonPath("$[0].status").value("SUBMITTED"));
	}

	@Test
	void onlyErgoRfqAndAdminEvaluateAndOnlyErgoInScopeSeesTheComparison() throws Exception {
		submit(x, sellerX, inr, "90");
		Login management = users.login(users.company("ERGO"), "ERGO_MANAGEMENT");
		Login kam = users.login(users.company("ERGO"), "ERGO_KAM");
		users.assignAccounts(kam.userId(), customer);
		Login otherKam = users.login(users.company("ERGO"), "ERGO_KAM");
		String scores = "{\"scores\": {\"commercial\": 5}}";

		call(get("/api/rfqs/{id}/comparison", rfqId), management, null).andExpect(jsonPath("$.canEdit").value(false));
		call(get("/api/rfqs/{id}/comparison", rfqId), kam, null)
			.andExpect(jsonPath("$.lines[0].actions", not(hasItem("SHORTLIST"))));
		call(get("/api/rfqs/{id}/comparison", rfqId), otherKam, null).andExpect(status().isNotFound());
		Login npd = users.login(users.company("ERGO"), "ERGO_NPD");
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), kam, null).andExpect(status().isOk());
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), otherKam, null).andExpect(status().isNotFound());
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), npd, null).andExpect(status().isNotFound()); // not approved yet
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), users.login(users.company("ERGO"), "ERGO_OFP"), null)
			.andExpect(status().isForbidden());
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), users.login(users.company("ERGO"), "ERGO_QUALITY"), null)
			.andExpect(status().isForbidden());
		for (Login outsider : List.of(sellerX, buyer)) {
			call(get("/api/rfqs/{id}/comparison", rfqId), outsider, null).andExpect(status().isForbidden());
			call(put("/api/quotes/{id}/evaluation", x), outsider, scores).andExpect(status().isForbidden());
		}
		for (Login viewer : List.of(management, kam)) {
			call(put("/api/quotes/{id}/evaluation", x), viewer, scores).andExpect(status().isForbidden());
			call(post("/api/quotes/{id}/decision", x), viewer, "{\"decision\": \"SHORTLIST\"}")
				.andExpect(status().isForbidden());
			call(post("/api/rfqs/{id}/customer-quotes", rfqId), viewer, sendAsIs(x)).andExpect(status().isForbidden());
		}
		call(put("/api/quotes/{id}/evaluation", y), ergoRfq, scores).andExpect(status().isConflict()); // not submitted
	}

	@Test
	void aSelectedSupplierQuoteGoesToTheCustomerAsIsAndTheCustomerApproves() throws Exception {
		submit(x, sellerX, inr, "90");
		submit(y, sellerY, inr, "95");
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, sendAsIs(x)).andExpect(status().isConflict());
		call(post("/api/quotes/{id}/decision", x), ergoRfq, "{\"decision\": \"SELECT_FOR_CUSTOMER\"}")
			.andExpect(jsonPath("$.actions", hasItem("SEND_TO_CUSTOMER")));
		call(post("/api/quotes/{id}/decision", y), ergoRfq, "{\"decision\": \"MARK_NOT_SELECTED\"}")
			.andExpect(status().isUnprocessableContent()); // a reason is required
		call(post("/api/quotes/{id}/decision", y), ergoRfq,
				"{\"decision\": \"MARK_NOT_SELECTED\", \"comment\": \"Higher piece price than X\"}")
			.andExpect(jsonPath("$.status").value("NOT_SELECTED"));
		call(get("/api/quotes/{id}", y), sellerY, null).andExpect(jsonPath("$.status").value("SUBMITTED"));
		assertThat(jdbc.queryForObject("""
				SELECT action_reason FROM audit_logs WHERE entity_name = 'Invitation' AND record_id = ?
				AND field_name = 'status' AND new_value = 'NOT_SELECTED'""", String.class, y))
			.isEqualTo("Higher piece price than X");

		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, sendAsIs(x))
			.andExpect(jsonPath("$.quotes[0].type").value("SUPPLIER_QUOTE"))
			.andExpect(jsonPath("$.quotes[0].status").value("WAITING"));
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("SENT_TO_CUSTOMER"));
		call(get("/api/rfqs/{id}", rfqId), buyer, null).andExpect(jsonPath("$.status").value("QUOTE_RECEIVED"));

		// one quote at a time waits for the customer
		call(post("/api/quotes/{id}/decision", y), ergoRfq, "{\"decision\": \"SELECT_FOR_CUSTOMER\"}");
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, sendAsIs(y)).andExpect(status().isConflict());

		String supplierName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?",
				String.class, supplierX);
		long sent = JsonPath.<Number>read(call(get("/api/rfqs/{id}/customer-quotes", rfqId), buyer, null)
			.andExpect(jsonPath("$.audience").value("CUSTOMER"))
			.andExpect(jsonPath("$.quotes", hasSize(1)))
			.andExpect(jsonPath("$.quotes[0].content.supplierName").value(supplierName)) // client decision: as-is
			.andExpect(jsonPath("$.quotes[0].content.supplierQuote.productionPiecePrice").value(90.0))
			.andExpect(jsonPath("$.quotes[0].content.remarks").value("Best lead time"))
			.andExpect(jsonPath("$.quotes[0].actions", hasItem("APPROVE")))
			.andExpect(content().string(not(containsString("margin"))))
			.andExpect(content().string(not(containsString("supplierVersion"))))
			.andExpect(content().string(not(containsString("95.0")))) // the other supplier's price
			.andReturn().getResponse().getContentAsString(), "$.quotes[0].id").longValue();

		Login otherCustomer = users.login(users.company("CUSTOMER"), "CUSTOMER_RFQ");
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), otherCustomer, null).andExpect(status().isNotFound());
		call(post("/api/customer-quotes/{id}/respond", sent), otherCustomer, "{\"response\": \"APPROVED\"}")
			.andExpect(status().isNotFound());
		Login quality = users.login(customer, "CUSTOMER_QUALITY");
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), quality, null).andExpect(status().isForbidden());
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), sellerX, null).andExpect(status().isForbidden());
		call(post("/api/customer-quotes/{id}/respond", sent), quality, "{\"response\": \"APPROVED\"}")
			.andExpect(status().isForbidden());

		call(post("/api/customer-quotes/{id}/respond", sent), buyer, "{\"response\": \"DECLINED\"}")
			.andExpect(status().isUnprocessableContent()); // declining needs a reason
		call(post("/api/customer-quotes/{id}/respond", sent), buyer, "{\"response\": \"APPROVED\"}")
			.andExpect(jsonPath("$.quotes[0].status").value("APPROVED"))
			.andExpect(jsonPath("$.quotes[0].actions", hasSize(0)));
		call(post("/api/customer-quotes/{id}/respond", sent), buyer, "{\"response\": \"DECLINED\", \"comment\": \"x\"}")
			.andExpect(status().isConflict());
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("CUSTOMER_APPROVED"));
		// approval puts the RFQ where ERGO NPD sees it; it is never converted automatically
		call(get("/api/rfqs/{id}", rfqId), users.login(users.company("ERGO"), "ERGO_NPD"), null)
			.andExpect(status().isOk());

		assertThatThrownBy(() -> jdbc.update("UPDATE customer_quote_submissions SET customer_view = '{}'::jsonb"))
			.hasMessageContaining("cannot be changed");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM customer_quote_submissions")).hasMessageContaining("deleted");
	}

	@Test
	void anErgoQuoteHidesSupplierAndMarginAndARevisionStartsANewRound() throws Exception {
		submit(x, sellerX, inr, "90");
		call(post("/api/quotes/{id}/decision", x), ergoRfq, "{\"decision\": \"SELECT_FOR_CUSTOMER\"}");
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, "{\"quoteId\": %d, \"type\": \"ERGO_QUOTE\"}"
			.formatted(x)).andExpect(status().isUnprocessableContent());
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, sendErgoQuote(x, "110", LocalDate.now().minusDays(1)))
			.andExpect(status().isUnprocessableContent());

		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, sendErgoQuote(x, "110", LocalDate.now().plusDays(30)))
			.andExpect(jsonPath("$.quotes[0].marginAmount").value(20.0))
			.andExpect(jsonPath("$.quotes[0].marginPercentage").value(18.18))
			.andExpect(jsonPath("$.quotes[0].content.ergoQuote.productionPiecePrice").value(110.0));
		String supplierName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?",
				String.class, supplierX);
		long first = JsonPath.<Number>read(call(get("/api/rfqs/{id}/customer-quotes", rfqId), buyer, null)
			.andExpect(jsonPath("$.quotes[0].content.type").value("ERGO_QUOTE"))
			.andExpect(jsonPath("$.quotes[0].content.ergoQuote.currency.code").value("INR"))
			.andExpect(jsonPath("$.quotes[0].content.supplierName").doesNotExist())
			.andExpect(jsonPath("$.quotes[0].content.supplierQuote").doesNotExist())
			.andExpect(content().string(not(containsString(supplierName))))
			.andExpect(content().string(not(containsString("margin"))))
			.andExpect(content().string(not(containsString("90.0")))) // the supplier's price
			.andReturn().getResponse().getContentAsString(), "$.quotes[0].id").longValue();

		call(post("/api/customer-quotes/{id}/respond", first), buyer, "{\"response\": \"REVISION_REQUESTED\"}")
			.andExpect(status().isUnprocessableContent());
		call(post("/api/customer-quotes/{id}/respond", first), buyer,
				"{\"response\": \"REVISION_REQUESTED\", \"comment\": \"Our target is 100\"}")
			.andExpect(jsonPath("$.quotes[0].status").value("REVISION_REQUESTED"));
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$.status").value("CUSTOMER_REVISION_REQUESTED"));

		// this supplier quote was already shown as an ERGO quote; showing it as-is now would reveal the margin
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, sendAsIs(x)).andExpect(status().isConflict());

		// ERGO asks the supplier for a better price and sends a new quote
		call(post("/api/quotes/{id}/reopen", x), ergoRfq, "{\"comment\": \"Customer target is 100\"}")
			.andExpect(jsonPath("$.status").value("REOPENED"));
		call(put("/api/quotes/{id}", x), sellerX, quote(inr, "84")).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", x), sellerX, null).andExpect(jsonPath("$.status").value("RESUBMITTED"));
		call(get("/api/rfqs/{id}/comparison", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$.lines[0].version").value(2))
			.andExpect(jsonPath("$.lines[0].quote.productionPiecePrice").value(84.0));
		call(post("/api/quotes/{id}/decision", x), ergoRfq, "{\"decision\": \"SELECT_FOR_CUSTOMER\"}");
		// the supplier sees its resubmission, never ERGO's internal decision
		call(get("/api/quotes/{id}", x), sellerX, null).andExpect(jsonPath("$.status").value("RESUBMITTED"));
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, sendErgoQuote(x, "100", LocalDate.now().plusDays(30)))
			.andExpect(jsonPath("$.quotes", hasSize(2)))
			.andExpect(jsonPath("$.quotes[0].supplierVersion").value(2))
			.andExpect(jsonPath("$.quotes[0].marginAmount").value(16.0));
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), buyer, null)
			.andExpect(jsonPath("$.quotes[*].status", contains("WAITING", "REVISION_REQUESTED")))
			.andExpect(jsonPath("$.quotes[1].responseComment").value("Our target is 100"));
	}

	// ---------------------------------------------------------------- helpers

	private long validatedRfq() throws Exception {
		long id = JsonPath.<Number>read(call(post("/api/rfqs"), buyer, """
				{"partNumber": "PN-C1", "description": "Bracket", "materialSpec": "EN8", "targetQuoteDate": "%s",
				 "currencyId": %d, "targetProductionPiecePrice": 99.5, "processSteps": [{"stepName": "Casting"}]}"""
			.formatted(LocalDate.now().plusDays(10), inr)).andReturn().getResponse().getContentAsString(), "$.id")
			.longValue();
		call(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		call(post("/api/rfqs/{id}/validate", id), ergoRfq, "{}").andExpect(status().isOk());
		return id;
	}

	private void submit(long quote, Login seller, long currency, String price) throws Exception {
		call(put("/api/quotes/{id}", quote), seller, quote(currency, price)).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", quote), seller, null).andExpect(status().isOk());
	}

	private static String quote(long currency, String price) {
		return """
				{"validityDate": "%s", "currencyId": %d, "productionPiecePrice": %s, "toolingCostTotal": 50000,
				 "rawMaterialCost": 40, "processingCost": 20, "overheadCost": 8, "packagingCost": 2, "profit": 6,
				 "moq": 500, "productionLeadTimeDays": 45}""".formatted(LocalDate.now().plusDays(60), currency, price);
	}

	private static String sendAsIs(long quote) {
		return "{\"quoteId\": %d, \"type\": \"SUPPLIER_QUOTE\", \"remarks\": \"Best lead time\"}".formatted(quote);
	}

	private String sendErgoQuote(long quote, String price, LocalDate validity) {
		return """
				{"quoteId": %d, "type": "ERGO_QUOTE", "remarks": "Incl. inspection", "ergoQuote": {"currencyId": %d,
				 "productionPiecePrice": %s, "toolingCost": 60000, "moq": 500, "productionLeadTimeDays": 50,
				 "validityDate": "%s"}}""".formatted(quote, inr, price, validity);
	}

	private long invitationOf(Login supplier) throws Exception {
		String number = JsonPath.read(call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andReturn().getResponse()
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

}
