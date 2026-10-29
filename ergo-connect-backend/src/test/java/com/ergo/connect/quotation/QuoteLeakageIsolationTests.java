package com.ergo.connect.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.AdversarialSupport;
import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Who sees what (flow doc "data visibility control"): no supplier data to a customer, no customer data to a supplier,
 * nothing of another customer's or supplier's, ERGO's margin and internal notes to nobody outside ERGO - checked on
 * every response an audience can reach (records, lists, versions, documents, notifications, dashboard, error bodies),
 * with sentinel values that only occur in the data under protection.
 */
@IntegrationTest
class QuoteLeakageIsolationTests extends AdversarialSupport {

	/** Everything the scenario creates, with the sentinel strings that must stay on their side. */
	record Scn(long rfq, String number, List<Long> parts, long x1, long x2, long y3, long quotationA, long quotationB,
			long internalDoc, long sharedDoc, long customerDoc, String customerName, String customerCode, String xName,
			String xCode, String yName, String yCode) {
	}

	private String nameOf(long company) {
		return jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?", String.class, company);
	}

	private String codeOf(long company) {
		return jdbc.queryForObject("SELECT company_code FROM companies WHERE company_id = ?", String.class, company);
	}

	private static String part(String number, boolean sentinels) {
		return """
				{"partNumber": "%s", "description": "Flange", "materialSpec": "EN8", "targetProductionPiecePrice": 77.7777,
				 "targetToolingCost": 55555.5, "processSteps": [{"stepName": "Forge", "cycleTimeSeconds": 12.34,
				 "sourcingType": "OUTSOURCED", "outsourcedSupplierName": "SECRET-SUBSUPPLIER", "remarks": "SECRET-STEP-REMARK"}]}"""
			.formatted(number);
	}

	private Scn build() throws Exception {
		String form = """
				{"targetQuoteDate": "%s", "currencyId": %d, "customerRemarks": "SECRET-CUSTOMER-REMARK", "items": [%s, %s, %s]}"""
			.formatted(LocalDate.now().plusDays(10), inr, part("PN-LK1", true), part("PN-LK2", true), part("PN-LK3", true));
		long rfq = TestRfq.create(mvc, buyer, form);
		call(post("/api/rfqs/{id}/submit", rfq), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", rfq), ergoRfq, null).andExpect(status().isOk());
		call(put("/api/rfqs/{id}/internal-notes", rfq), ergoRfq, "{\"internalNotes\": \"SECRET-INTERNAL-NOTE\"}")
			.andExpect(status().isOk());
		List<Long> p = parts(rfq);
		long customerDoc = idOf(upload("/api/rfqs/{id}/documents", rfq, buyer, "customer-drawing.pdf").andReturn());
		long internalDoc = idOf(upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "costing-internal.xlsx").andReturn());
		long sharedDoc = idOf(upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "shared-drawing.pdf").andReturn());
		call(put("/api/rfqs/{id}/documents/{doc}/visibility", rfq, sharedDoc), ergoRfq,
				"{\"visibility\": \"ALL\", \"itemIds\": [%d]}".formatted(p.get(0))).andExpect(status().isOk());
		// supplier Y first, so that X's global sub-RFQ number is 2 while X must see 1
		distribute(rfq, supplierY, List.of(p.get(2)), supplierX, List.of(p.get(0), p.get(1)));
		long x1 = quoteOf(sellerX, rfq, "PN-LK1");
		long x2 = quoteOf(sellerX, rfq, "PN-LK2");
		long y3 = quoteOf(sellerY, rfq, "PN-LK3");
		String xBody = TestRfq.supplierQuote(inr, "71.7171").replace("\"moq\": 500", "\"remarks\": \"X-SUPPLIER-REMARK\", \"moq\": 500");
		upload("/api/quotes/{id}/documents", x1, sellerX, "x-process-sheet.pdf").andExpect(status().isCreated());
		for (long quote : List.of(x1, x2)) {
			call(put("/api/quotes/{id}", quote), sellerX, xBody).andExpect(status().isOk());
			call(post("/api/quotes/{id}/submit", quote), sellerX, null).andExpect(status().isOk());
		}
		call(put("/api/quotes/{id}", y3), sellerY, TestRfq.supplierQuote(inr, "82.8282")).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", y3), sellerY, null).andExpect(status().isOk());
		for (long quote : List.of(x1, x2, y3)) {
			accept(quote);
		}
		String generated = "{\"remarks\": \"CUSTOMER-FACING-NOTE\", \"lines\": [%s, %s]}".formatted(line(x1, "123.4321"),
				line(y3, "130.5555"));
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generated).andExpect(status().isOk());
		long quotationA = latestQuotation(rfq);
		call(post("/api/customer-quotes/{id}/respond", quotationA), buyer, answer("REVISION_REQUESTED", "CUSTOMER-COMMENT-SENTINEL"))
			.andExpect(status().isOk());
		call(post("/api/quotes/{id}/revision", x1), ergoRfq, "{\"comment\": \"ERGO-REVISION-COMMENT\"}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(line(x2, "123.4321"))).andExpect(status().isOk());
		long quotationB = latestQuotation(rfq);
		return new Scn(rfq, TestRfq.numberOf(mvc, ergoRfq, rfq), p, x1, x2, y3, quotationA, quotationB, internalDoc,
				sharedDoc, customerDoc, nameOf(customer), codeOf(customer), nameOf(supplierX), codeOf(supplierX),
				nameOf(supplierY), codeOf(supplierY));
	}

	/** Fetches every URL as {@code who} and returns url -> response body (status must be one of the allowed). */
	private Map<String, String> fetch(Login who, List<String> urls) throws Exception {
		Map<String, String> bodies = new LinkedHashMap<>();
		for (String url : urls) {
			var response = call(get(url), who, null).andReturn().getResponse();
			bodies.put(url + " [" + response.getStatus() + "]", response.getContentAsString(StandardCharsets.UTF_8));
		}
		return bodies;
	}

	private static void assertNone(Map<String, String> bodies, String audience, String... forbidden) {
		for (var entry : bodies.entrySet()) {
			for (String token : forbidden) {
				assertThat(entry.getValue()).as("%s must not receive '%s' from %s", audience, token, entry.getKey())
					.doesNotContain(token);
			}
		}
	}

	// ---------------------------------------------------------------- suppliers

	@Test
	void aSupplierNeverGetsTheCustomerErgosMarginOrAnotherSuppliersDataOnAnyResponse() throws Exception {
		Scn s = build();
		List<String> urls = new ArrayList<>(List.of("/api/quotes", "/api/quotes/" + s.x1, "/api/quotes/" + s.x2,
				"/api/quotes/" + s.x1 + "/versions", "/api/quotes/" + s.x1 + "/documents", "/api/quotes/" + s.x2 + "/documents",
				"/api/notifications?size=100", "/api/dashboard", "/api/documents?size=100",
				"/api/documents/" + s.sharedDoc, "/api/documents/" + s.sharedDoc + "/content",
				"/api/quotes/" + s.x1 + "/documents/" + s.sharedDoc + "/content?source=RFQ",
				// everything that is not X's, and everything that is ERGO's or the customer's: refused, with a tidy body
				"/api/quotes/" + s.y3, "/api/quotes/" + s.y3 + "/versions", "/api/documents/" + s.internalDoc,
				"/api/documents/" + s.customerDoc, "/api/documents/" + s.internalDoc + "/content",
				"/api/quotes/" + s.x1 + "/documents/" + s.internalDoc + "/content?source=RFQ",
				"/api/rfqs", "/api/rfqs/" + s.rfq, "/api/rfqs/" + s.rfq + "/parts", "/api/rfqs/" + s.rfq + "/quotes",
				"/api/rfqs/" + s.rfq + "/customer-quotes", "/api/rfqs/" + s.rfq + "/history",
				"/api/rfqs/" + s.rfq + "/documents"));
		Map<String, String> bodies = fetch(sellerX, urls);
		assertNone(bodies, "supplier X", s.customerName, s.customerCode, "SECRET-INTERNAL-NOTE", "SECRET-CUSTOMER-REMARK",
				"77.7777", "55555.5", "SECRET-SUBSUPPLIER", "SECRET-STEP-REMARK", s.yName, s.yCode, "PN-LK3", "82.8282",
				"123.4321", "130.5555", "CUSTOMER-COMMENT-SENTINEL", "CUSTOMER-FACING-NOTE", "margin", "Hidden",
				"customerView", "ergoQuote", "WITH_CUSTOMER", "CUSTOMER_ON_HOLD", "CUSTOMER_CORRECTION", "CUSTOMER_APPROVED",
				"CUSTOMER_REJECTED", "costing-internal.xlsx", "customer-drawing.pdf", "\"customer\"", "targetProduction",
				"internalNotes");
		// what X is meant to see is there (so the scan above is not vacuous) - and its own numbering, not the global one
		String own = bodies.entrySet().stream().filter(e -> e.getKey().startsWith("/api/quotes/" + s.x1 + " ")).findFirst().orElseThrow().getValue();
		assertThat(own).contains("PN-LK1", "X-SUPPLIER-REMARK", "ERGO-REVISION-COMMENT", "\"subRfqNo\":1");
		assertThat(bodies.get("/api/quotes [200]")).contains("PN-LK1", "PN-LK2").contains("\"subRfqNo\":1").doesNotContain("\"subRfqNo\":2");
		assertThat(bodies.get("/api/quotes/" + s.x1 + "/documents [200]")).contains("shared-drawing.pdf", "x-process-sheet.pdf");
		assertThat(bodies.get("/api/quotes/" + s.x2 + "/documents [200]")).doesNotContain("shared-drawing.pdf"); // shared for part 1 only

		// the same for the supplier of the other part: not X's parts, prices, files; not the part-1 drawing
		Map<String, String> y = fetch(sellerY, List.of("/api/quotes", "/api/quotes/" + s.y3, "/api/quotes/" + s.y3 + "/versions",
				"/api/quotes/" + s.y3 + "/documents", "/api/notifications?size=100", "/api/dashboard", "/api/documents?size=100",
				"/api/documents/" + s.sharedDoc, "/api/quotes/" + s.x1, "/api/quotes/" + s.x1 + "/versions"));
		assertNone(y, "supplier Y", s.customerName, s.customerCode, "SECRET-INTERNAL-NOTE", "SECRET-CUSTOMER-REMARK",
				"77.7777", "55555.5", "SECRET-SUBSUPPLIER", "SECRET-STEP-REMARK", s.xName, s.xCode, "PN-LK1", "PN-LK2",
				"71.7171", "X-SUPPLIER-REMARK", "123.4321", "130.5555", "CUSTOMER-COMMENT-SENTINEL", "CUSTOMER-FACING-NOTE",
				"margin", "Hidden", "customerView", "ergoQuote", "WITH_CUSTOMER", "CUSTOMER_CORRECTION", "shared-drawing.pdf",
				"x-process-sheet.pdf", "ERGO-REVISION-COMMENT");
		assertThat(y.get("/api/quotes/" + s.y3 + " [200]")).contains("PN-LK3").contains("\"subRfqNo\":1");
	}

	@Test
	void aSupplierWhoseRoleHasNoQuotationAccessAlsoNeverSeesCustomerErgoOrOtherSupplierData() throws Exception {
		Scn s = build();
		Login quality = users.login(supplierX, "SUPPLIER_QUALITY");
		Map<String, String> bodies = fetch(quality, List.of("/api/quotes", "/api/quotes/" + s.x1, "/api/quotes/" + s.x2,
				"/api/notifications?size=100", "/api/dashboard", "/api/documents?size=100"));
		assertNone(bodies, "supplier quality", s.customerName, s.customerCode, "SECRET-INTERNAL-NOTE", "SECRET-CUSTOMER-REMARK",
				"77.7777", "SECRET-SUBSUPPLIER", s.yName, "PN-LK3", "82.8282", "123.4321", "margin", "Hidden", "ergoQuote",
				"X-SUPPLIER-REMARK", "71.7171", "ERGO-REVISION-COMMENT");
	}

	// ---------------------------------------------------------------- customers

	@Test
	void aCustomerNeverGetsSupplierDataErgoMarginInternalNotesOrErgoOnlyStatusesOnAnyResponse() throws Exception {
		Scn s = build();
		List<String> urls = List.of("/api/rfqs", "/api/rfqs/" + s.rfq, "/api/rfqs/" + s.rfq + "/parts",
				"/api/rfqs/" + s.rfq + "/customer-quotes", "/api/rfqs/" + s.rfq + "/documents",
				"/api/rfqs/" + s.rfq + "/documents/" + s.customerDoc + "/content", "/api/notifications?size=100", "/api/dashboard",
				"/api/documents?size=100", "/api/documents/" + s.customerDoc,
				// refused: the quote side, history, ERGO's and the suppliers' files
				"/api/quotes/" + s.x1, "/api/quotes", "/api/rfqs/" + s.rfq + "/quotes", "/api/rfqs/" + s.rfq + "/history",
				"/api/documents/" + s.internalDoc, "/api/documents/" + s.sharedDoc,
				"/api/rfqs/" + s.rfq + "/documents/" + s.internalDoc + "/content", "/api/rfqs/" + s.rfq + "/documents/" + s.sharedDoc + "/content");
		Map<String, String> bodies = fetch(buyer, urls);
		assertNone(bodies, "customer", s.xName, s.xCode, s.yName, s.yCode, "71.7171", "82.8282", "X-SUPPLIER-REMARK",
				"SECRET-INTERNAL-NOTE", "internalNotes", "costing-internal.xlsx", "marginAmount", "marginPercentage", "Hidden",
				"supplierQuoteVersion", "assignmentId", "\"basis\"", "subRfqNo", "recallReason", "SENT_TO_SUPPLIERS",
				"QUOTES_PARTIAL", "QUOTES_COMPLETE", "UNASSIGNED", "NOT_STARTED", "DRAFT_SAVED", "REVISION_REQUIRED",
				"RESUBMITTED", "\"ACCEPTED\"", "ERGO-REVISION-COMMENT", "x-process-sheet.pdf",
				"CUSTOMER_CORRECTION", "WITH_CUSTOMER", "CUSTOMER_APPROVED", "CUSTOMER_REJECTED", "CUSTOMER_ON_HOLD");
		// the customer does see its own data and the quotation as sent (so the scan is not vacuous)
		assertThat(bodies.get("/api/rfqs/" + s.rfq + "/customer-quotes [200]")).contains("123.4321", "CUSTOMER-FACING-NOTE", "CUSTOMER-COMMENT-SENTINEL");
		assertThat(bodies.get("/api/rfqs/" + s.rfq + " [200]")).contains("SECRET-CUSTOMER-REMARK", "77.7777");
		// part-level: each part has a customer status, one of the collapsed set
		call(get("/api/rfqs/{id}/parts", s.rfq), buyer, null)
			.andExpect(jsonPath("$[*].status", hasItem("CORRECTION_REQUIRED")))
			.andExpect(jsonPath("$[*].status", hasItem("QUOTATION_RECEIVED")));
		// the frozen customer_view the customer holds names no supplier and carries no ERGO-only field
		String frozen = jdbc.queryForObject("SELECT customer_view::text FROM customer_quote_submissions WHERE customer_quote_submission_id = ?",
				String.class, s.quotationA);
		assertThat(frozen).doesNotContain(s.xName, s.yName, s.xCode, s.yCode, "71.7171", "82.8282", "margin", "Hidden", "supplier",
				"SECRET-INTERNAL-NOTE");
	}

	@Test
	void ergoSeesTheMarginAndTheBasisWhichIsWhyNoExternalCallerMustGetTheErgoViewOfAnything() throws Exception {
		Scn s = build();
		// the positive control for the two tests above: the ERGO-side responses do carry what is protected
		String ergo = text(get("/api/rfqs/{id}/customer-quotes", s.rfq), ergoRfq, null);
		assertThat(ergo).contains("marginAmount", s.xName, "supplierVersion");
		call(get("/api/rfqs/{id}", s.rfq), ergoRfq, null).andExpect(jsonPath("$.internalNotes").value("SECRET-INTERNAL-NOTE"));
		call(get("/api/quotes/{id}", s.x1), ergoRfq, null).andExpect(jsonPath("$.supplier.name").value(s.xName));
	}

	// ---------------------------------------------------------------- strangers

	@Test
	void anotherCustomerAnotherSupplierAndAnUnassignedKamSeeNothingOfTheScenarioAnywhere() throws Exception {
		Scn s = build();
		Login kam = users.login(ergoCo, "ERGO_KAM"); // no accounts assigned
		Login npd = users.login(ergoCo, "ERGO_NPD"); // sees an RFQ only once it is approved
		Login customerNpdB = users.login(customerB, "CUSTOMER_NPD");
		List<String> lists = List.of("/api/rfqs", "/api/quotes", "/api/notifications?size=100", "/api/dashboard", "/api/documents?size=100");
		for (Login stranger : List.of(buyerB, customerNpdB, sellerZ, kam, npd)) {
			Map<String, String> bodies = fetch(stranger, lists);
			assertNone(bodies, "stranger", s.number, "PN-LK", s.customerName, s.customerCode, s.xName, s.xCode, s.yName, s.yCode,
					"SECRET", "71.7171", "82.8282", "123.4321", "130.5555", "77.7777", "CUSTOMER-COMMENT-SENTINEL",
					"shared-drawing.pdf", "customer-drawing.pdf", "costing-internal.xlsx", "x-process-sheet.pdf", "marginAmount");
		}
		call(get("/api/quotes"), sellerZ, null).andExpect(jsonPath("$.items", hasSize(0)));
		call(get("/api/rfqs"), buyerB, null).andExpect(jsonPath("$.items[*].id", not(hasItem((int) s.rfq))));
		call(get("/api/rfqs"), kam, null).andExpect(jsonPath("$.items", hasSize(0)));
		call(get("/api/rfqs"), npd, null).andExpect(jsonPath("$.items[*].id", not(hasItem((int) s.rfq))));
	}

	@Test
	void askingForSomeoneElsesRecordByIdIsIndistinguishableFromAskingForOneThatDoesNotExist() throws Exception {
		Scn s = build();
		Login kam = users.login(ergoCo, "ERGO_KAM");
		Login npd = users.login(ergoCo, "ERGO_NPD");
		Login customerKamB = users.login(customerB, "CUSTOMER_KAM");
		long missing = 987654321L;
		List<String> problems = new ArrayList<>();
		java.util.Set<Integer> seen = new java.util.TreeSet<>();
		for (var who : Map.<String, Login>of("customer B", buyerB, "customer KAM B", customerKamB, "supplier Z", sellerZ,
				"supplier Y (other part)", sellerY, "unassigned KAM", kam, "NPD before approval", npd).entrySet()) {
			Login user = who.getValue();
			Map<String, Function<Long, MockHttpServletRequestBuilder>> byRfq = new LinkedHashMap<>();
			byRfq.put("GET rfq", id -> get("/api/rfqs/{id}", id));
			byRfq.put("GET history", id -> get("/api/rfqs/{id}/history", id));
			byRfq.put("GET parts", id -> get("/api/rfqs/{id}/parts", id));
			byRfq.put("GET quotes", id -> get("/api/rfqs/{id}/quotes", id));
			byRfq.put("GET customer-quotes", id -> get("/api/rfqs/{id}/customer-quotes", id));
			byRfq.put("GET documents", id -> get("/api/rfqs/{id}/documents", id));
			byRfq.put("PUT rfq", id -> put("/api/rfqs/{id}", id));
			byRfq.put("POST submit", id -> post("/api/rfqs/{id}/submit", id));
			byRfq.put("POST cancel", id -> post("/api/rfqs/{id}/cancel", id));
			byRfq.put("POST clone", id -> post("/api/rfqs/{id}/clone", id));
			byRfq.put("POST distribute", id -> post("/api/rfqs/{id}/quotes", id));
			byRfq.put("POST generate", id -> post("/api/rfqs/{id}/customer-quotes", id));
			byRfq.put("PUT notes", id -> put("/api/rfqs/{id}/internal-notes", id));
			Map<String, Function<Long, MockHttpServletRequestBuilder>> byQuote = new LinkedHashMap<>();
			byQuote.put("GET quote", id -> get("/api/quotes/{id}", id));
			byQuote.put("PUT quote", id -> put("/api/quotes/{id}", id));
			byQuote.put("POST submit quote", id -> post("/api/quotes/{id}/submit", id));
			byQuote.put("POST accept", id -> post("/api/quotes/{id}/accept", id));
			byQuote.put("POST revision", id -> post("/api/quotes/{id}/revision", id));
			byQuote.put("POST recall", id -> post("/api/quotes/{id}/recall", id));
			byQuote.put("GET versions", id -> get("/api/quotes/{id}/versions", id));
			byQuote.put("GET quote documents", id -> get("/api/quotes/{id}/documents", id));
			byQuote.put("DELETE quote doc", id -> delete("/api/quotes/{id}/documents/{d}", id, s.sharedDoc));
			byQuote.put("GET quote doc content", id -> get("/api/quotes/{id}/documents/{d}/content?source=RFQ", id, s.sharedDoc));
			Map<String, Function<Long, MockHttpServletRequestBuilder>> byQuotation = Map.of("POST respond",
					id -> post("/api/customer-quotes/{id}/respond", id));
			Map<String, Function<Long, MockHttpServletRequestBuilder>> byDoc = new LinkedHashMap<>();
			byDoc.put("GET document", id -> get("/api/documents/{id}", id));
			byDoc.put("GET document content", id -> get("/api/documents/{id}/content", id));
			byDoc.put("PATCH document", id -> patch("/api/documents/{id}", id));
			for (var group : List.of(Map.entry(byRfq, s.rfq), Map.entry(byQuote, s.x1), Map.entry(byQuotation, s.quotationA),
					Map.entry(byDoc, s.sharedDoc))) {
				for (var request : group.getKey().entrySet()) {
					int real = statusWithBody(request.getValue().apply(group.getValue()), user);
					int none = statusWithBody(request.getValue().apply(missing), user);
					seen.add(real);
					if (real >= 500 || real != none || real == 200 || real == 201 || real == 204) {
						problems.add("%s: %s -> %d for the real record but %d for a missing one".formatted(who.getKey(),
								request.getKey(), real, none));
					}
				}
			}
		}
		assertThat(problems).as("every probe of a record outside the caller's scope answers like a missing record").isEmpty();
		assertThat(seen).as("a probe answered by body validation or anything but a scope refusal says nothing about scope").containsOnly(403, 404);
		// and nothing was changed by the probes
		assertThat(rfqStatus(s.rfq)).isEqualTo("SENT_TO_CUSTOMER");
		assertThat(statusOf(s.x2)).isEqualTo("WITH_CUSTOMER");
		assertThat(count("SELECT count(*) FROM customer_quote_submissions WHERE rfq_id = ?", s.rfq)).isEqualTo(2);
	}

	/** A request with a plausible JSON body (so that body validation cannot mask the scope answer). */
	private int statusWithBody(MockHttpServletRequestBuilder request, Login user) {
		String future = LocalDate.now().plusDays(30).toString();
		String body = ("{\"comment\": \"probe\", \"response\": \"APPROVED\", \"visibility\": \"SUPPLIER\", \"currencyId\": %d, "
				+ "\"internalNotes\": \"probe\", \"targetQuoteDate\": \"%s\", \"items\": [{\"partNumber\": \"PROBE\"}], "
				+ "\"subRfqs\": [{\"supplierId\": 1, \"itemIds\": [1]}], \"responseDueDate\": \"%s\", "
				+ "\"lines\": [{\"quoteId\": 1, \"ergoQuote\": {\"currencyId\": %d, \"productionPiecePrice\": 1, \"validityDate\": \"%s\"}}]}")
			.formatted(inr, future, future, inr, future);
		return send(request, user, body);
	}

	// ---------------------------------------------------------------- files shared per part

	@Test
	void aFileSharedWithoutPartsReachesNoSupplierAndOneSharedForPartsReachesOnlyThoseSuppliers() throws Exception {
		long rfq = reviewedRfq("PN-1", "PN-2", "PN-3");
		List<Long> p = parts(rfq);
		long other = reviewedRfq("PN-O");
		long foreignPart = parts(other).get(0);
		long doc = idOf(upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "d1.pdf").andReturn());
		long before = count("SELECT count(*) FROM documents WHERE linked_module = 'rfq' AND linked_record_id = ?", rfq);

		// parts are required, must belong to this RFQ, and a refused request changes nothing
		for (String body : List.of("{\"visibility\": \"ALL\"}", "{\"visibility\": \"SUPPLIER\", \"itemIds\": []}",
				"{\"visibility\": \"SUPPLIER\", \"itemIds\": [%d]}".formatted(foreignPart),
				"{\"visibility\": \"ALL\", \"itemIds\": [%d, %d]}".formatted(p.get(0), foreignPart),
				"{\"visibility\": \"ALL\", \"itemIds\": [-5]}")) {
			call(put("/api/rfqs/{id}/documents/{doc}/visibility", rfq, doc), ergoRfq, body).andExpect(status().isUnprocessableContent());
		}
		assertThat(jdbc.queryForObject("SELECT visibility FROM documents WHERE document_id = ?", String.class, doc)).isEqualTo("INTERNAL");
		assertThat(count("SELECT count(*) FROM rfq_document_parts WHERE document_id = ?", doc)).isZero();
		// an upload that asks for supplier visibility without parts is refused and stores nothing
		upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "d2.pdf", "d2".getBytes(StandardCharsets.UTF_8), "visibility", "SUPPLIER")
			.andExpect(status().isUnprocessableContent());
		upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "d3.pdf", "d3".getBytes(StandardCharsets.UTF_8), "visibility", "ALL", "itemIds", String.valueOf(foreignPart))
			.andExpect(status().isUnprocessableContent());
		assertThat(count("SELECT count(*) FROM documents WHERE linked_module = 'rfq' AND linked_record_id = ?", rfq)).isEqualTo((int) before);
		// a customer cannot pick parts or visibility
		upload("/api/rfqs/{id}/documents", rfq, buyer, "mine.pdf", "m".getBytes(StandardCharsets.UTF_8), "visibility", "ALL", "itemIds", String.valueOf(p.get(0)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.visibility").value("CUSTOMER")).andExpect(jsonPath("$.itemIds").isEmpty());

		call(put("/api/rfqs/{id}/documents/{doc}/visibility", rfq, doc), ergoRfq,
				"{\"visibility\": \"ALL\", \"itemIds\": [%d]}".formatted(p.get(0))).andExpect(status().isOk());
		distribute(rfq, supplierX, List.of(p.get(0)), supplierY, List.of(p.get(1), p.get(2)));
		long x1 = quoteOf(sellerX, rfq, "PN-1");
		long y2 = quoteOf(sellerY, rfq, "PN-2");
		call(get("/api/quotes/{id}/documents", x1), sellerX, null).andExpect(jsonPath("$[*].fileName", hasItem("d1.pdf")));
		call(get("/api/quotes/{id}/documents", y2), sellerY, null).andExpect(jsonPath("$[*].fileName", not(hasItem("d1.pdf"))));
		call(get("/api/documents/{id}", doc), sellerY, null).andExpect(status().isNotFound());
		call(get("/api/documents/{id}/content", doc), sellerY, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/documents/{doc}/content?source=RFQ", y2, doc), sellerY, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/documents/{doc}/content?source=RFQ", x1, doc), sellerX, null).andExpect(status().isOk());
		call(get("/api/documents/{id}/content", doc), sellerX, null).andExpect(status().isOk());
		call(get("/api/documents?size=100"), sellerY, null).andExpect(jsonPath("$.items[*].fileName", not(hasItem("d1.pdf"))));
		call(get("/api/documents?size=100"), sellerX, null).andExpect(jsonPath("$.items[*].fileName", hasItem("d1.pdf")));

		// "source" is not a way past the rules: any other value is the quote's own files
		call(get("/api/quotes/{id}/documents/{doc}/content?source=rfq", x1, doc), sellerX, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/documents/{doc}/content?source=QUOTE", x1, doc), sellerX, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/documents/{doc}/content?source=../RFQ", x1, doc), sellerX, null).andExpect(status().isNotFound());

		// a supplier can neither widen nor change the sharing, nor remove ERGO's file
		call(patch("/api/documents/{id}", doc), sellerX, "{\"visibility\": \"SUPPLIER\"}").andExpect(status().is4xxClientError());
		call(put("/api/rfqs/{id}/documents/{doc}/visibility", rfq, doc), sellerX, "{\"visibility\": \"ALL\", \"itemIds\": [%d]}".formatted(p.get(1)))
			.andExpect(status().isForbidden());
		call(delete("/api/rfqs/{id}/documents/{doc}", rfq, doc), sellerX, null).andExpect(status().isForbidden());
		assertThat(count("SELECT count(*) FROM rfq_document_parts WHERE document_id = ?", doc)).isEqualTo(1);

		// part 1 is taken from X and sent to Y: the file follows the part
		call(post("/api/quotes/{id}/recall", x1), ergoRfq, "{\"comment\": \"move it\"}").andExpect(status().isOk());
		call(get("/api/documents/{id}", doc), sellerX, null).andExpect(status().isNotFound());
		call(get("/api/documents/{id}/content", doc), sellerX, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/documents/{doc}/content?source=RFQ", x1, doc), sellerX, null).andExpect(status().isNotFound());
		call(get("/api/documents?size=100"), sellerX, null).andExpect(jsonPath("$.items[*].fileName", not(hasItem("d1.pdf"))));
		distribute(rfq, supplierY, List.of(p.get(0)));
		long y1 = quoteOf(sellerY, rfq, "PN-1");
		call(get("/api/quotes/{id}/documents", y1), sellerY, null).andExpect(jsonPath("$[*].fileName", hasItem("d1.pdf")));
		call(get("/api/documents/{id}", doc), sellerY, null).andExpect(status().isOk());
		call(get("/api/documents/{id}", doc), sellerX, null).andExpect(status().isNotFound());
	}

	@Test
	void aFileMadeVisibleToSuppliersInTheLibraryWithoutPartsFailsClosedAndTheVisibilityFilterIsNotBypassedByPartRows() throws Exception {
		long rfq = reviewedRfq("PN-1", "PN-2");
		List<Long> p = parts(rfq);
		long customerFile = idOf(upload("/api/rfqs/{id}/documents", rfq, buyer, "cust.pdf").andReturn());
		long ergoFile = idOf(upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "ergo.pdf").andReturn());
		long internal = idOf(upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "internal.pdf").andReturn());
		long shared = idOf(upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "shared.pdf").andReturn());
		distribute(rfq, supplierX, p);
		long x1 = quoteOf(sellerX, rfq, "PN-1");
		// ERGO widens both through the Document Library, which knows nothing of parts
		for (long file : List.of(customerFile, ergoFile)) {
			call(patch("/api/documents/{id}", file), ergoRfq, "{\"visibility\": \"SUPPLIER\"}"); // whether ERGO may widen a customer's file is the Library's rule; the supplier must not get it either way
			call(get("/api/documents/{id}", file), sellerX, null).andExpect(status().isNotFound());
			call(get("/api/documents/{id}/content", file), sellerX, null).andExpect(status().isNotFound());
			call(get("/api/quotes/{id}/documents/{doc}/content?source=RFQ", x1, file), sellerX, null).andExpect(status().isNotFound());
		}
		call(get("/api/quotes/{id}/documents", x1), sellerX, null).andExpect(jsonPath("$", hasSize(0)));
		call(get("/api/documents?size=100"), sellerX, null).andExpect(jsonPath("$.items", hasSize(0)));
		// part rows alone do not make a customer's or internal file reachable
		jdbc.update("INSERT INTO rfq_document_parts (document_id, rfq_item_id) VALUES (?, ?)", internal, p.get(0));
		jdbc.update("INSERT INTO rfq_document_parts (document_id, rfq_item_id) VALUES (?, ?)", customerFile, p.get(0));
		jdbc.update("UPDATE documents SET visibility = 'CUSTOMER' WHERE document_id = ?", customerFile);
		for (long file : List.of(internal, customerFile)) {
			call(get("/api/documents/{id}", file), sellerX, null).andExpect(status().isNotFound());
			call(get("/api/quotes/{id}/documents/{doc}/content?source=RFQ", x1, file), sellerX, null).andExpect(status().isNotFound());
		}
		// switching a shared file back to internal in the library takes it away from the supplier at once
		call(put("/api/rfqs/{id}/documents/{doc}/visibility", rfq, shared), ergoRfq, "{\"visibility\": \"SUPPLIER\", \"itemIds\": [%d]}".formatted(p.get(0)))
			.andExpect(status().isOk());
		call(get("/api/documents/{id}", shared), sellerX, null).andExpect(status().isOk());
		call(patch("/api/documents/{id}", shared), ergoRfq, "{\"visibility\": \"INTERNAL\"}").andExpect(status().isOk());
		call(get("/api/documents/{id}", shared), sellerX, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/documents/{doc}/content?source=RFQ", x1, shared), sellerX, null).andExpect(status().isNotFound());
		// ... and switching it to "customer only" through the RFQ endpoint forgets the parts for good
		call(put("/api/rfqs/{id}/documents/{doc}/visibility", rfq, shared), ergoRfq, "{\"visibility\": \"CUSTOMER\"}").andExpect(status().isOk());
		assertThat(count("SELECT count(*) FROM rfq_document_parts WHERE document_id = ?", shared)).isZero();
	}

}
