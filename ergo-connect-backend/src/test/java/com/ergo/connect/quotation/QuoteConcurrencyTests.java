package com.ergo.connect.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ergo.connect.AdversarialSupport;
import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers.Login;

/**
 * Two actors, one record (flow doc §2-5). Expected outcome of every race: one winner, the loser a 409 / 422 the user
 * can act on - never a 5xx, never two writes. Most races are made deterministic: the test holds the RFQ row's lock
 * (the lock every part change takes before it reports to the RFQ), starts both requests, waits until both are parked
 * behind it, and then lets them go together.
 */
@IntegrationTest
class QuoteConcurrencyTests extends AdversarialSupport {

	@Autowired
	DataSource dataSource;

	interface WhileHeld {

		void run(Connection held) throws Exception;

	}

	/**
	 * Holds the RFQ row lock, runs the calls, waits until {@code waiters} backends wait for a lock, runs
	 * {@code whileHeld} on the holding connection (still inside its transaction), commits and returns the statuses.
	 */
	@SafeVarargs
	private List<Integer> gated(long rfq, int waiters, WhileHeld whileHeld, Callable<Integer>... calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.length);
		try (Connection held = dataSource.getConnection()) {
			held.setAutoCommit(false);
			try (Statement s = held.createStatement()) {
				s.execute("SELECT rfq_id FROM rfqs WHERE rfq_id = " + rfq + " FOR UPDATE");
			}
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> call : calls) {
				futures.add(pool.submit(call));
			}
			try {
				for (int i = 0; i < 100 && count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'") < waiters; i++) {
					Thread.sleep(100);
				}
				Thread.sleep(300); // let the rest of each request run up to its own lock wait
				if (whileHeld != null) {
					whileHeld.run(held);
				}
				held.commit();
			}
			catch (Exception e) {
				held.rollback();
				throw e;
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(60, TimeUnit.SECONDS));
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static void sql(Connection c, String sql) throws Exception {
		try (Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	private static void assertOneWinner(List<Integer> statuses, String what) {
		assertThat(statuses).as(what + " " + statuses).noneMatch(s -> s >= 500);
		assertThat(statuses).as(what + " " + statuses).containsOnlyOnce(200);
		assertThat(statuses).as(what + " " + statuses).filteredOn(s -> s != 200).allMatch(s -> s == 409 || s == 422);
	}

	/** The RFQ status an independent reading of the flow doc's precedence gives for the parts as they now are. */
	private String expectedRfqStatus(long rfq) {
		int parts = count("SELECT count(*) FROM rfq_items WHERE rfq_id = ?", rfq);
		java.util.Map<String, Integer> n = new java.util.HashMap<>();
		jdbc.query("SELECT status, count(*) FROM rfq_supplier_assignments WHERE rfq_id = ? AND status <> 'RECALLED' "
				+ "GROUP BY status", rs -> {
					n.put(rs.getString(1), rs.getInt(2));
				}, rfq);
		int withSupplier = n.getOrDefault("NOT_STARTED", 0) + n.getOrDefault("DRAFT_SAVED", 0) + n.getOrDefault("REVISION_REQUIRED", 0);
		int underReview = n.getOrDefault("SUBMITTED", 0) + n.getOrDefault("RESUBMITTED", 0);
		int accepted = n.getOrDefault("ACCEPTED", 0);
		int withCustomer = n.getOrDefault("WITH_CUSTOMER", 0);
		int onHold = n.getOrDefault("CUSTOMER_ON_HOLD", 0);
		int correction = n.getOrDefault("CUSTOMER_CORRECTION", 0);
		int approved = n.getOrDefault("CUSTOMER_APPROVED", 0);
		int rejected = n.getOrDefault("CUSTOMER_REJECTED", 0);
		int assigned = withSupplier + underReview + accepted + withCustomer + onHold + correction + approved + rejected;
		if (approved + rejected == parts) {
			return approved > 0 ? "CUSTOMER_APPROVED" : "CUSTOMER_DECLINED";
		}
		if (withCustomer > 0) {
			return "SENT_TO_CUSTOMER";
		}
		if (onHold > 0) {
			return "CUSTOMER_ON_HOLD";
		}
		if (correction > 0) {
			return "CUSTOMER_REVISION_REQUESTED";
		}
		if (underReview + accepted + approved + rejected == 0) {
			return "SENT_TO_SUPPLIERS";
		}
		return withSupplier + (parts - assigned) == 0 ? "QUOTES_COMPLETE" : "QUOTES_PARTIAL";
	}

	// ---------------------------------------------------------------- distribution

	@Test
	void twoErgoUsersSendingTheSamePartToTwoSuppliersAtOnceLeaveItWithExactlyOne() throws Exception {
		for (int round = 0; round < 3; round++) {
			long rfq = reviewedRfq("PN-1");
			List<Long> p = parts(rfq);
			List<Integer> statuses = gated(rfq, 2, null,
					() -> send(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierX, p)),
					() -> send(post("/api/rfqs/{id}/quotes", rfq), ergoMgmt, TestRfq.distribution(supplierY, p)));
			assertOneWinner(statuses, "distribute one part to two suppliers");
			assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_item_id = ? AND status <> 'RECALLED'", p.get(0)))
				.isEqualTo(1);
			assertThat(count("SELECT count(*) FROM sub_rfqs WHERE rfq_id = ?", rfq)).isEqualTo(1);
			assertThat(rfqStatus(rfq)).isEqualTo("SENT_TO_SUPPLIERS");
		}
	}

	@Test
	void aDoubleClickOnSendToSupplierSendsOnce() throws Exception {
		long rfq = reviewedRfq("PN-1", "PN-2");
		List<Long> p = parts(rfq);
		List<Integer> statuses = gated(rfq, 2, null,
				() -> send(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierX, p)),
				() -> send(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierX, p)));
		assertOneWinner(statuses, "duplicate distribute");
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_id = ?", rfq)).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM sub_rfqs WHERE rfq_id = ?", rfq)).isEqualTo(1);
	}

	@Test
	void anRfqEditedWhileErgoDistributesNeverEndsWithAQuoteOnAPartThatNoLongerExists() throws Exception {
		long rfq = reviewedRfq("PN-1", "PN-2", "PN-3");
		List<Long> p = parts(rfq);
		// ERGO's edit (it removes part 3) commits while the distribution request waits for the RFQ lock; the request
		// had already read the RFQ's parts before it waited
		List<Integer> statuses = gated(rfq, 1, held -> sql(held, "DELETE FROM rfq_items WHERE rfq_item_id = " + p.get(2)),
				() -> send(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierX, p)));
		assertThat(statuses).as("distribute with a stale part list " + statuses).noneMatch(s -> s >= 500);
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_item_id = ?", p.get(2))).isZero();
	}

	@Test
	void twoErgoUsersSendingDifferentPartsToDifferentSuppliersAtOnceBothSucceedWithDistinctSubRfqNumbers() throws Exception {
		for (int round = 0; round < 2; round++) {
			long rfq = reviewedRfq("PN-1", "PN-2");
			List<Long> p = parts(rfq);
			List<Integer> statuses = gated(rfq, 2, null,
					() -> send(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierX, List.of(p.get(0)))),
					() -> send(post("/api/rfqs/{id}/quotes", rfq), ergoMgmt, TestRfq.distribution(supplierY, List.of(p.get(1)))));
			assertThat(statuses).as("two sub-RFQs " + statuses).noneMatch(s -> s >= 500).contains(200).allMatch(s -> s == 200 || s == 409);
			// a loser of the optimistic lock (the RFQ moved to "sent to suppliers" under it) just sends again
			if (count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_id = ?", rfq) < 2) {
				long missing = p.stream().filter(id -> count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_item_id = ?", id) == 0).findFirst().orElseThrow();
				call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(missing == p.get(0) ? supplierX : supplierY, List.of(missing))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
			}
			assertThat(jdbc.queryForList("SELECT sub_no FROM sub_rfqs WHERE rfq_id = ? ORDER BY sub_no", Integer.class, rfq)).containsExactly(1, 2);
			assertThat(rfqStatus(rfq)).isEqualTo("SENT_TO_SUPPLIERS");
		}
	}

	// ---------------------------------------------------------------- quote submission and review

	@Test
	void twoSupplierUsersSubmittingTheSameQuoteAtOnceCreateOneVersion() throws Exception {
		Login colleague = users.login(supplierX, "SUPPLIER_KAM");
		long quote = singlePartQuote(sellerX, supplierX, "PN-1");
		long rfq = rfqOf(quote);
		saveQuote(quote, sellerX, "90");
		List<Integer> statuses = gated(rfq, 2, null,
				() -> send(post("/api/quotes/{id}/submit", quote), sellerX, null),
				() -> send(post("/api/quotes/{id}/submit", quote), colleague, null));
		assertOneWinner(statuses, "double submit");
		assertThat(count("SELECT count(*) FROM supplier_quote_versions v JOIN supplier_quotes s ON s.supplier_quote_id = "
				+ "v.supplier_quote_id WHERE s.assignment_id = ?", quote)).isEqualTo(1);
		assertThat(count("SELECT current_version FROM supplier_quotes WHERE assignment_id = ?", quote)).isEqualTo(1);
		assertThat(statusOf(quote)).isEqualTo("SUBMITTED");
	}

	@Test
	void twoErgoUsersAcceptingTheSameQuoteAtOnceAcceptItOnce() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-1");
		long rfq = rfqOf(quote);
		submitQuote(quote, sellerX, "90");
		List<Integer> statuses = gated(rfq, 2, null,
				() -> send(post("/api/quotes/{id}/accept", quote), ergoRfq, null),
				() -> send(post("/api/quotes/{id}/accept", quote), ergoMgmt, null));
		assertOneWinner(statuses, "double accept");
		assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_name = 'Invitation' AND record_id = ? AND "
				+ "field_name = 'status' AND new_value = 'ACCEPTED'", quote)).isEqualTo(1);
		assertThat(rfqStatus(rfq)).isEqualTo(expectedRfqStatus(rfq));
	}

	@Test
	void acceptingAndTakingBackTheSameQuoteAtOnceLeavesTheRfqSummaryConsistent() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-1");
		long rfq = rfqOf(quote);
		submitQuote(quote, sellerX, "90");
		List<Integer> statuses = gated(rfq, 2, null,
				() -> send(post("/api/quotes/{id}/accept", quote), ergoRfq, null),
				() -> send(post("/api/quotes/{id}/recall", quote), ergoMgmt, "{\"comment\": \"other supplier\"}"));
		assertThat(statuses).as("accept vs recall " + statuses).noneMatch(s -> s >= 500);
		assertThat(statuses).contains(200);
		assertThat(rfqStatus(rfq)).as("part is " + statusOf(quote)).isEqualTo(expectedRfqStatus(rfq));
	}

	@Test
	void twoSuppliersSubmittingTheirPartsAtOnceStillMakeTheRfqComplete() throws Exception {
		for (int round = 0; round < 3; round++) {
			long rfq = reviewedRfq("PN-1", "PN-2");
			List<Long> p = parts(rfq);
			distribute(rfq, supplierX, List.of(p.get(0)), supplierY, List.of(p.get(1)));
			long q1 = quoteOf(sellerX, rfq, "PN-1");
			long q2 = quoteOf(sellerY, rfq, "PN-2");
			saveQuote(q1, sellerX, "90");
			saveQuote(q2, sellerY, "95");
			List<Integer> statuses = race(() -> send(post("/api/quotes/{id}/submit", q1), sellerX, null),
					() -> send(post("/api/quotes/{id}/submit", q2), sellerY, null));
			assertThat(statuses).as("two suppliers " + statuses).noneMatch(s -> s >= 500);
			// a loser of the optimistic lock may retry; the summary must then be right
			if (statusOf(q1).equals("DRAFT_SAVED")) {
				send(post("/api/quotes/{id}/submit", q1), sellerX, null);
			}
			if (statusOf(q2).equals("DRAFT_SAVED")) {
				send(post("/api/quotes/{id}/submit", q2), sellerY, null);
			}
			assertThat(rfqStatus(rfq)).isEqualTo("QUOTES_COMPLETE");
		}
	}

	@Test
	void anRfqSummaryComputedAfterAnotherPartChangeCommittedIsNotLostToAStaleCopyOfTheRfq() throws Exception {
		// part 1 submitted, part 2 not yet: QUOTES_PARTIAL. ERGO sends part 1 back while supplier Y submits part 2 (its
		// commit lands while ERGO's request waits for the RFQ lock). The truth afterwards: part 1 is back with its
		// supplier, part 2 is in -> QUOTES_PARTIAL. The slice-2 review (L4/L5) says two part changes cannot leave a
		// stale summary.
		long rfq = reviewedRfq("PN-1", "PN-2");
		List<Long> p = parts(rfq);
		distribute(rfq, supplierX, List.of(p.get(0)), supplierY, List.of(p.get(1)));
		long q1 = quoteOf(sellerX, rfq, "PN-1");
		long q2 = quoteOf(sellerY, rfq, "PN-2");
		submitQuote(q1, sellerX, "90");
		saveQuote(q2, sellerY, "95");
		assertThat(rfqStatus(rfq)).isEqualTo("QUOTES_PARTIAL");

		List<Integer> statuses = gated(rfq, 1, held -> {
			sql(held, "UPDATE rfq_supplier_assignments SET status = 'SUBMITTED', row_version = row_version + 1 WHERE assignment_id = " + q2);
			sql(held, "UPDATE rfqs SET status = 'QUOTES_COMPLETE', row_version = row_version + 1 WHERE rfq_id = " + rfq);
		}, () -> send(post("/api/quotes/{id}/revision", q1), ergoRfq, "{\"comment\": \"re-quote\"}"));
		assertThat(statuses).containsExactly(200);
		assertThat(statusOf(q1)).isEqualTo("REVISION_REQUIRED");
		assertThat(statusOf(q2)).isEqualTo("SUBMITTED");
		assertThat(rfqStatus(rfq)).as("the RFQ summary must reflect both parts").isEqualTo(expectedRfqStatus(rfq))
			.isEqualTo("QUOTES_PARTIAL");
	}

	// ---------------------------------------------------------------- customer quotation

	@Test
	void aDoubleClickOnGenerateQuotationMakesOneQuotation() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-1");
		long rfq = rfqOf(quote);
		driveTo(quote, sellerX, "ACCEPTED");
		String body = generateBody(line(quote, "120"));
		List<Integer> statuses = gated(rfq, 2, null,
				() -> send(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, body),
				() -> send(post("/api/rfqs/{id}/customer-quotes", rfq), ergoMgmt, body));
		assertOneWinner(statuses, "double generate");
		assertThat(count("SELECT count(*) FROM customer_quote_submissions WHERE rfq_id = ?", rfq)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM customer_quote_lines l JOIN customer_quote_submissions s ON "
				+ "s.customer_quote_submission_id = l.submission_id WHERE s.rfq_id = ?", rfq)).isEqualTo(1);
		assertThat(statusOf(quote)).isEqualTo("WITH_CUSTOMER");
		assertThat(rfqStatus(rfq)).isEqualTo("SENT_TO_CUSTOMER");
	}

	@Test
	void generatingWhileThePartIsBeingTakenBackNeverQuotesARecalledPart() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-1");
		long rfq = rfqOf(quote);
		driveTo(quote, sellerX, "ACCEPTED");
		List<Integer> statuses = gated(rfq, 2, null,
				() -> send(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(line(quote, "120"))),
				() -> send(post("/api/quotes/{id}/recall", quote), ergoMgmt, "{\"comment\": \"other supplier\"}"));
		assertThat(statuses).as("generate vs recall " + statuses).noneMatch(s -> s >= 500);
		// whichever won, a part is not both with the customer and recalled, and the summary is right
		String part = statusOf(quote);
		int quotations = count("SELECT count(*) FROM customer_quote_submissions WHERE rfq_id = ?", rfq);
		assertThat(part.equals("WITH_CUSTOMER") ? 1 : part.equals("RECALLED") ? 0 : -1).as("part " + part).isEqualTo(quotations);
		assertThat(rfqStatus(rfq)).isEqualTo(expectedRfqStatus(rfq));
	}

	@Test
	void theCustomerAnsweringTwiceAtOnceIsAnsweredOnce() throws Exception {
		Login colleague = users.login(customer, "CUSTOMER_RFQ");
		long quote = singlePartQuote(sellerX, supplierX, "PN-1");
		long rfq = rfqOf(quote);
		driveTo(quote, sellerX, "ACCEPTED");
		long quotation = generate(rfq, quote);
		List<Integer> statuses = gated(rfq, 2, null,
				() -> send(post("/api/customer-quotes/{id}/respond", quotation), buyer, answer("APPROVED", "go")),
				() -> send(post("/api/customer-quotes/{id}/respond", quotation), colleague, answer("DECLINED", "no")));
		assertOneWinner(statuses, "double respond");
		String response = jdbc.queryForObject("SELECT response_status FROM customer_quote_submissions WHERE "
				+ "customer_quote_submission_id = ?", String.class, quotation);
		// the part follows the one answer that was kept, and exactly one answer was audited
		assertThat(statusOf(quote)).isEqualTo(response.equals("APPROVED") ? "CUSTOMER_APPROVED" : "CUSTOMER_REJECTED");
		assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_name = 'CustomerQuote' AND record_id = ? AND "
				+ "field_name = 'responseStatus'", quotation)).isEqualTo(1);
		assertThat(rfqStatus(rfq)).isEqualTo(expectedRfqStatus(rfq));
	}

	@Test
	void theCustomerSendingTheSameAnswerTwiceIsAnsweredOnce() throws Exception {
		Login colleague = users.login(customer, "CUSTOMER_RFQ");
		long quote = singlePartQuote(sellerX, supplierX, "PN-1");
		long rfq = rfqOf(quote);
		driveTo(quote, sellerX, "ACCEPTED");
		long quotation = generate(rfq, quote);
		List<Integer> statuses = gated(rfq, 2, null,
				() -> send(post("/api/customer-quotes/{id}/respond", quotation), buyer, answer("REVISION_REQUESTED", "lower")),
				() -> send(post("/api/customer-quotes/{id}/respond", quotation), colleague, answer("REVISION_REQUESTED", "lower")));
		assertOneWinner(statuses, "duplicate respond");
		assertThat(statusOf(quote)).isEqualTo("CUSTOMER_CORRECTION");
	}

	// ---------------------------------------------------------------- RFQ header workflow

	@Test
	void twoErgoUsersStartingTheReviewAtOnceStartItOnce() throws Exception {
		for (int round = 0; round < 3; round++) {
			long rfq = com.ergo.connect.TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-1"));
			call(post("/api/rfqs/{id}/submit", rfq), buyer, "{}");
			List<Integer> statuses = race(() -> send(post("/api/rfqs/{id}/start-review", rfq), ergoRfq, null),
					() -> send(post("/api/rfqs/{id}/start-review", rfq), ergoMgmt, null));
			assertThat(statuses).as("start-review " + statuses).noneMatch(s -> s >= 500);
			assertThat(statuses).contains(200);
			assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_name = 'Rfq' AND record_id = ? AND "
					+ "field_name = 'status' AND new_value = 'UNDER_REVIEW'", rfq)).as("status audited once " + statuses).isEqualTo(1);
		}
	}

	@Test
	void theCustomerSubmittingTwiceAtOnceSubmitsOnce() throws Exception {
		Login colleague = users.login(customer, "CUSTOMER_RFQ");
		for (int round = 0; round < 3; round++) {
			long rfq = TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-1"));
			List<Integer> statuses = race(() -> send(post("/api/rfqs/{id}/submit", rfq), buyer, "{}"),
					() -> send(post("/api/rfqs/{id}/submit", rfq), colleague, "{}"));
			assertThat(statuses).as("submit " + statuses).noneMatch(s -> s >= 500);
			assertThat(statuses).contains(200);
			assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_name = 'Rfq' AND record_id = ? AND "
					+ "field_name = 'status' AND new_value = 'SUBMITTED'", rfq)).as("status audited once " + statuses).isEqualTo(1);
		}
	}

	@Test
	void anRfqEditRacingTheCustomersEditKeepsOneConsistentPartList() throws Exception {
		long rfq = TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-1", "PN-2"));
		List<Long> p = TestRfq.itemIds(mvc, ergoRfq, rfq);
		String one = TestRfq.form(inr, "PN-1").replace("\"partNumber\": \"PN-1\"", "\"id\": %d, \"partNumber\": \"PN-1\"".formatted(p.get(0)));
		String two = TestRfq.form(inr, "PN-1", "PN-2").replace("\"partNumber\": \"PN-1\"", "\"id\": %d, \"partNumber\": \"PN-1\"".formatted(p.get(0)))
			.replace("\"partNumber\": \"PN-2\"", "\"id\": %d, \"partNumber\": \"PN-2\"".formatted(p.get(1)));
		List<Integer> statuses = race(() -> send(put("/api/rfqs/{id}", rfq), buyer, one),
				() -> send(put("/api/rfqs/{id}", rfq), ergoRfq, two));
		assertThat(statuses).as("two edits " + statuses).noneMatch(s -> s >= 500);
		List<Integer> lineNos = jdbc.queryForList("SELECT line_no FROM rfq_items WHERE rfq_id = ? ORDER BY line_no", Integer.class, rfq);
		for (int i = 0; i < lineNos.size(); i++) {
			assertThat(lineNos.get(i)).as("line numbers stay 1..n without gaps or duplicates").isEqualTo(i + 1);
		}
	}

}
