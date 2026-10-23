package com.ergo.connect.quotation;

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
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Flow doc §4-5: ERGO accepts the supplier quotes per part, generates one customer quotation with a line per part
 * (never the supplier's quotation), and the customer answers it: approve, correction required, reject or on hold,
 * each with a comment (SRS §5.3, §14.4).
 */
@IntegrationTest
class CustomerQuotationTests {

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

	String number;

	long x1;

	long x2;

	long y3;

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

		rfqId = TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-C1", "PN-C2", "PN-C3"));
		call(post("/api/rfqs/{id}/submit", rfqId), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", rfqId), ergoRfq, null).andExpect(status().isOk());
		number = TestRfq.numberOf(mvc, ergoRfq, rfqId);
		List<Long> parts = TestRfq.itemIds(mvc, ergoRfq, rfqId);
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq,
				TestRfq.distribution(supplierX, List.of(parts.get(0), parts.get(1)), supplierY, List.of(parts.get(2))))
			.andExpect(status().isOk());
		x1 = TestRfq.quoteOf(mvc, sellerX, number, "PN-C1");
		x2 = TestRfq.quoteOf(mvc, sellerX, number, "PN-C2");
		y3 = TestRfq.quoteOf(mvc, sellerY, number, "PN-C3");
	}

	@Test
	void aGeneratedQuotationCarriesALinePerPartAndTheCustomerNeverSeesTheSupplierOrTheMargin() throws Exception {
		submit(x1, sellerX, "90");
		submit(y3, sellerY, "95");
		// nothing is quoted to the customer before ERGO has accepted it
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, generate(line(x1, "110")))
			.andExpect(status().isConflict());
		call(post("/api/quotes/{id}/accept", x1), ergoRfq, null).andExpect(status().isOk());
		call(post("/api/quotes/{id}/accept", y3), ergoRfq, null).andExpect(status().isOk());
		// a part nobody has quoted yet cannot be in the quotation
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, generate(line(x1, "110"), line(x2, "100")))
			.andExpect(status().isConflict());

		Login kam = users.login(users.company("ERGO"), "ERGO_KAM");
		users.assignAccounts(kam.userId(), customer);
		for (Login viewer : List.of(users.login(users.company("ERGO"), "ERGO_ADMIN"), kam, sellerX, buyer)) {
			call(post("/api/rfqs/{id}/customer-quotes", rfqId), viewer, generate(line(x1, "110")))
				.andExpect(status().isForbidden());
		}
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, generate(line(x1, "110"), line(x1, "111")))
			.andExpect(status().isUnprocessableContent()); // a part twice
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq,
				"{\"lines\": [{\"quoteId\": %d}]}".formatted(x1)).andExpect(status().isBadRequest()); // terms missing
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq,
				"{\"lines\": [{\"quoteId\": %d, \"ergoQuote\": {\"currencyId\": %d, \"productionPiecePrice\": 110, \"validityDate\": \"%s\"}}]}"
					.formatted(x1, inr, LocalDate.now().minusDays(1)))
			.andExpect(status().isUnprocessableContent());

		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, generate(line(x1, "110"), line(y3, "120")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quotations", hasSize(1)))
			.andExpect(jsonPath("$.quotations[0].version").value(1))
			.andExpect(jsonPath("$.quotations[0].status").value("WAITING"))
			.andExpect(jsonPath("$.quotations[0].basis[*].marginAmount", contains(20.0, 25.0)))
			.andExpect(jsonPath("$.quotations[0].basis[*].marginPercentage", contains(18.18, 20.83)));
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("SENT_TO_CUSTOMER"));
		call(get("/api/rfqs/{id}", rfqId), buyer, null).andExpect(jsonPath("$.status").value("QUOTE_RECEIVED"));
		call(get("/api/rfqs/{id}/parts", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$[*].status", contains("WITH_CUSTOMER", "NOT_STARTED", "WITH_CUSTOMER")));
		call(get("/api/rfqs/{id}/parts", rfqId), buyer, null)
			.andExpect(jsonPath("$[*].status", contains("QUOTATION_RECEIVED", "IN_PROGRESS", "QUOTATION_RECEIVED")));

		String supplierName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?",
				String.class, supplierX);
		String otherSupplierName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?",
				String.class, supplierY);
		long sent = JsonPath.<Number>read(call(get("/api/rfqs/{id}/customer-quotes", rfqId), buyer, null)
			.andExpect(jsonPath("$.audience").value("CUSTOMER"))
			.andExpect(jsonPath("$.quotations", hasSize(1)))
			.andExpect(jsonPath("$.quotations[0].content.lines[*].partNumber", contains("PN-C1", "PN-C3")))
			.andExpect(jsonPath("$.quotations[0].content.lines[0].ergoQuote.currency.code").value("INR"))
			.andExpect(jsonPath("$.quotations[0].content.lines[0].ergoQuote.productionPiecePrice").value(110.0))
			.andExpect(jsonPath("$.quotations[0].actions", hasItem("APPROVE")))
			.andExpect(content().string(not(containsString(supplierName))))
			.andExpect(content().string(not(containsString(otherSupplierName))))
			.andExpect(content().string(not(containsString("margin"))))
			.andExpect(content().string(not(containsString("90.0")))) // the suppliers' prices
			.andExpect(content().string(not(containsString("95.0"))))
			.andReturn().getResponse().getContentAsString(), "$.quotations[0].id").longValue();

		// one quotation at a time waits for the customer
		call(post("/api/quotes/{id}/accept", x2), ergoRfq, null).andExpect(status().isConflict()); // never submitted
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, generate(line(x1, "112")))
			.andExpect(status().isConflict());

		// suppliers learn nothing of it, and cannot read the customer quotation
		call(get("/api/quotes/{id}", x1), sellerX, null).andExpect(jsonPath("$.status").value("ACCEPTED"))
			.andExpect(content().string(not(containsString("WITH_CUSTOMER"))));
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), sellerX, null).andExpect(status().isForbidden());

		Login otherCustomer = users.login(users.company("CUSTOMER"), "CUSTOMER_RFQ");
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), otherCustomer, null).andExpect(status().isNotFound());
		call(post("/api/customer-quotes/{id}/respond", sent), otherCustomer, answer("APPROVED", "ok"))
			.andExpect(status().isNotFound());
		Login quality = users.login(customer, "CUSTOMER_QUALITY");
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), quality, null).andExpect(status().isForbidden());
		call(post("/api/customer-quotes/{id}/respond", sent), quality, answer("APPROVED", "ok"))
			.andExpect(status().isForbidden());
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), kam, null).andExpect(status().isOk());
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), users.login(users.company("ERGO"), "ERGO_KAM"), null)
			.andExpect(status().isNotFound()); // another KAM's customer

		call(post("/api/customer-quotes/{id}/respond", sent), buyer, "{\"response\": \"DECLINED\"}")
			.andExpect(status().isUnprocessableContent()); // every decision needs a comment
		call(post("/api/customer-quotes/{id}/respond", sent), buyer, answer("APPROVED", "Go ahead"))
			.andExpect(jsonPath("$.quotations[0].status").value("APPROVED"))
			.andExpect(jsonPath("$.quotations[0].actions", hasSize(0)));
		call(post("/api/customer-quotes/{id}/respond", sent), buyer, answer("DECLINED", "x"))
			.andExpect(status().isConflict());
		// the parts that were quoted are approved; the second is still with its supplier
		call(get("/api/rfqs/{id}/parts", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$[*].status", contains("CUSTOMER_APPROVED", "NOT_STARTED", "CUSTOMER_APPROVED")));
		call(get("/api/rfqs/{id}/parts", rfqId), buyer, null)
			.andExpect(jsonPath("$[*].status", contains("APPROVED", "IN_PROGRESS", "APPROVED")));
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("QUOTES_PARTIAL"));

		// both the supplier quotes and the generated quotation are kept, untouched
		assertThatThrownBy(() -> jdbc.update("UPDATE customer_quote_submissions SET customer_view = '{}'::jsonb"))
			.hasMessageContaining("cannot be changed");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM customer_quote_submissions")).hasMessageContaining("deleted");
		assertThatThrownBy(() -> jdbc.update("UPDATE customer_quote_lines SET quoted_production_piece_price = 1"))
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM customer_quote_lines")).hasMessageContaining("append-only");
		call(get("/api/quotes/{id}/versions", x1), ergoRfq, null).andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void aCustomerCanPutAQuotationOnHoldAndAnswerItLater() throws Exception {
		long sent = generated();

		// every decision carries a comment
		for (String response : List.of("APPROVED", "ON_HOLD", "DECLINED", "REVISION_REQUESTED")) {
			call(post("/api/customer-quotes/{id}/respond", sent), buyer, "{\"response\": \"%s\"}".formatted(response))
				.andExpect(status().isUnprocessableContent());
		}
		call(post("/api/customer-quotes/{id}/respond", sent), buyer, answer("ON_HOLD", "Budget review next month"))
			.andExpect(jsonPath("$.quotations[0].status").value("ON_HOLD"))
			.andExpect(jsonPath("$.quotations[0].actions", hasItem("APPROVE")))
			.andExpect(jsonPath("$.quotations[0].actions", not(hasItem("PUT_ON_HOLD"))));
		call(get("/api/rfqs/{id}", rfqId), buyer, null).andExpect(jsonPath("$.status").value("ON_HOLD"));
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("CUSTOMER_ON_HOLD"));
		call(get("/api/rfqs/{id}/parts", rfqId), buyer, null).andExpect(jsonPath("$[0].status").value("ON_HOLD"));
		call(post("/api/customer-quotes/{id}/respond", sent), buyer, answer("ON_HOLD", "x"))
			.andExpect(status().isConflict());
		// the quotation still waits for the customer: ERGO cannot generate another one, nor send its parts back
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, generate(line(x1, "115")))
			.andExpect(status().isConflict());
		call(post("/api/quotes/{id}/revision", x1), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isConflict());

		call(post("/api/customer-quotes/{id}/respond", sent), buyer, answer("APPROVED", "Budget approved"))
			.andExpect(jsonPath("$.quotations[0].status").value("APPROVED"));
		call(post("/api/customer-quotes/{id}/respond", sent), buyer, answer("DECLINED", "x"))
			.andExpect(status().isConflict());
	}

	@Test
	void aCorrectionComesBackToErgoWhoRegeneratesTheQuotation() throws Exception {
		long first = generated();
		call(post("/api/customer-quotes/{id}/respond", first), buyer, answer("REVISION_REQUESTED", "Our target is 100"))
			.andExpect(jsonPath("$.quotations[0].status").value("REVISION_REQUESTED"));
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$.status").value("CUSTOMER_REVISION_REQUESTED"));
		call(get("/api/rfqs/{id}/parts", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$[0].status").value("CUSTOMER_CORRECTION"));
		call(get("/api/rfqs/{id}/parts", rfqId), buyer, null)
			.andExpect(jsonPath("$[0].status").value("CORRECTION_REQUIRED"));

		// "necessary revisions through the RFQ workflow": ERGO can go back to the supplier ...
		call(post("/api/quotes/{id}/revision", x1), ergoRfq, "{\"comment\": \"Customer target is 100\"}")
			.andExpect(jsonPath("$.status").value("REVISION_REQUIRED"));
		call(put("/api/quotes/{id}", x1), sellerX, quote("84")).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", x1), sellerX, null).andExpect(jsonPath("$.status").value("RESUBMITTED"));
		call(post("/api/quotes/{id}/accept", x1), ergoRfq, null).andExpect(status().isOk());

		// ... and generates the quotation again
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, generate(line(x1, "100")))
			.andExpect(jsonPath("$.quotations", hasSize(2)))
			.andExpect(jsonPath("$.quotations[0].version").value(2))
			.andExpect(jsonPath("$.quotations[0].basis[0].supplierVersion").value(2))
			.andExpect(jsonPath("$.quotations[0].basis[0].marginAmount").value(16.0));
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), buyer, null)
			.andExpect(jsonPath("$.quotations[*].status", contains("WAITING", "REVISION_REQUESTED")))
			.andExpect(jsonPath("$.quotations[1].responseComment").value("Our target is 100"));
	}

	@Test
	void whenEveryPartIsRejectedTheRfqIsDeclined() throws Exception {
		submit(x2, sellerX, "92");
		call(post("/api/quotes/{id}/accept", x2), ergoRfq, null).andExpect(status().isOk());
		call(post("/api/quotes/{id}/accept", y3), ergoRfq, null).andExpect(status().isConflict()); // not submitted
		submit(y3, sellerY, "95");
		call(post("/api/quotes/{id}/accept", y3), ergoRfq, null).andExpect(status().isOk());
		submit(x1, sellerX, "90");
		call(post("/api/quotes/{id}/accept", x1), ergoRfq, null).andExpect(status().isOk());
		long sent = idOfLatest(call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq,
				generate(line(x1, "110"), line(x2, "112"), line(y3, "120"))).andReturn());

		call(post("/api/customer-quotes/{id}/respond", sent), buyer, answer("DECLINED", "Too expensive"))
			.andExpect(jsonPath("$.quotations[0].status").value("DECLINED"));
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("CUSTOMER_DECLINED"));
		call(get("/api/rfqs/{id}/parts", rfqId), buyer, null)
			.andExpect(jsonPath("$[*].status", contains("REJECTED", "REJECTED", "REJECTED")));
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$.quotations[0].responseComment").value("Too expensive"));
	}

	@Test
	void onlyTheApprovingCustomerRoleApprovesRejectsOrHolds() throws Exception {
		long sent = generated();
		// Customer Management is inactive in the roles table for the MVP (V2), so it cannot sign in; switch it on
		// for this check only, so the sheet's "A, not CO" cell is still exercised.
		jdbc.update("UPDATE roles SET active = true WHERE role_code = 'CUSTOMER_MANAGEMENT'");
		try {
			for (String role : List.of("CUSTOMER_KAM", "CUSTOMER_MANAGEMENT")) {
				Login limited = users.login(customer, role);
				for (String response : List.of("APPROVED", "DECLINED", "ON_HOLD")) {
					call(post("/api/customer-quotes/{id}/respond", sent), limited, answer(response, "x"))
						.andExpect(status().isForbidden());
				}
			}
		}
		finally {
			jdbc.update("UPDATE roles SET active = false WHERE role_code = 'CUSTOMER_MANAGEMENT'");
		}
		call(get("/api/rfqs/{id}/customer-quotes", rfqId), buyer, null)
			.andExpect(jsonPath("$.quotations[0].actions", hasItem("APPROVE")));
	}

	// ---------------------------------------------------------------- helpers

	/** Part C1 submitted, accepted and quoted to the customer; returns the quotation's id. */
	private long generated() throws Exception {
		submit(x1, sellerX, "90");
		call(post("/api/quotes/{id}/accept", x1), ergoRfq, null).andExpect(status().isOk());
		return idOfLatest(
				call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq, generate(line(x1, "110"))).andReturn());
	}

	private long idOfLatest(org.springframework.test.web.servlet.MvcResult result) throws Exception {
		return JsonPath.<Number>read(result.getResponse().getContentAsString(), "$.quotations[0].id").longValue();
	}

	private void submit(long quote, Login seller, String price) throws Exception {
		call(put("/api/quotes/{id}", quote), seller, quote(price)).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", quote), seller, null).andExpect(status().isOk());
	}

	private String quote(String price) {
		return """
				{"validityDate": "%s", "currencyId": %d, "productionPiecePrice": %s, "toolingCostTotal": 50000,
				 "rawMaterialCost": 40, "processingCost": 20, "overheadCost": 8, "packagingCost": 2, "profit": 6,
				 "moq": 500, "productionLeadTimeDays": 45}"""
			.formatted(LocalDate.now().plusDays(60), inr, price);
	}

	/** One line of the quotation: the part's quote and ERGO's own price for it. */
	private String line(long quote, String price) {
		return """
				{"quoteId": %d, "ergoQuote": {"currencyId": %d, "productionPiecePrice": %s, "toolingCost": 60000,
				 "moq": 500, "productionLeadTimeDays": 50, "validityDate": "%s"}}"""
			.formatted(quote, inr, price, LocalDate.now().plusDays(30));
	}

	private static String generate(String... lines) {
		return "{\"remarks\": \"Incl. inspection\", \"lines\": [%s]}".formatted(String.join(", ", lines));
	}

	private static String answer(String response, String comment) {
		return "{\"response\": \"%s\", \"comment\": \"%s\"}".formatted(response, comment);
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

}
