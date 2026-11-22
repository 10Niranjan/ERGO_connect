package com.ergo.connect.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;

import com.ergo.connect.AdversarialSupport;
import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Several suppliers per part (2026-11-22): ERGO sends one part to several suppliers, compares their quotes, selects ONE
 * for the customer; only that quote goes into the customer quotation, the others are never sent and never told.
 */
@IntegrationTest
class QuoteSeveralSuppliersTests extends AdversarialSupport {

	private static String sentTo(String distribution, long... documentIds) {
		String ids = java.util.Arrays.stream(documentIds).mapToObj(Long::toString).collect(java.util.stream.Collectors.joining(","));
		return distribution.substring(0, distribution.lastIndexOf('}')) + ", \"sharedDocumentIds\": [" + ids + "]}";
	}

	private int selectedCount(long item) {
		return count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_item_id = ? AND status IN ('ACCEPTED', 'WITH_CUSTOMER', "
				+ "'CUSTOMER_ON_HOLD', 'CUSTOMER_CORRECTION', 'CUSTOMER_APPROVED', 'CUSTOMER_REJECTED')", item);
	}

	@Test
	void ergoComparesSeveralQuotesSelectsOneAndOnlyThatOneGoesToTheCustomer() throws Exception {
		long rfq = reviewedRfq("PN-S1");
		List<Long> p = parts(rfq);
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierX, p, supplierY, p, supplierZ, p)).andExpect(status().isOk())
			.andExpect(jsonPath("$.parts", hasSize(3)))
			.andExpect(jsonPath("$.parts[?(@.selected == true)]", hasSize(0)))
			.andExpect(jsonPath("$.canDistribute").value(true));
		long qx = quoteOf(sellerX, rfq, "PN-S1");
		long qy = quoteOf(sellerY, rfq, "PN-S1");
		long qz = quoteOf(sellerZ, rfq, "PN-S1");
		submitQuote(qx, sellerX, "90");
		submitQuote(qy, sellerY, "80");
		assertThat(rfqStatus(rfq)).isEqualTo("QUOTES_COMPLETE"); // the part has a quote in; counted per part, not per supplier

		// ERGO compares: every supplier's submitted quote is a row with its price in the RFQ currency
		call(get("/api/rfqs/{id}/quotes", rfq), ergoRfq, null).andExpect(jsonPath("$.parts[?(@.productionPiecePrice != null)]", hasSize(2)))
			.andExpect(jsonPath("$.parts[?(@.priceInRfqCurrency != null)]", hasSize(2)));

		// selecting Y, then switching to X while nothing has gone to the customer: one selected quote only
		accept(qy);
		assertThat(statusOf(qy)).isEqualTo("ACCEPTED");
		accept(qx);
		assertThat(statusOf(qx)).isEqualTo("ACCEPTED");
		assertThat(statusOf(qy)).isEqualTo("SUBMITTED");
		assertThat(selectedCount(p.get(0))).isEqualTo(1);
		accept(qy);
		assertThat(statusOf(qx)).isEqualTo("SUBMITTED");
		assertThat(selectedCount(p.get(0))).isEqualTo(1);
		call(get("/api/rfqs/{id}/quotes", rfq), ergoRfq, null).andExpect(jsonPath("$.parts[?(@.selected == true)]", hasSize(1)));

		// only the selected quote can be put in the customer quotation
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(line(qx, "120"))).andExpect(status().isConflict());
		long quotation = generate(rfq, qy);
		call(get("/api/rfqs/{id}/customer-quotes", rfq), buyer, null).andExpect(jsonPath("$.quotations[0].content.lines", hasSize(1)));
		call(get("/api/rfqs/{id}/parts", rfq), buyer, null).andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].status").value("QUOTATION_RECEIVED"));
		assertThat(rfqStatus(rfq)).isEqualTo("SENT_TO_CUSTOMER");

		// with the customer: the others can neither be selected nor sent back, and no supplier can be added
		call(post("/api/quotes/{id}/accept", qx), ergoRfq, null).andExpect(status().isConflict());
		call(post("/api/quotes/{id}/revision", qx), ergoRfq, "{\"comment\": \"cheaper\"}").andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierZ, p)).andExpect(status().isUnprocessableContent());
		call(get("/api/rfqs/{id}/quotes", rfq), ergoRfq, null).andExpect(jsonPath("$.canDistribute").value(false));

		// the customer approves: the unfinished quote (Z never submitted) closes, the submitted one stays as it was
		respond(quotation, "APPROVED");
		assertThat(statusOf(qy)).isEqualTo("CUSTOMER_APPROVED");
		assertThat(statusOf(qx)).isEqualTo("SUBMITTED");
		assertThat(statusOf(qz)).isEqualTo("CLOSED");
		call(get("/api/quotes/{id}", qz), sellerZ, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT_SAVED")) // never "closed": that would tell Z a competitor exists
			.andExpect(jsonPath("$.actions", hasSize(0)));
		call(put("/api/quotes/{id}", qz), sellerZ, TestRfq.supplierQuote(inr, "70")).andExpect(status().isConflict());
		call(get("/api/quotes/{id}", qx), sellerX, null).andExpect(jsonPath("$.status").value("SUBMITTED")); // not told
		assertThat(rfqStatus(rfq)).isEqualTo("CUSTOMER_APPROVED");
	}

	@Test
	void aSupplierNeverSeesAnotherSuppliersQuoteOrThatThereIsOne() throws Exception {
		long rfq = reviewedRfq("PN-S2");
		List<Long> p = parts(rfq);
		distribute(rfq, supplierX, p, supplierY, p);
		long qx = quoteOf(sellerX, rfq, "PN-S2");
		long qy = quoteOf(sellerY, rfq, "PN-S2");
		submitQuote(qy, sellerY, "77.77");
		call(get("/api/quotes/{id}", qy), sellerX, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/versions", qy), sellerX, null).andExpect(status().isNotFound());
		String mine = text(get("/api/quotes"), sellerX, null);
		assertThat(mine).doesNotContain("77.77");
		assertThat((List<?>) JsonPath.read(mine, "$.items")).hasSize(1);
		assertThat(text(get("/api/quotes/{id}", qx), sellerX, null)).doesNotContain("77.77");
		// the supplier's own sub-RFQ number is counted among its own, so it cannot tell how many suppliers there are
		call(get("/api/quotes/{id}", qx), sellerX, null).andExpect(jsonPath("$.subRfqNo").value(1));
		call(get("/api/quotes/{id}", qy), sellerY, null).andExpect(jsonPath("$.subRfqNo").value(1));
	}

	@Test
	void aPartGoesToASupplierOnceAndARecalledSupplierCanGetItAgain() throws Exception {
		long rfq = reviewedRfq("PN-S3");
		List<Long> p = parts(rfq);
		distribute(rfq, supplierX, p);
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierX, p)).andExpect(status().isUnprocessableContent());
		distribute(rfq, supplierY, p);
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_item_id = ? AND status <> 'RECALLED'", p.get(0))).isEqualTo(2);
		long qx = quoteOf(sellerX, rfq, "PN-S3");
		call(post("/api/quotes/{id}/recall", qx), ergoRfq, "{\"comment\": \"capacity\"}").andExpect(status().isOk());
		assertThat(rfqStatus(rfq)).isEqualTo("SENT_TO_SUPPLIERS"); // Y still holds the part
		distribute(rfq, supplierX, p);
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_item_id = ? AND status <> 'RECALLED'", p.get(0))).isEqualTo(2);
	}

	@Test
	void twoErgoUsersSelectingDifferentSuppliersAtOnceLeaveExactlyOneSelected() throws Exception {
		for (int round = 0; round < 3; round++) {
			long rfq = reviewedRfq("PN-S4");
			List<Long> p = parts(rfq);
			distribute(rfq, supplierX, p, supplierY, p);
			long qx = quoteOf(sellerX, rfq, "PN-S4");
			long qy = quoteOf(sellerY, rfq, "PN-S4");
			submitQuote(qx, sellerX, "90");
			submitQuote(qy, sellerY, "80");
			List<Integer> statuses = race(() -> send(post("/api/quotes/{id}/accept", qx), ergoRfq, null),
					() -> send(post("/api/quotes/{id}/accept", qy), ergoMgmt, null));
			assertThat(statuses).as(statuses.toString()).noneMatch(s -> s >= 500).contains(200);
			assertThat(selectedCount(p.get(0))).isEqualTo(1);
		}
	}

	@Test
	void theFilesErgoTicksReachOnlyTheSuppliersOfThoseParts() throws Exception {
		long rfq = TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-A", "PN-B"));
		List<Long> p = parts(rfq);
		long docA = partFile(rfq, p.get(0), "a-drawing.pdf");
		long docB = partFile(rfq, p.get(1), "b-drawing.pdf");
		long docC = partFile(rfq, p.get(0), "c-spec.pdf"); // never ticked
		call(post("/api/rfqs/{id}/submit", rfq), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", rfq), ergoRfq, null).andExpect(status().isOk());

		// a file of a part that is not being sent is refused, and nothing is sent
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, sentTo(TestRfq.distribution(supplierX, List.of(p.get(0))), docB))
			.andExpect(status().isUnprocessableContent());
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_id = ?", rfq)).isZero();
		// a stranger's id, and a login that is not ERGO's, are refused too
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, sentTo(TestRfq.distribution(supplierX, List.of(p.get(0))), 987654321L))
			.andExpect(status().isUnprocessableContent());

		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq,
				sentTo(TestRfq.distribution(supplierX, List.of(p.get(0)), supplierY, List.of(p.get(1))), docA, docB)).andExpect(status().isOk());
		long qa = quoteOf(sellerX, rfq, "PN-A");
		long qb = quoteOf(sellerY, rfq, "PN-B");
		call(get("/api/quotes/{id}/documents", qa), sellerX, null).andExpect(jsonPath("$[*].fileName", contains("a-drawing.pdf")));
		call(get("/api/quotes/{id}/documents", qb), sellerY, null).andExpect(jsonPath("$[*].fileName", contains("b-drawing.pdf")));
		// an unticked file stays with ERGO and the customer
		assertThat(jdbc.queryForObject("SELECT visibility FROM documents WHERE document_id = ?", String.class, docC)).isEqualTo("CUSTOMER");
		assertThat(jdbc.queryForObject("SELECT visibility FROM documents WHERE document_id = ?", String.class, docA)).isEqualTo("ALL");
	}

	@Test
	void switchingAfterACorrectionRequestAndARejectionBehaveAsDescribed() throws Exception {
		long rfq = reviewedRfq("PN-S5");
		List<Long> p = parts(rfq);
		distribute(rfq, supplierX, p, supplierY, p, supplierZ, p);
		long qx = quoteOf(sellerX, rfq, "PN-S5");
		long qy = quoteOf(sellerY, rfq, "PN-S5");
		long qz = quoteOf(sellerZ, rfq, "PN-S5");
		submitQuote(qx, sellerX, "90");
		submitQuote(qy, sellerY, "80");
		accept(qx);
		long first = generate(rfq, qx);
		respond(first, "REVISION_REQUESTED"); // the customer asks for a correction
		assertThat(statusOf(qx)).isEqualTo("CUSTOMER_CORRECTION");
		// ERGO may switch to another supplier now: the earlier selection goes back to submitted, one selected only
		accept(qy);
		assertThat(statusOf(qx)).isEqualTo("SUBMITTED");
		assertThat(statusOf(qy)).isEqualTo("ACCEPTED");
		assertThat(selectedCount(p.get(0))).isEqualTo(1);
		// the customer rejects the new quotation: the part is decided, Z's unfinished quote closes, X stays submitted
		long second = generate(rfq, qy);
		respond(second, "DECLINED");
		assertThat(statusOf(qy)).isEqualTo("CUSTOMER_REJECTED");
		assertThat(statusOf(qx)).isEqualTo("SUBMITTED");
		assertThat(statusOf(qz)).isEqualTo("CLOSED");
		call(post("/api/quotes/{id}/accept", qx), ergoRfq, null).andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierZ, p)).andExpect(status().isConflict()); // decided
		assertThat(rfqStatus(rfq)).isEqualTo("CUSTOMER_DECLINED");
	}

	@Test
	void recallingTheSelectedQuoteLeavesThePartUnselectedAndTheOthersIntact() throws Exception {
		long rfq = reviewedRfq("PN-S6");
		List<Long> p = parts(rfq);
		distribute(rfq, supplierX, p, supplierY, p);
		long qx = quoteOf(sellerX, rfq, "PN-S6");
		long qy = quoteOf(sellerY, rfq, "PN-S6");
		submitQuote(qx, sellerX, "90");
		submitQuote(qy, sellerY, "80");
		accept(qx);
		call(post("/api/quotes/{id}/recall", qx), ergoRfq, "{\"comment\": \"capacity\"}").andExpect(status().isOk());
		assertThat(selectedCount(p.get(0))).isZero();
		assertThat(statusOf(qy)).isEqualTo("SUBMITTED");
		accept(qy);
		assertThat(selectedCount(p.get(0))).isEqualTo(1);
		call(post("/api/quotes/{id}/accept", qy), sellerY, null).andExpect(status().isForbidden()); // a supplier never selects
	}

	private long partFile(long rfq, long item, String name) throws Exception {
		Login uploader = buyer;
		String json = mvc.perform(multipart("/api/rfqs/{id}/items/{i}/files", rfq, item).param("kind", "DRAWING_2D")
			.file(new MockMultipartFile("files", name, "application/octet-stream", name.getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, uploader.bearer())).andReturn().getResponse().getContentAsString();
		return JsonPath.<Number>read(json, "$[0].id").longValue();
	}

}
