package com.ergo.connect;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Shared test plumbing for the RFQ flow: an RFQ is a header plus parts, and ERGO distributes parts to suppliers as
 * sub-RFQs (flow doc §1-2). Keeps the individual test classes free of that JSON.
 */
public final class TestRfq {

	private TestRfq() {
	}

	/** An RFQ form with one complete part per given part number (material, process flow and a customer target). */
	public static String form(long currencyId, String... partNumbers) {
		List<String> items = new ArrayList<>();
		for (String part : partNumbers) {
			items.add("""
					{"partNumber": "%s", "description": "Bracket", "materialSpec": "EN8",
					 "targetProductionPiecePrice": 99.5, "processSteps": [{"stepName": "Casting"}]}""".formatted(part));
		}
		return """
				{"targetQuoteDate": "%s", "currencyId": %d, "items": [%s]}"""
			.formatted(LocalDate.now().plusDays(10), currencyId, String.join(", ", items));
	}

	/** Creates the RFQ as {@code creator} and returns its id. */
	public static long create(MockMvc mvc, Login creator, String body) throws Exception {
		String json = mvc
			.perform(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, creator.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.<Number>read(json, "$.id").longValue();
	}

	/** The ids of an RFQ's parts, in order, as ERGO sees them. */
	public static List<Long> itemIds(MockMvc mvc, Login ergo, long rfqId) throws Exception {
		String json = mvc.perform(get("/api/rfqs/{id}", rfqId).header(HttpHeaders.AUTHORIZATION, ergo.bearer()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<Number> ids = JsonPath.read(json, "$.items[*].id");
		return ids.stream().map(Number::longValue).toList();
	}

	/**
	 * A distribution request: pairs of (supplier company id, list of part ids), one sub-RFQ per pair, due in a week.
	 * {@code TestRfq.distribution(x, List.of(p1, p2), y, List.of(p3))}.
	 */
	public static String distribution(Object... supplierAndParts) {
		List<String> subs = new ArrayList<>();
		for (int i = 0; i < supplierAndParts.length; i += 2) {
			subs.add("{\"supplierId\": %s, \"itemIds\": %s}".formatted(supplierAndParts[i], supplierAndParts[i + 1]));
		}
		return "{\"subRfqs\": [%s], \"responseDueDate\": \"%s\"}".formatted(String.join(", ", subs),
				LocalDate.now().plusDays(7));
	}

	/** The whole RFQ to one supplier (flow doc §2: "complete RFQ to one supplier"). */
	public static String wholeTo(MockMvc mvc, Login ergo, long rfqId, long supplierId) throws Exception {
		return distribution(supplierId, itemIds(mvc, ergo, rfqId));
	}

	/**
	 * The quote (invitation) id of a part for a supplier login, found through the supplier's own list; the part is
	 * identified by its number.
	 */
	public static long quoteOf(MockMvc mvc, Login supplier, String rfqNumber, String partNumber) throws Exception {
		String json = mvc.perform(get("/api/quotes").header(HttpHeaders.AUTHORIZATION, supplier.bearer()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<Number> ids = JsonPath.read(json,
				"$.items[?(@.rfqNumber == '" + rfqNumber + "' && @.partNumber == '" + partNumber + "')].id");
		return ids.get(0).longValue();
	}

	public static String numberOf(MockMvc mvc, Login ergo, long rfqId) throws Exception {
		return JsonPath.read(mvc.perform(get("/api/rfqs/{id}", rfqId).header(HttpHeaders.AUTHORIZATION, ergo.bearer()))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.number");
	}


	/** The part numbers of an RFQ, in order, as ERGO sees them. */
	public static List<String> partNumbers(MockMvc mvc, Login ergo, long rfqId) throws Exception {
		String json = mvc.perform(get("/api/rfqs/{id}", rfqId).header(HttpHeaders.AUTHORIZATION, ergo.bearer()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> parts = JsonPath.read(json, "$.items[*].partNumber");
		return parts;
	}

	/** A submitted supplier quote for one part: complete, in INR-style single currency, priced at {@code price}. */
	public static String supplierQuote(long currencyId, String price) {
		return """
				{"validityDate": "%s", "currencyId": %d, "productionPiecePrice": %s, "toolingCostTotal": 50000,
				 "rawMaterialCost": 40, "processingCost": 20, "overheadCost": 8, "packagingCost": 2, "profit": 6,
				 "moq": 500, "productionLeadTimeDays": 45}"""
			.formatted(LocalDate.now().plusDays(60), currencyId, price);
	}

	/**
	 * ERGO sends the RFQ to the given suppliers: with one supplier the whole RFQ, with several the parts are split
	 * between them (part k goes to supplier k, wrapping round), the way a sub-RFQ split works (flow doc §2).
	 */
	public static void invite(MockMvc mvc, Login ergo, long rfqId, long... suppliers) throws Exception {
		List<Long> parts = itemIds(mvc, ergo, rfqId);
		List<List<Long>> shares = new ArrayList<>();
		for (int s = 0; s < suppliers.length; s++) {
			shares.add(new ArrayList<>());
		}
		for (int i = 0; i < parts.size(); i++) {
			shares.get(i % suppliers.length).add(parts.get(i));
		}
		List<Object> args = new ArrayList<>();
		for (int s = 0; s < suppliers.length; s++) {
			if (!shares.get(s).isEmpty()) {
				args.add(suppliers[s]);
				args.add(shares.get(s));
			}
		}
		send(mvc, post("/api/rfqs/{id}/quotes", rfqId), ergo, distribution(args.toArray()));
	}

	/**
	 * Drives a submitted RFQ that ERGO is reviewing to the customer's approval, the way the flow doc has it: the whole
	 * RFQ goes to one supplier, which quotes and submits every part; ERGO accepts every quote and generates the
	 * customer quotation (price 120 per part); the customer approves it. The RFQ's parts are then "customer approved".
	 */
	public static void approveWhole(MockMvc mvc, Login customer, Login ergo, Login seller, long supplierId,
			long currencyId, long rfqId, String supplierPrice) throws Exception {
		send(mvc, post("/api/rfqs/{id}/quotes", rfqId), ergo, wholeTo(mvc, ergo, rfqId, supplierId));
		String number = numberOf(mvc, ergo, rfqId);
		List<String> lines = new ArrayList<>();
		for (String part : partNumbers(mvc, ergo, rfqId)) {
			long quote = quoteOf(mvc, seller, number, part);
			send(mvc, put("/api/quotes/{id}", quote), seller, supplierQuote(currencyId, supplierPrice));
			send(mvc, post("/api/quotes/{id}/submit", quote), seller, null);
			send(mvc, post("/api/quotes/{id}/accept", quote), ergo, null);
			lines.add("""
					{"quoteId": %d, "ergoQuote": {"currencyId": %d, "productionPiecePrice": 120, "validityDate": "%s"}}"""
				.formatted(quote, currencyId, LocalDate.now().plusDays(30)));
		}
		send(mvc, post("/api/rfqs/{id}/customer-quotes", rfqId), ergo,
				"{\"remarks\": \"ok\", \"lines\": [" + String.join(", ", lines) + "]}");
		long quotation = JsonPath.<Number>read(
				mvc.perform(get("/api/rfqs/{id}/customer-quotes", rfqId).header(HttpHeaders.AUTHORIZATION,
						customer.bearer())).andReturn().getResponse().getContentAsString(),
				"$.quotations[0].id").longValue();
		send(mvc, post("/api/customer-quotes/{id}/respond", quotation), customer,
				"{\"response\": \"APPROVED\", \"comment\": \"Go ahead\"}");
	}

	/** Performs a request as {@code user}, expecting 200 OK. */
	private static void send(MockMvc mvc, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
	}

}
