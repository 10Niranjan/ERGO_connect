package com.ergo.connect.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ergo.connect.AdversarialSupport;
import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestRfq;
import com.ergo.connect.quotation.api.QuotationApi;
import com.jayway.jsonpath.JsonPath;

/**
 * The Quotation module switch (PERMISSIONS.md §9): at V every change is refused whoever asks and nothing is lost; at NV
 * nothing of the module is shown, from any path; back at O everything works again. The RFQ-side switch is covered by
 * StrictModuleSwitchTests; this is the quotation side, entry point by entry point.
 */
@IntegrationTest
class QuoteSwitchTests extends AdversarialSupport {

	@Autowired
	QuotationApi quotationApi;

	/** An RFQ with a submitted part, an accepted one and one with the customer, and a second RFQ with a part not yet quoted. */
	record Fx(long rfq, long submitted, long accepted, long withCustomer, long quotation, long rfq2, long draft,
			long unassignedPart) {
	}

	private Fx fixture() throws Exception {
		long rfq = reviewedRfq("PN-1", "PN-2", "PN-3");
		List<Long> p = parts(rfq);
		distribute(rfq, supplierX, p);
		long q1 = quoteOf(sellerX, rfq, "PN-1");
		long q2 = quoteOf(sellerX, rfq, "PN-2");
		long q3 = quoteOf(sellerX, rfq, "PN-3");
		for (long q : List.of(q1, q2, q3)) {
			submitQuote(q, sellerX, "90");
		}
		accept(q2);
		accept(q3);
		long quotation = generate(rfq, q3);
		long rfq2 = reviewedRfq("PN-A", "PN-B");
		List<Long> p2 = parts(rfq2);
		distribute(rfq2, supplierX, List.of(p2.get(0)));
		long draft = quoteOf(sellerX, rfq2, "PN-A");
		jdbc.update("UPDATE rfq_supplier_assignments SET response_due_date = ? WHERE assignment_id = ?", LocalDate.now().minusDays(2), draft);
		return new Fx(rfq, q1, q2, q3, quotation, rfq2, draft, p2.get(1));
	}

	private void assertNothingChanged(Fx f) {
		assertThat(statusOf(f.submitted)).isEqualTo("SUBMITTED");
		assertThat(statusOf(f.accepted)).isEqualTo("ACCEPTED");
		assertThat(statusOf(f.withCustomer)).isEqualTo("WITH_CUSTOMER");
		assertThat(statusOf(f.draft)).isEqualTo("NOT_STARTED");
		assertThat(count("SELECT count(*) FROM customer_quote_submissions WHERE rfq_id = ?", f.rfq)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT response_status FROM customer_quote_submissions WHERE customer_quote_submission_id = ?",
				String.class, f.quotation)).isEqualTo("WAITING");
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_id = ?", f.rfq2)).isEqualTo(1);
		assertThat(rfqStatus(f.rfq)).isEqualTo("SENT_TO_CUSTOMER");
		assertThat(count("SELECT count(*) FROM supplier_quotes WHERE assignment_id = ?", f.draft)).isZero();
	}

	/** Every way to change a quotation, as the role that normally may: all refused, nothing saved. */
	private void everyChangeIsRefused(Fx f, int expected) throws Exception {
		assertThat(send(post("/api/rfqs/{id}/quotes", f.rfq2), ergoRfq, TestRfq.distribution(supplierY, List.of(f.unassignedPart)))).as("distribute").isEqualTo(expected);
		assertThat(send(put("/api/quotes/{id}", f.draft), sellerX, TestRfq.supplierQuote(inr, "70"))).as("save quote").isEqualTo(expected);
		assertThat(send(post("/api/quotes/{id}/submit", f.draft), sellerX, null)).as("submit").isEqualTo(expected);
		assertThat(send(post("/api/quotes/{id}/accept", f.submitted), ergoRfq, null)).as("accept").isEqualTo(expected);
		assertThat(send(post("/api/quotes/{id}/revision", f.submitted), ergoRfq, "{\"comment\": \"r\"}")).as("revision").isEqualTo(expected);
		assertThat(send(post("/api/quotes/{id}/recall", f.accepted), ergoRfq, "{\"comment\": \"r\"}")).as("recall").isEqualTo(expected);
		assertThat(send(post("/api/rfqs/{id}/customer-quotes", f.rfq), ergoMgmt, generateBody(line(f.accepted, "100")))).as("generate").isEqualTo(expected);
		assertThat(send(post("/api/customer-quotes/{id}/respond", f.quotation), buyer, answer("APPROVED", "go"))).as("respond").isEqualTo(expected);
		assertThat(send(post("/api/customer-quotes/{id}/respond", f.quotation), buyer, answer("REVISION_REQUESTED", "fix"))).as("correction").isEqualTo(expected);
		try {
			assertThat(upload("/api/quotes/{id}/documents", f.draft, sellerX, "late.pdf").andReturn().getResponse().getStatus()).as("supplier upload").isEqualTo(expected);
			assertThat(upload("/api/quotes/{id}/documents", f.submitted, ergoRfq, "note.pdf").andReturn().getResponse().getStatus()).as("ERGO upload").isEqualTo(expected);
		}
		catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void aReadOnlyQuotationModuleRefusesEveryChangeButShowsEverything() throws Exception {
		Fx f = fixture();
		cap("QUOTATION", "V");
		everyChangeIsRefused(f, 403);
		assertNothingChanged(f);
		// reads are unchanged for every audience
		call(get("/api/rfqs/{id}/quotes", f.rfq), ergoRfq, null).andExpect(status().isOk()).andExpect(jsonPath("$.parts", hasSize(3)))
			.andExpect(jsonPath("$.canDistribute").value(false));
		call(get("/api/rfqs/{id}/quotes", f.rfq2), ergoRfq, null).andExpect(status().isOk()).andExpect(jsonPath("$.canDistribute").value(false));
		call(get("/api/quotes/{id}", f.submitted), ergoRfq, null).andExpect(status().isOk()).andExpect(jsonPath("$.actions", hasSize(0)));
		call(get("/api/quotes/{id}/versions", f.submitted), ergoRfq, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
		call(get("/api/quotes"), sellerX, null).andExpect(status().isOk());
		call(get("/api/quotes/{id}", f.draft), sellerX, null).andExpect(status().isOk()).andExpect(jsonPath("$.actions", hasSize(0)));
		call(get("/api/rfqs/{id}/parts", f.rfq), buyer, null).andExpect(status().isOk());
		call(get("/api/rfqs/{id}/customer-quotes", f.rfq), buyer, null).andExpect(status().isOk()).andExpect(jsonPath("$.quotations", hasSize(1)))
			.andExpect(jsonPath("$.quotations[0].actions", hasSize(0)));
		call(get("/api/rfqs/{id}/customer-quotes", f.rfq), ergoRfq, null).andExpect(status().isOk());
		String dashboard = text(get("/api/dashboard"), buyer, null);
		assertThat(JsonPath.<List<?>>read(dashboard, "$.partItems")).isNotEmpty();

		cap("QUOTATION", "O");
		call(post("/api/quotes/{id}/accept", f.submitted), ergoRfq, null).andExpect(status().isOk());
		call(post("/api/customer-quotes/{id}/respond", f.quotation), buyer, answer("APPROVED", "go")).andExpect(status().isOk());
	}

	@Test
	void aHiddenQuotationModuleShowsNothingAndRefusesEverythingFromEveryPath() throws Exception {
		Fx f = fixture();
		long before = count("SELECT count(*) FROM notifications WHERE linked_module = 'quotation'");
		assertThat(before).isPositive();
		call(get("/api/notifications?size=100"), sellerX, null).andExpect(jsonPath("$.items[?(@.linkedModule=='quotation')]", not(hasSize(0))));
		cap("QUOTATION", "NV");

		everyChangeIsRefused(f, 403);
		assertNothingChanged(f);
		for (var user : List.of(ergoRfq, ergoMgmt)) {
			call(get("/api/rfqs/{id}/quotes", f.rfq), user, null).andExpect(status().isForbidden());
			call(get("/api/rfqs/{id}/parts", f.rfq), user, null).andExpect(status().isForbidden());
			call(get("/api/rfqs/{id}/customer-quotes", f.rfq), user, null).andExpect(status().isForbidden());
			call(get("/api/quotes/{id}", f.submitted), user, null).andExpect(status().isForbidden());
			call(get("/api/quotes/{id}/versions", f.submitted), user, null).andExpect(status().isForbidden());
		}
		call(get("/api/rfqs/{id}/parts", f.rfq), buyer, null).andExpect(status().isForbidden());
		call(get("/api/rfqs/{id}/customer-quotes", f.rfq), buyer, null).andExpect(status().isForbidden());
		// a supplier keeps the assigned part (an RFQ fact) but sees no quote, status, version or file of the module
		String list = text(get("/api/quotes"), sellerX, null);
		assertThat(list).doesNotContain("\"status\"", "\"version\"", "submittedAt");
		// the status filter is ignored here, so the total cannot reveal the quote states
		String total = text(get("/api/quotes"), sellerX, null).replaceAll(".*\"total\":(\\d+).*", "$1");
		assertThat(text(get("/api/quotes?status=SUBMITTED"), sellerX, null)).contains("\"total\":" + total);
		assertThat(text(get("/api/quotes?status=NOT_STARTED"), sellerX, null)).contains("\"total\":" + total);
		String one = text(get("/api/quotes/{id}", f.submitted), sellerX, null);
		assertThat(one).doesNotContain("\"quote\"", "\"status\"", "productionPiecePrice", "revisionReason");
		call(get("/api/quotes/{id}/versions", f.submitted), sellerX, null).andExpect(status().isForbidden());
		call(get("/api/quotes/{id}/documents", f.submitted), sellerX, null).andExpect(jsonPath("$", hasSize(0)));
		// dashboards, notifications: nothing of the module
		assertThat(JsonPath.<List<?>>read(text(get("/api/dashboard"), buyer, null), "$.partItems")).isEmpty();
		assertThat(JsonPath.<List<?>>read(text(get("/api/dashboard"), ergoMgmt, null), "$.partItems")).isEmpty();
		call(get("/api/notifications?size=100"), sellerX, null).andExpect(jsonPath("$.items[?(@.linkedModule=='quotation')]", hasSize(0)));
		call(get("/api/notifications/unread-count"), sellerX, null).andExpect(status().isOk());
		// the daily reminder scan reports nothing while the module is hidden
		assertThat(quotationApi.overdueItems(LocalDate.now())).isEmpty();

		cap("QUOTATION", "O");
		call(get("/api/notifications?size=100"), sellerX, null).andExpect(jsonPath("$.items[?(@.linkedModule=='quotation')]", not(hasSize(0))));
		assertThat(quotationApi.overdueItems(LocalDate.now())).anyMatch(i -> i.recordId() == f.draft);
		call(get("/api/rfqs/{id}/quotes", f.rfq), ergoRfq, null).andExpect(status().isOk()).andExpect(jsonPath("$.parts", hasSize(3)));
		call(post("/api/quotes/{id}/accept", f.submitted), ergoRfq, null).andExpect(status().isOk());
	}

	@Test
	void theReminderScanPausesWhileEitherModuleIsNotAcceptingChangesAndResumesByItself() throws Exception {
		Fx f = fixture();
		assertThat(quotationApi.overdueItems(LocalDate.now())).anyMatch(i -> i.recordId() == f.draft);
		for (String module : List.of("QUOTATION", "RFQ")) {
			for (String cap : List.of("V", "NV")) {
				cap(module, cap);
				assertThat(quotationApi.overdueItems(LocalDate.now())).as(module + " at " + cap).isEmpty();
			}
			cap(module, "O");
		}
		assertThat(quotationApi.overdueItems(LocalDate.now())).anyMatch(i -> i.recordId() == f.draft);
	}

	@Test
	void theCustomerAndSupplierSidesOfTheCycleStayInStepWhenOnlyTheRfqModuleIsHidden() throws Exception {
		Fx f = fixture();
		cap("RFQ", "NV");
		// the part list embeds the RFQ's parts: nothing to show, nothing to change
		call(get("/api/rfqs/{id}/parts", f.rfq), buyer, null).andExpect(status().isNotFound());
		call(get("/api/rfqs/{id}/quotes", f.rfq), ergoRfq, null).andExpect(status().isNotFound());
		assertThat(send(post("/api/customer-quotes/{id}/respond", f.quotation), buyer, answer("APPROVED", "go"))).isBetween(400, 499);
		assertThat(send(post("/api/quotes/{id}/accept", f.submitted), ergoRfq, null)).isBetween(400, 499);
		assertThat(send(post("/api/quotes/{id}/recall", f.accepted), ergoRfq, "{\"comment\": \"r\"}")).isBetween(400, 499);
		assertNothingChanged(f);
	}

}
