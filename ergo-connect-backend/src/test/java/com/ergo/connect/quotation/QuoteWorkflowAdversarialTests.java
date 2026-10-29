package com.ergo.connect.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;

import com.ergo.connect.AdversarialSupport;
import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers.Login;
import com.ergo.connect.common.security.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * Adversarial coverage of the quote workflow per part (flow doc §3-5): every status against every action, reasons,
 * mixed part states, role x action, a cancelled / closed RFQ, input boundaries and the audit trail.
 */
@IntegrationTest
class QuoteWorkflowAdversarialTests extends AdversarialSupport {

	static Stream<String> states() {
		return ALL_STATES.stream();
	}

	// ---------------------------------------------------------------- illegal transitions

	@ParameterizedTest(name = "{0}")
	@MethodSource("states")
	void actionsThatAreNotAllowedInAStatusAreRefusedAndChangeNothing(String state) throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-M" + ALL_STATES.indexOf(state));
		driveTo(quote, sellerX, state);

		if (state.equals("RECALLED")) {
			// a recalled part does not exist for the supplier it was taken from, nor for anyone else
			for (Login user : List.of(sellerX, ergoRfq)) {
				call(get("/api/quotes/{id}", quote), user, null).andExpect(status().isNotFound());
				call(get("/api/quotes/{id}/versions", quote), user, null).andExpect(status().isNotFound());
				call(get("/api/quotes/{id}/documents", quote), user, null).andExpect(status().isNotFound());
			}
			call(put("/api/quotes/{id}", quote), sellerX, TestRfq.supplierQuote(inr, "70")).andExpect(status().isNotFound());
			call(post("/api/quotes/{id}/submit", quote), sellerX, null).andExpect(status().isNotFound());
			call(post("/api/quotes/{id}/accept", quote), ergoRfq, null).andExpect(status().isNotFound());
			call(post("/api/quotes/{id}/revision", quote), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isNotFound());
			call(post("/api/quotes/{id}/recall", quote), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isNotFound());
			upload("/api/quotes/{id}/documents", quote, ergoRfq, "late.pdf").andExpect(status().isNotFound());
			return;
		}
		Set<String> editable = Set.of("NOT_STARTED", "DRAFT_SAVED", "REVISION_REQUIRED");
		Set<String> acceptable = Set.of("SUBMITTED", "RESUBMITTED");
		Set<String> returnable = Set.of("SUBMITTED", "RESUBMITTED", "ACCEPTED", "CUSTOMER_CORRECTION");
		Set<String> frozenForRecall = Set.of("WITH_CUSTOMER", "CUSTOMER_ON_HOLD", "CUSTOMER_APPROVED",
				"CUSTOMER_REJECTED");

		if (!editable.contains(state)) {
			call(put("/api/quotes/{id}", quote), sellerX, TestRfq.supplierQuote(inr, "70")).andExpect(status().isConflict());
			call(post("/api/quotes/{id}/submit", quote), sellerX, null).andExpect(status().isConflict());
			upload("/api/quotes/{id}/documents", quote, sellerX, "late.pdf").andExpect(status().isConflict());
		}
		if (!acceptable.contains(state)) {
			call(post("/api/quotes/{id}/accept", quote), ergoRfq, null).andExpect(status().isConflict());
			call(post("/api/quotes/{id}/accept", quote), ergoMgmt, null).andExpect(status().isConflict());
		}
		if (!returnable.contains(state)) {
			call(post("/api/quotes/{id}/revision", quote), ergoRfq, "{\"comment\": \"change it\"}")
				.andExpect(status().isConflict());
		}
		if (frozenForRecall.contains(state)) {
			call(post("/api/quotes/{id}/recall", quote), ergoRfq, "{\"comment\": \"take it back\"}")
				.andExpect(status().isConflict());
		}
		assertThat(statusOf(quote)).isEqualTo(state);

		// the supplier never learns what the customer did: everything from "accepted" on reads as accepted
		String seen = text(get("/api/quotes/{id}", quote), sellerX, null);
		String supplierStatus = JsonPath.read(seen, "$.status");
		if (Set.of("ACCEPTED", "WITH_CUSTOMER", "CUSTOMER_ON_HOLD", "CUSTOMER_CORRECTION", "CUSTOMER_APPROVED",
				"CUSTOMER_REJECTED").contains(state)) {
			assertThat(supplierStatus).isEqualTo("ACCEPTED");
		}
		assertThat(seen).doesNotContain("WITH_CUSTOMER", "CUSTOMER_ON_HOLD", "CUSTOMER_CORRECTION", "CUSTOMER_APPROVED",
				"CUSTOMER_REJECTED");
		assertThat(text(get("/api/quotes"), sellerX, null)).doesNotContain("WITH_CUSTOMER", "CUSTOMER_ON_HOLD",
				"CUSTOMER_CORRECTION", "CUSTOMER_APPROVED", "CUSTOMER_REJECTED");
	}

	// ---------------------------------------------------------------- reasons

	@Test
	void everyReturnAndRecallNeedsARealReasonAndLeavesTheQuoteAloneWithout() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-R1");
		driveTo(quote, sellerX, "SUBMITTED");
		for (String body : List.of("{}", "{\"comment\": null}", "{\"comment\": \"\"}", "{\"comment\": \"   \"}",
				"{\"comment\": \"\\n\\t \"}")) {
			call(post("/api/quotes/{id}/revision", quote), ergoRfq, body).andExpect(status().isUnprocessableContent());
			call(post("/api/quotes/{id}/recall", quote), ergoRfq, body).andExpect(status().isUnprocessableContent());
		}
		// no body at all, and an oversize reason, are bad requests rather than server errors
		call(post("/api/quotes/{id}/revision", quote), ergoRfq, null).andExpect(status().is4xxClientError());
		call(post("/api/quotes/{id}/revision", quote), ergoRfq, "{\"comment\": \"%s\"}".formatted("x".repeat(2001)))
			.andExpect(status().isBadRequest());
		call(post("/api/quotes/{id}/recall", quote), ergoRfq, "{\"comment\": \"%s\"}".formatted("x".repeat(2001)))
			.andExpect(status().isBadRequest());
		assertThat(statusOf(quote)).isEqualTo("SUBMITTED");
		assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_name = 'Invitation' AND record_id = ? "
				+ "AND field_name = 'status' AND new_value IN ('REVISION_REQUIRED', 'RECALLED')", quote)).isZero();
		// exactly 2000 characters is fine
		call(post("/api/quotes/{id}/revision", quote), ergoRfq, "{\"comment\": \"%s\"}".formatted("x".repeat(2000)))
			.andExpect(status().isOk());
	}

	@Test
	void everyCustomerDecisionNeedsACommentAndAnUnansweredQuotationStaysUnanswered() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-D1");
		driveTo(quote, sellerX, "ACCEPTED");
		long rfq = rfqOf(quote);
		long quotation = generate(rfq, quote);
		for (String response : List.of("APPROVED", "ON_HOLD", "DECLINED", "REVISION_REQUESTED")) {
			for (String comment : List.of("{\"response\": \"%s\"}", "{\"response\": \"%s\", \"comment\": null}",
					"{\"response\": \"%s\", \"comment\": \"\"}", "{\"response\": \"%s\", \"comment\": \"   \"}",
					"{\"response\": \"%s\", \"comment\": \"\\n \\t\"}")) {
				call(post("/api/customer-quotes/{id}/respond", quotation), buyer, comment.formatted(response))
					.andExpect(status().isUnprocessableContent());
			}
			call(post("/api/customer-quotes/{id}/respond", quotation), buyer,
					answer(response, "x".repeat(2001))).andExpect(status().isBadRequest());
		}
		// a missing / unknown decision is a bad request, not a server error
		call(post("/api/customer-quotes/{id}/respond", quotation), buyer, "{\"comment\": \"yes\"}")
			.andExpect(status().isBadRequest());
		call(post("/api/customer-quotes/{id}/respond", quotation), buyer, answer("MAYBE", "yes"))
			.andExpect(status().isBadRequest());
		call(post("/api/customer-quotes/{id}/respond", quotation), buyer, answer("WAITING", "yes"))
			.andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT response_status FROM customer_quote_submissions WHERE "
				+ "customer_quote_submission_id = ?", String.class, quotation)).isEqualTo("WAITING");
		assertThat(statusOf(quote)).isEqualTo("WITH_CUSTOMER");
		assertThat(rfqStatus(rfq)).isEqualTo("SENT_TO_CUSTOMER");
		// a 2000 character comment is accepted
		call(post("/api/customer-quotes/{id}/respond", quotation), buyer, answer("APPROVED", "y".repeat(2000)))
			.andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- mixed part states

	@Test
	void mixedPartStatesFollowThePrecedenceAndARecalledPartCanBeSentToAnotherSupplier() throws Exception {
		long rfq = reviewedRfq("PN-1", "PN-2", "PN-3");
		List<Long> p = parts(rfq);
		distribute(rfq, supplierX, List.of(p.get(0), p.get(1)), supplierY, List.of(p.get(2)));
		assertThat(rfqStatus(rfq)).isEqualTo("SENT_TO_SUPPLIERS");
		long q1 = quoteOf(sellerX, rfq, "PN-1");
		long q2 = quoteOf(sellerX, rfq, "PN-2");
		long q3 = quoteOf(sellerY, rfq, "PN-3");

		submitQuote(q1, sellerX, "90");
		assertThat(rfqStatus(rfq)).isEqualTo("QUOTES_PARTIAL");
		submitQuote(q2, sellerX, "91");
		assertThat(rfqStatus(rfq)).isEqualTo("QUOTES_PARTIAL");
		submitQuote(q3, sellerY, "95");
		assertThat(rfqStatus(rfq)).isEqualTo("QUOTES_COMPLETE");
		accept(q1);
		accept(q2);
		accept(q3);
		assertThat(rfqStatus(rfq)).isEqualTo("QUOTES_COMPLETE");

		long qa = generate(rfq, q1);
		assertThat(rfqStatus(rfq)).isEqualTo("SENT_TO_CUSTOMER");
		respond(qa, "APPROVED");
		assertThat(rfqStatus(rfq)).as("one part approved, two accepted and waiting").isEqualTo("QUOTES_COMPLETE");

		long qb = generate(rfq, q2);
		respond(qb, "REVISION_REQUESTED");
		assertThat(rfqStatus(rfq)).as("correction beats an accepted part").isEqualTo("CUSTOMER_REVISION_REQUESTED");
		call(get("/api/rfqs/{id}", rfq), buyer, null).andExpect(jsonPath("$.status").value("REVISION_REQUESTED"));

		long qc = generate(rfq, q3);
		assertThat(rfqStatus(rfq)).as("with-customer beats correction").isEqualTo("SENT_TO_CUSTOMER");
		respond(qc, "ON_HOLD");
		assertThat(rfqStatus(rfq)).as("on-hold beats correction").isEqualTo("CUSTOMER_ON_HOLD");
		call(get("/api/rfqs/{id}", rfq), buyer, null).andExpect(jsonPath("$.status").value("ON_HOLD"));
		respond(qc, "APPROVED");
		assertThat(rfqStatus(rfq)).as("2 approved, 1 still in correction").isEqualTo("CUSTOMER_REVISION_REQUESTED");
		assertThat(statusOf(q2)).isEqualTo("CUSTOMER_CORRECTION");

		// ERGO takes the corrected part back (an approving role) and the RFQ falls back to "quotes partly in"
		call(post("/api/quotes/{id}/recall", q2), ergoRfq, "{\"comment\": \"Supplier X cannot make it\"}")
			.andExpect(jsonPath("$.status").value("RECALLED"));
		assertThat(rfqStatus(rfq)).isEqualTo("QUOTES_PARTIAL");
		call(get("/api/rfqs/{id}/parts", rfq), ergoRfq, null)
			.andExpect(jsonPath("$[*].status", contains("CUSTOMER_APPROVED", "UNASSIGNED", "CUSTOMER_APPROVED")));
		call(get("/api/rfqs/{id}/parts", rfq), buyer, null)
			.andExpect(jsonPath("$[*].status", contains("APPROVED", "IN_PROGRESS", "APPROVED")));
		call(get("/api/quotes/{id}", q2), sellerX, null).andExpect(status().isNotFound());
		call(get("/api/quotes"), sellerX, null).andExpect(jsonPath("$.items", hasSize(1)));

		// sent again, to the other supplier: its own sub-RFQ numbering, ERGO's global one
		distribute(rfq, supplierY, List.of(p.get(1)));
		assertThat(rfqStatus(rfq)).isEqualTo("QUOTES_PARTIAL");
		long q2b = quoteOf(sellerY, rfq, "PN-2");
		call(get("/api/quotes/{id}", q2b), sellerY, null).andExpect(jsonPath("$.subRfqNo").value(2));
		call(get("/api/quotes/{id}", q3), sellerY, null).andExpect(jsonPath("$.subRfqNo").value(1));
		call(get("/api/quotes/{id}", q2b), ergoRfq, null).andExpect(jsonPath("$.subRfqNo").value(3));
		submitQuote(q2b, sellerY, "88");
		assertThat(rfqStatus(rfq)).isEqualTo("QUOTES_COMPLETE");
		accept(q2b);
		long qd = generate(rfq, q2b);
		respond(qd, "DECLINED");
		assertThat(rfqStatus(rfq)).as("every part decided, two approved").isEqualTo("CUSTOMER_APPROVED");
		call(get("/api/rfqs/{id}/parts", rfq), buyer, null)
			.andExpect(jsonPath("$[*].status", contains("APPROVED", "REJECTED", "APPROVED")));
		call(get("/api/rfqs/{id}/customer-quotes", rfq), buyer, null)
			.andExpect(jsonPath("$.quotations[*].version", contains(4, 3, 2, 1)))
			.andExpect(jsonPath("$.quotations[*].status", contains("DECLINED", "APPROVED", "REVISION_REQUESTED", "APPROVED")));

		// decided parts and a finished RFQ are final
		call(post("/api/quotes/{id}/recall", q2b), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isConflict());
		call(post("/api/quotes/{id}/revision", q2b), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isConflict());
		call(post("/api/quotes/{id}/recall", q1), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierX, List.of(p.get(1))))
			.andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(line(q1, "100")))
			.andExpect(status().isConflict());
		call(put("/api/quotes/{id}", q2b), sellerY, TestRfq.supplierQuote(inr, "1")).andExpect(status().isConflict());
	}

	@Test
	void takingBackAnAcceptedPartMakesTheRfqPartialAgainAndTheOldSupplierLosesIt() throws Exception {
		long rfq = reviewedRfq("PN-1", "PN-2");
		List<Long> p = parts(rfq);
		distribute(rfq, supplierX, p);
		long q1 = quoteOf(sellerX, rfq, "PN-1");
		long q2 = quoteOf(sellerX, rfq, "PN-2");
		submitQuote(q1, sellerX, "90");
		submitQuote(q2, sellerX, "90");
		accept(q1);
		accept(q2);
		assertThat(rfqStatus(rfq)).isEqualTo("QUOTES_COMPLETE");
		call(post("/api/quotes/{id}/recall", q2), ergoRfq, "{\"comment\": \"capacity\"}").andExpect(status().isOk());
		assertThat(rfqStatus(rfq)).isEqualTo("QUOTES_PARTIAL");
		// the recalled part cannot be put in a customer quotation
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(line(q2, "100")))
			.andExpect(status().isConflict());
		// the same part cannot be sent to the same supplier twice either, but can to another one
		distribute(rfq, supplierY, List.of(p.get(1)));
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierX, List.of(p.get(1))))
			.andExpect(status().isUnprocessableContent());
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_item_id = ? AND status <> 'RECALLED'",
				p.get(1))).isEqualTo(1);
		// the old quote and its versions are kept on record
		assertThat(count("SELECT count(*) FROM supplier_quote_versions v JOIN supplier_quotes s "
				+ "ON s.supplier_quote_id = v.supplier_quote_id WHERE s.assignment_id = ?", q2)).isEqualTo(1);
	}

	@Test
	void anRfqCannotBeCancelledOnceASupplierHasRespondedEvenIfASentBackQuoteLowersTheDerivedStatus() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-C");
		long rfq = rfqOf(quote);
		submitQuote(quote, sellerX, "90");
		call(post("/api/quotes/{id}/revision", quote), ergoRfq, "{\"comment\": \"re-quote\"}").andExpect(status().isOk());
		// SRS 17.1: "Cancel RFQ -> only before supplier response" - the supplier has responded, so only closing is possible
		call(post("/api/rfqs/{id}/cancel", rfq), ergoRfq, "{\"comment\": \"Customer withdrew\"}")
			.andExpect(status().isConflict());
		assertThat(rfqStatus(rfq)).isNotEqualTo("CANCELLED");
		assertThat(count("SELECT count(*) FROM supplier_quote_versions v JOIN supplier_quotes s "
				+ "ON s.supplier_quote_id = v.supplier_quote_id WHERE s.assignment_id = ?", quote)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- cancelled / closed RFQ

	@Test
	void nothingCanBeDoneOnACancelledRfqAndASupplierSeesItIsNoLongerOpen() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-X");
		long rfq = rfqOf(quote);
		saveQuote(quote, sellerX, "90");
		call(post("/api/rfqs/{id}/cancel", rfq), ergoRfq, "{\"comment\": \"Dropped\"}")
			.andExpect(jsonPath("$.status").value("CANCELLED"));
		call(put("/api/quotes/{id}", quote), sellerX, TestRfq.supplierQuote(inr, "70")).andExpect(status().isConflict());
		call(post("/api/quotes/{id}/submit", quote), sellerX, null).andExpect(status().isConflict());
		upload("/api/quotes/{id}/documents", quote, sellerX, "late.pdf").andExpect(status().isConflict());
		call(post("/api/quotes/{id}/recall", quote), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierY, parts(rfq)))
			.andExpect(status().isConflict());
		call(get("/api/quotes"), sellerX, null).andExpect(jsonPath("$.items[0].quotingOpen").value(false));
		assertThat(statusOf(quote)).isEqualTo("DRAFT_SAVED");
		// the customer cannot revive it
		call(post("/api/rfqs/{id}/submit", rfq), buyer, "{}").andExpect(status().isConflict());
		call(put("/api/rfqs/{id}", rfq), buyer, TestRfq.form(inr, "PN-X")).andExpect(status().isConflict());
	}

	@Test
	void aClosedRfqRefusesTheCustomersAnswerAndErgosNextStep() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-K");
		long rfq = rfqOf(quote);
		driveTo(quote, sellerX, "ACCEPTED");
		long quotation = generate(rfq, quote);
		call(post("/api/rfqs/{id}/close", rfq), ergoRfq, "{}").andExpect(status().isUnprocessableContent());
		call(post("/api/rfqs/{id}/close", rfq), ergoRfq, "{\"comment\": \"Program lost\"}")
			.andExpect(jsonPath("$.status").value("CLOSED"));
		call(post("/api/customer-quotes/{id}/respond", quotation), buyer, answer("APPROVED", "go")).andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(line(quote, "100")))
			.andExpect(status().isConflict());
		call(post("/api/quotes/{id}/revision", quote), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isConflict());
		call(post("/api/quotes/{id}/recall", quote), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isConflict());
		assertThat(statusOf(quote)).isEqualTo("WITH_CUSTOMER");
		assertThat(rfqStatus(rfq)).isEqualTo("CLOSED");
		// closing again, and cancelling a closed RFQ, are illegal transitions
		call(post("/api/rfqs/{id}/close", rfq), ergoRfq, "{\"comment\": \"again\"}").andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/cancel", rfq), ergoRfq, "{\"comment\": \"again\"}").andExpect(status().isConflict());
	}

	// ---------------------------------------------------------------- role x action

	@Test
	void onlyTheOwningErgoRolesDistributeAcceptReturnRecallAndGenerate() throws Exception {
		Login admin = users.login(ergoCo, "ERGO_ADMIN");
		Login kam = users.login(ergoCo, "ERGO_KAM");
		users.assignAccounts(kam.userId(), customer);
		Login npd = users.login(ergoCo, "ERGO_NPD");
		Login ofp = users.login(ergoCo, "ERGO_OFP");
		Login qa = users.login(ergoCo, "ERGO_QUALITY");
		Login customerKam = users.login(customer, "CUSTOMER_KAM");
		Login supplierNpd = users.login(supplierX, "SUPPLIER_NPD");
		List<Login> denied = List.of(admin, kam, npd, ofp, qa, buyer, customerKam, sellerX, supplierNpd);

		long rfq = reviewedRfq("PN-1", "PN-2");
		List<Long> p = parts(rfq);
		for (Login user : denied) {
			call(post("/api/rfqs/{id}/quotes", rfq), user, TestRfq.distribution(supplierX, p)).andExpect(status().isForbidden());
		}
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_id = ?", rfq)).isZero();
		// Management holds the same "O" as ERGO RFQ
		call(post("/api/rfqs/{id}/quotes", rfq), ergoMgmt, TestRfq.distribution(supplierX, p)).andExpect(status().isOk());

		long q1 = quoteOf(sellerX, rfq, "PN-1");
		long q2 = quoteOf(sellerX, rfq, "PN-2");
		submitQuote(q1, sellerX, "90");
		submitQuote(q2, sellerX, "90");
		for (Login user : denied) {
			call(post("/api/quotes/{id}/accept", q1), user, null).andExpect(status().isForbidden());
			call(post("/api/quotes/{id}/revision", q1), user, "{\"comment\": \"x\"}").andExpect(status().isForbidden());
			call(post("/api/quotes/{id}/recall", q1), user, "{\"comment\": \"x\"}").andExpect(status().isForbidden());
		}
		assertThat(statusOf(q1)).isEqualTo("SUBMITTED");
		call(post("/api/quotes/{id}/accept", q1), ergoMgmt, null).andExpect(jsonPath("$.status").value("ACCEPTED"));
		accept(q2);
		for (Login user : denied) {
			call(post("/api/rfqs/{id}/customer-quotes", rfq), user, generateBody(line(q1, "100"))).andExpect(status().isForbidden());
		}
		assertThat(count("SELECT count(*) FROM customer_quote_submissions WHERE rfq_id = ?", rfq)).isZero();
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoMgmt, generateBody(line(q1, "100"))).andExpect(status().isOk());

		// quote work is a supplier-side job: other ERGO / customer roles and supplier roles without Quotation are refused
		long quote = singlePartQuote(sellerX, supplierX, "PN-S");
		for (Login user : List.of(ergoRfq, ergoMgmt, admin, buyer, customerKam, supplierNpd, users.login(supplierX, "SUPPLIER_QUALITY"))) {
			call(put("/api/quotes/{id}", quote), user, TestRfq.supplierQuote(inr, "70")).andExpect(status().isForbidden());
			call(post("/api/quotes/{id}/submit", quote), user, null).andExpect(status().isForbidden());
		}
		assertThat(statusOf(quote)).isEqualTo("NOT_STARTED");
	}

	@Test
	void aSupplierRoleWithRfqButNoQuotationAccessSeesThePartNeverTheQuote() throws Exception {
		Login quality = users.login(supplierX, "SUPPLIER_QUALITY"); // RFQ V, Quotation NV on the sheet
		long quote = singlePartQuote(sellerX, supplierX, "PN-V");
		driveTo(quote, sellerX, "SUBMITTED");
		String body = text(get("/api/quotes/{id}", quote), quality, null);
		assertThat(body).doesNotContain("productionPiecePrice", "\"quote\"", "toolingCostTotal", "revisionReason");
		call(get("/api/quotes/{id}/versions", quote), quality, null).andExpect(status().isForbidden());
		assertThat(text(get("/api/quotes"), quality, null)).doesNotContain("version", "submittedAt");
		call(get("/api/quotes/{id}/documents", quote), quality, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void onlyTheCustomersApprovingRoleDecidesAndEveryOtherRoleIsRefused() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-P");
		driveTo(quote, sellerX, "ACCEPTED");
		long quotation = generate(rfqOf(quote), quote);
		Login admin = users.login(ergoCo, "ERGO_ADMIN");
		Login customerKam = users.login(customer, "CUSTOMER_KAM");
		for (Login user : List.of(ergoRfq, ergoMgmt, admin, sellerX, sellerY, users.login(customer, "CUSTOMER_NPD"),
				users.login(customer, "CUSTOMER_QUALITY"), users.login(customer, "CUSTOMER_OFP"))) {
			for (String response : List.of("APPROVED", "DECLINED", "ON_HOLD", "REVISION_REQUESTED")) {
				call(post("/api/customer-quotes/{id}/respond", quotation), user, answer(response, "x"))
					.andExpect(status().isForbidden());
			}
		}
		call(post("/api/customer-quotes/{id}/respond", quotation), buyerB, answer("APPROVED", "x")).andExpect(status().isNotFound());
		assertThat(statusOf(quote)).isEqualTo("WITH_CUSTOMER");
		// the customer's KAM ("A" on Quotation) may only ask for a correction, and only with a comment
		for (String response : List.of("APPROVED", "DECLINED", "ON_HOLD")) {
			call(post("/api/customer-quotes/{id}/respond", quotation), customerKam, answer(response, "x"))
				.andExpect(status().isForbidden());
		}
		call(get("/api/rfqs/{id}/customer-quotes", rfqOf(quote)), customerKam, null)
			.andExpect(jsonPath("$.quotations[0].actions", contains("REQUEST_REVISION")));
		call(post("/api/customer-quotes/{id}/respond", quotation), customerKam, "{\"response\": \"REVISION_REQUESTED\"}")
			.andExpect(status().isUnprocessableContent());
		call(post("/api/customer-quotes/{id}/respond", quotation), customerKam, answer("REVISION_REQUESTED", "Lower please"))
			.andExpect(status().isOk());
		assertThat(statusOf(quote)).isEqualTo("CUSTOMER_CORRECTION");
		assertThat(jdbc.queryForObject("SELECT response_status FROM customer_quote_submissions WHERE "
				+ "customer_quote_submission_id = ?", String.class, quotation)).isEqualTo("REVISION_REQUESTED");
	}

	@Test
	void undoingAnAcceptanceOrACorrectionNeedsAnApprovingRoleNotJustAnActingOne() throws Exception {
		Login admin = users.login(ergoCo, "ERGO_KAM"); // the KAM is raised to "A" on Quotation: acts, gives no final approval
		users.assignAccounts(admin.userId(), customer);
		level(Role.ERGO_KAM, "QUOTATION", "A");
		long submitted = singlePartQuote(sellerX, supplierX, "PN-A1");
		driveTo(submitted, sellerX, "SUBMITTED");
		long accepted = singlePartQuote(sellerX, supplierX, "PN-A2");
		driveTo(accepted, sellerX, "ACCEPTED");
		long correction = singlePartQuote(sellerX, supplierX, "PN-A3");
		driveTo(correction, sellerX, "CUSTOMER_CORRECTION");

		call(post("/api/quotes/{id}/accept", submitted), admin, null).andExpect(status().isForbidden());
		for (long quote : List.of(accepted, correction)) {
			call(post("/api/quotes/{id}/revision", quote), admin, "{\"comment\": \"x\"}").andExpect(status().isConflict());
			call(post("/api/quotes/{id}/recall", quote), admin, "{\"comment\": \"x\"}").andExpect(status().isConflict());
			call(get("/api/quotes/{id}", quote), admin, null)
				.andExpect(jsonPath("$.actions", not(hasItem("REQUEST_REVISION"))))
				.andExpect(jsonPath("$.actions", not(hasItem("RECALL"))));
		}
		assertThat(statusOf(accepted)).isEqualTo("ACCEPTED");
		assertThat(statusOf(correction)).isEqualTo("CUSTOMER_CORRECTION");
		// an "A" role may still send a submitted quote back and take an unaccepted part away
		call(post("/api/quotes/{id}/revision", submitted), admin, "{\"comment\": \"re-quote\"}").andExpect(status().isOk());
		call(post("/api/quotes/{id}/recall", submitted), admin, "{\"comment\": \"other supplier\"}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/customer-quotes", rfqOf(accepted)), admin, generateBody(line(accepted, "100")))
			.andExpect(status().isForbidden());
		// the approving role can undo both
		call(post("/api/quotes/{id}/revision", accepted), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isOk());
		call(post("/api/quotes/{id}/recall", correction), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- input boundaries

	@Test
	void aSupplierQuoteRejectsBadAmountsCurrenciesAndLengthsWithoutSavingAnything() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-B");
		String ok = TestRfq.supplierQuote(inr, "90");
		for (String bad : List.of(
				ok.replace("\"productionPiecePrice\": 90", "\"productionPiecePrice\": -1"),
				ok.replace("\"productionPiecePrice\": 90", "\"productionPiecePrice\": 1.00001"), // 5 decimals
				ok.replace("\"productionPiecePrice\": 90", "\"productionPiecePrice\": 123456789012345"), // 15 digits
				ok.replace("\"productionPiecePrice\": 90", "\"productionPiecePrice\": \"ninety\""),
				ok.replace("\"toolingCostTotal\": 50000", "\"toolingCostTotal\": -0.0001"),
				ok.replace("\"rawMaterialCost\": 40", "\"rawMaterialCost\": -40"),
				ok.replace("\"profit\": 6", "\"profit\": -6"),
				ok.replace("\"moq\": 500", "\"moq\": 0"), // SRS 17.2: MOQ must be > 0
				ok.replace("\"moq\": 500", "\"moq\": -1"),
				ok.replace("\"productionLeadTimeDays\": 45", "\"productionLeadTimeDays\": -1"),
				ok.replace("\"moq\": 500", "\"quoteReference\": \"%s\", \"moq\": 500".formatted("R".repeat(51))),
				ok.replace("\"moq\": 500", "\"remarks\": \"%s\", \"moq\": 500".formatted("r".repeat(4001))),
				ok.replace("\"moq\": 500", "\"tooling\": [{\"toolName\": \"die\", \"toolingCost\": -5}], \"moq\": 500"),
				ok.replace("\"moq\": 500", "\"tooling\": [{\"toolName\": \"\", \"toolingCost\": 5}], \"moq\": 500"),
				ok.replace("\"moq\": 500", "\"processSteps\": [{\"stepName\": \"A\", \"sourcingType\": \"MAGIC\"}], \"moq\": 500"),
				"{not json", "[]")) {
			call(put("/api/quotes/{id}", quote), sellerX, bad).andExpect(status().isBadRequest());
		}
		// unknown master data: a business-rule refusal
		call(put("/api/quotes/{id}", quote), sellerX, ok.replace("\"currencyId\": " + inr, "\"currencyId\": 987654321"))
			.andExpect(status().isUnprocessableContent());
		call(put("/api/quotes/{id}", quote), sellerX, ok.replace("\"moq\": 500", "\"incotermId\": 987654321, \"moq\": 500"))
			.andExpect(status().isUnprocessableContent());
		call(put("/api/quotes/{id}", quote), sellerX, ok.replace("\"moq\": 500", "\"paymentTermId\": 987654321, \"moq\": 500"))
			.andExpect(status().isUnprocessableContent());
		assertThat(count("SELECT count(*) FROM supplier_quotes WHERE assignment_id = ?", quote)).isZero();
		assertThat(statusOf(quote)).isEqualTo("NOT_STARTED");

		// every money field needs the currency (currency is tagged on every money row)
		for (String money : List.of("\"toolingCostTotal\": 1", "\"ppapPiecePrice\": 1", "\"productionPiecePrice\": 1",
				"\"rawMaterialCost\": 1", "\"processingCost\": 1", "\"overheadCost\": 1", "\"packagingCost\": 1",
				"\"profit\": 1", "\"tooling\": [{\"toolName\": \"die\", \"toolingCost\": 1}]")) {
			call(put("/api/quotes/{id}", quote), sellerX, "{" + money + "}").andExpect(status().isUnprocessableContent());
		}
		assertThat(count("SELECT count(*) FROM supplier_quotes WHERE assignment_id = ?", quote)).isZero();

		// the extremes that are legal are stored exactly
		call(put("/api/quotes/{id}", quote), sellerX, ok.replace("\"productionPiecePrice\": 90",
				"\"productionPiecePrice\": 12345678901234.5678").replace("\"moq\": 500", "\"moq\": 1")).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT production_piece_price FROM supplier_quotes WHERE assignment_id = ?",
				BigDecimal.class, quote)).isEqualByComparingTo("12345678901234.5678");
		call(put("/api/quotes/{id}", quote), sellerX, ok.replace("\"productionPiecePrice\": 90",
				"\"productionPiecePrice\": 0.0001")).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT production_piece_price FROM supplier_quotes WHERE assignment_id = ?",
				BigDecimal.class, quote)).isEqualByComparingTo("0.0001");
	}

	@Test
	void zeroOrExpiredQuotesCannotBeSubmitted() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-Z");
		String ok = TestRfq.supplierQuote(inr, "90");
		call(put("/api/quotes/{id}", quote), sellerX, ok.replace("\"productionPiecePrice\": 90", "\"productionPiecePrice\": 0"))
			.andExpect(status().isOk()); // a draft may be incomplete
		call(post("/api/quotes/{id}/submit", quote), sellerX, null).andExpect(status().is4xxClientError());
		assertThat(statusOf(quote)).as("a zero piece price is not a quote").isEqualTo("DRAFT_SAVED");

		call(put("/api/quotes/{id}", quote), sellerX, ok.replace(LocalDate.now().plusDays(60).toString(),
				LocalDate.now().minusDays(1).toString())).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", quote), sellerX, null).andExpect(status().isUnprocessableContent());
		// validity today is the last valid day
		call(put("/api/quotes/{id}", quote), sellerX, ok.replace(LocalDate.now().plusDays(60).toString(),
				LocalDate.now().toString())).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", quote), sellerX, null).andExpect(status().isOk());
	}

	@Test
	void distributeRejectsEveryMalformedRequestAndCreatesNothing() throws Exception {
		long rfq = reviewedRfq("PN-1", "PN-2");
		List<Long> p = parts(rfq);
		String due = LocalDate.now().plusDays(7).toString();
		Map<String, String> bad = new java.util.LinkedHashMap<>();
		bad.put("no sub-rfqs", "{\"subRfqs\": [], \"responseDueDate\": \"%s\"}".formatted(due));
		bad.put("null sub-rfqs", "{\"responseDueDate\": \"%s\"}".formatted(due));
		bad.put("no due date", "{\"subRfqs\": [{\"supplierId\": %d, \"itemIds\": [%d]}]}".formatted(supplierX, p.get(0)));
		bad.put("past due date", "{\"subRfqs\": [{\"supplierId\": %d, \"itemIds\": [%d]}], \"responseDueDate\": \"%s\"}"
			.formatted(supplierX, p.get(0), LocalDate.now().minusDays(1)));
		bad.put("empty parts", "{\"subRfqs\": [{\"supplierId\": %d, \"itemIds\": []}], \"responseDueDate\": \"%s\"}".formatted(supplierX, due));
		bad.put("no supplier", "{\"subRfqs\": [{\"itemIds\": [%d]}], \"responseDueDate\": \"%s\"}".formatted(p.get(0), due));
		bad.put("null part id", "{\"subRfqs\": [{\"supplierId\": %d, \"itemIds\": [null]}], \"responseDueDate\": \"%s\"}".formatted(supplierX, due));
		bad.put("string ids", "{\"subRfqs\": [{\"supplierId\": \"x\", \"itemIds\": [\"y\"]}], \"responseDueDate\": \"%s\"}".formatted(due));
		bad.put("bad date", "{\"subRfqs\": [{\"supplierId\": %d, \"itemIds\": [%d]}], \"responseDueDate\": \"31/12/2030\"}".formatted(supplierX, p.get(0)));
		bad.put("51 sub-rfqs", "{\"subRfqs\": [%s], \"responseDueDate\": \"%s\"}".formatted(
				String.join(",", java.util.Collections.nCopies(51, "{\"supplierId\": %d, \"itemIds\": [%d]}".formatted(supplierX, p.get(0)))), due));
		for (var entry : bad.entrySet()) {
			assertThat(send(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, entry.getValue())).as(entry.getKey()).isEqualTo(400);
		}
		Map<String, String> unprocessable = new java.util.LinkedHashMap<>();
		unprocessable.put("unknown supplier", TestRfq.distribution(987654321L, List.of(p.get(0))));
		unprocessable.put("a customer is not a supplier", TestRfq.distribution(customer, List.of(p.get(0))));
		unprocessable.put("ERGO is not a supplier", TestRfq.distribution(ergoCo, List.of(p.get(0))));
		unprocessable.put("unknown part", TestRfq.distribution(supplierX, List.of(987654321L)));
		unprocessable.put("negative part", TestRfq.distribution(supplierX, List.of(-1L)));
		unprocessable.put("same supplier twice", TestRfq.distribution(supplierX, List.of(p.get(0)), supplierX, List.of(p.get(1))));
		unprocessable.put("same part to two suppliers", TestRfq.distribution(supplierX, List.of(p.get(0)), supplierY, List.of(p.get(0))));
		for (var entry : unprocessable.entrySet()) {
			assertThat(send(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, entry.getValue())).as(entry.getKey()).isEqualTo(422);
		}
		jdbc.update("UPDATE companies SET status = 'INACTIVE' WHERE company_id = ?", supplierZ);
		assertThat(send(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierZ, List.of(p.get(0)))))
			.as("inactive supplier").isEqualTo(422);
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_id = ?", rfq)).isZero();
		assertThat(count("SELECT count(*) FROM sub_rfqs WHERE rfq_id = ?", rfq)).isZero();
		assertThat(rfqStatus(rfq)).isEqualTo("UNDER_REVIEW"); // nothing half-done: the RFQ did not move
		// a partial failure rolls the whole request back: the valid first sub-RFQ is not kept
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierX, List.of(p.get(0)), supplierY, List.of(987654321L)))
			.andExpect(status().isUnprocessableContent());
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_id = ?", rfq)).isZero();
	}

	@Test
	void theGeneratedQuotationRejectsBadTermsAndKeepsMarginExactEvenWhenNegative() throws Exception {
		long rfq = reviewedRfq("PN-1", "PN-2");
		List<Long> p = parts(rfq);
		distribute(rfq, supplierX, p);
		long q1 = quoteOf(sellerX, rfq, "PN-1");
		long q2 = quoteOf(sellerX, rfq, "PN-2");
		submitQuote(q1, sellerX, "33.3333");
		submitQuote(q2, sellerX, "90");
		accept(q1);
		accept(q2);
		String valid = line(q1, "100.0001");
		for (String bad : List.of(
				valid.replace("\"productionPiecePrice\": 100.0001", "\"productionPiecePrice\": -1"),
				valid.replace("\"productionPiecePrice\": 100.0001", "\"productionPiecePrice\": 1.00001"),
				valid.replace("\"productionPiecePrice\": 100.0001", "\"productionPiecePrice\": 123456789012345"),
				valid.replace("\"productionPiecePrice\": 100.0001", "\"moq\": 0, \"productionPiecePrice\": 100"),
				valid.replace("\"productionPiecePrice\": 100.0001", "\"ppapPiecePrice\": -1, \"productionPiecePrice\": 100"),
				valid.replace("\"productionPiecePrice\": 100.0001", "\"toolingCost\": -1, \"productionPiecePrice\": 100"),
				valid.replace("\"currencyId\": " + inr + ",", ""),
				valid.replace("\"validityDate\": \"%s\"".formatted(LocalDate.now().plusDays(30)), "\"validityDate\": null"),
				"{\"quoteId\": %d}".formatted(q1), "{}")) {
			call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(bad)).andExpect(status().isBadRequest());
		}
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, "{\"lines\": []}").andExpect(status().isBadRequest());
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(valid).replace("Incl. inspection", "r".repeat(4001)))
			.andExpect(status().isBadRequest());
		for (String unknown : List.of(valid.replace("\"currencyId\": " + inr, "\"currencyId\": 987654321"),
				valid.replace("\"ergoQuote\": {", "\"ergoQuote\": {\"paymentTermId\": 987654321, "),
				valid.replace("\"ergoQuote\": {", "\"ergoQuote\": {\"incotermId\": 987654321, "))) {
			call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(unknown))
				.andExpect(status().isUnprocessableContent());
		}
		// a quote id that is not on this RFQ (or does not exist) is a 404, never an error and never another RFQ's data
		long otherRfq = reviewedRfq("PN-O");
		distribute(otherRfq, supplierX, parts(otherRfq));
		long foreign = quoteOf(sellerX, otherRfq, "PN-O");
		driveTo(foreign, sellerX, "ACCEPTED");
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(line(foreign, "100"))).andExpect(status().isNotFound());
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(line(987654321L, "100"))).andExpect(status().isNotFound());
		assertThat(count("SELECT count(*) FROM customer_quote_submissions WHERE rfq_id IN (?, ?)", rfq, otherRfq)).isZero();
		assertThat(statusOf(foreign)).isEqualTo("ACCEPTED");

		// a priced-below-cost line stores a negative margin exactly; a failure here would be a 500
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(valid, line(q2, "80"))).andExpect(status().isOk());
		Map<String, Object> first = jdbc.queryForMap("SELECT margin_amount_hidden, margin_percentage_hidden FROM "
				+ "customer_quote_lines WHERE assignment_id = ?", q1);
		Map<String, Object> second = jdbc.queryForMap("SELECT margin_amount_hidden, margin_percentage_hidden FROM "
				+ "customer_quote_lines WHERE assignment_id = ?", q2);
		assertThat((BigDecimal) first.get("margin_amount_hidden")).isEqualByComparingTo("66.6668");
		assertThat((BigDecimal) first.get("margin_percentage_hidden")).isEqualByComparingTo("66.67");
		assertThat((BigDecimal) second.get("margin_amount_hidden")).isEqualByComparingTo("-10");
		assertThat((BigDecimal) second.get("margin_percentage_hidden")).isEqualByComparingTo("-12.5");
	}

	@Test
	void aQuotationOfZeroPricedPartsIsNotAValidOfferToACustomer() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-0");
		driveTo(quote, sellerX, "ACCEPTED");
		long rfq = rfqOf(quote);
		assertThat(send(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(line(quote, "0"))))
			.as("ERGO quoting a part to the customer at a 0 piece price").isBetween(400, 499);
		assertThat(count("SELECT count(*) FROM customer_quote_submissions WHERE rfq_id = ?", rfq)).isZero();
	}

	// ---------------------------------------------------------------- audit and immutability

	@Test
	void everyStatusChangeOfAPartIsAuditedWithWhoRoleCompanyAndReason() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-AU");
		long rfq = rfqOf(quote);
		saveQuote(quote, sellerX, "90");
		call(post("/api/quotes/{id}/submit", quote), sellerX, null).andExpect(status().isOk());
		call(post("/api/quotes/{id}/revision", quote), ergoRfq, "{\"comment\": \"Check the tooling\"}").andExpect(status().isOk());
		call(put("/api/quotes/{id}", quote), sellerX, TestRfq.supplierQuote(inr, "85")).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", quote), sellerX, null).andExpect(status().isOk());
		accept(quote);
		long quotation = generate(rfq, quote);
		call(post("/api/customer-quotes/{id}/respond", quotation), buyer, answer("APPROVED", "Looks good")).andExpect(status().isOk());

		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT old_value, new_value, action_reason, changed_by_user_id, changed_by_role_code, changed_by_company_id,
				       source_channel, ip_address, changed_at, module_name
				FROM audit_logs WHERE entity_name = 'Invitation' AND record_id = ? AND field_name = 'status'
				ORDER BY audit_log_id""", quote);
		assertThat(rows.stream().map(r -> r.get("old_value") + ">" + r.get("new_value")).toList()).containsExactly(
				"NOT_STARTED>DRAFT_SAVED", "DRAFT_SAVED>SUBMITTED", "SUBMITTED>REVISION_REQUIRED",
				"REVISION_REQUIRED>RESUBMITTED", "RESUBMITTED>ACCEPTED", "ACCEPTED>WITH_CUSTOMER",
				"WITH_CUSTOMER>CUSTOMER_APPROVED");
		assertThat(rows).allSatisfy(r -> {
			assertThat(r.get("changed_by_user_id")).isNotNull();
			assertThat(r.get("changed_by_role_code")).isNotNull();
			assertThat(r.get("changed_by_company_id")).isNotNull();
			assertThat(r.get("changed_at")).isNotNull();
			assertThat(r.get("source_channel")).isEqualTo("API");
			assertThat(r.get("ip_address")).isNotNull();
			assertThat(r.get("module_name")).isEqualTo("quotation");
		});
		assertThat(rows.get(1).get("changed_by_role_code")).isEqualTo("SUPPLIER_RFQ");
		assertThat(((Number) rows.get(1).get("changed_by_company_id")).longValue()).isEqualTo(supplierX);
		assertThat(rows.get(2).get("changed_by_role_code")).isEqualTo("ERGO_RFQ");
		assertThat(((Number) rows.get(2).get("changed_by_company_id")).longValue()).isEqualTo(ergoCo);
		assertThat(rows.get(2).get("action_reason")).isEqualTo("Check the tooling");
		assertThat(rows.get(6).get("changed_by_role_code")).isEqualTo("CUSTOMER_RFQ");
		assertThat(((Number) rows.get(6).get("changed_by_company_id")).longValue()).isEqualTo(customer);
		assertThat(rows.get(6).get("action_reason")).isEqualTo("Looks good");

		List<Map<String, Object>> answered = jdbc.queryForList("""
				SELECT old_value, new_value, action_reason, changed_by_role_code FROM audit_logs
				WHERE entity_name = 'CustomerQuote' AND record_id = ? AND field_name = 'responseStatus'""", quotation);
		assertThat(answered).hasSize(1);
		assertThat(answered.get(0)).containsEntry("old_value", "WAITING").containsEntry("new_value", "APPROVED")
			.containsEntry("action_reason", "Looks good").containsEntry("changed_by_role_code", "CUSTOMER_RFQ");
		assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_name = 'CustomerQuote' AND record_id = ? "
				+ "AND event_type = 'CREATE' AND changed_by_role_code = 'ERGO_RFQ'", quotation)).isEqualTo(1);
		// the sending of the web client is recorded as UI
		long other = singlePartQuote(sellerX, supplierX, "PN-AU2");
		mvc.perform(put("/api/quotes/{id}", other).header(HttpHeaders.AUTHORIZATION, sellerX.bearer())
			.header("X-Ergo-Client", "web").contentType("application/json").content(TestRfq.supplierQuote(inr, "90")))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT source_channel FROM audit_logs WHERE entity_name = 'Invitation' AND "
				+ "record_id = ? AND field_name = 'status'", String.class, other)).isEqualTo("UI");
	}

	@Test
	void aRecallIsAuditedWithItsReasonAndARefusedActionLeavesNoTrace() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-RC");
		driveTo(quote, sellerX, "SUBMITTED");
		int before = count("SELECT count(*) FROM audit_logs WHERE entity_name IN ('Invitation', 'SupplierQuote') "
				+ "AND record_id = ?", quote);
		call(post("/api/quotes/{id}/accept", quote), sellerX, null).andExpect(status().isForbidden());
		call(post("/api/quotes/{id}/recall", quote), ergoRfq, "{}").andExpect(status().isUnprocessableContent());
		call(put("/api/quotes/{id}", quote), sellerX, TestRfq.supplierQuote(inr, "1")).andExpect(status().isConflict());
		assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_name IN ('Invitation', 'SupplierQuote') "
				+ "AND record_id = ?", quote)).isEqualTo(before);
		call(post("/api/quotes/{id}/recall", quote), ergoRfq, "{\"comment\": \"Wrong commodity\"}").andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT action_reason FROM audit_logs WHERE entity_name = 'Invitation' AND "
				+ "record_id = ? AND new_value = 'RECALLED'", String.class, quote)).isEqualTo("Wrong commodity");
		assertThat(jdbc.queryForObject("SELECT recall_reason FROM rfq_supplier_assignments WHERE assignment_id = ?",
				String.class, quote)).isEqualTo("Wrong commodity");
	}

	@Test
	void versionedAndFrozenRecordsRefuseUpdatesDeletesAndTruncatesAtTheDatabase() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-DB");
		long rfq = rfqOf(quote);
		driveTo(quote, sellerX, "ACCEPTED");
		long quotation = generate(rfq, quote);
		long versionId = jdbc.queryForObject("SELECT v.supplier_quote_version_id FROM supplier_quote_versions v JOIN "
				+ "supplier_quotes s ON s.supplier_quote_id = v.supplier_quote_id WHERE s.assignment_id = ?", Long.class, quote);
		long auditId = jdbc.queryForObject("SELECT max(audit_log_id) FROM audit_logs WHERE record_id = ?", Long.class, quote);

		blocked("UPDATE supplier_quote_versions SET snapshot = '{}'::jsonb WHERE supplier_quote_version_id = " + versionId, "append-only");
		blocked("UPDATE supplier_quote_versions SET submitted_by_user_id = submitted_by_user_id", "append-only");
		blocked("DELETE FROM supplier_quote_versions WHERE supplier_quote_version_id = " + versionId, "append-only");
		blocked("TRUNCATE supplier_quote_versions", "append-only");
		blocked("INSERT INTO supplier_quote_versions (supplier_quote_id, version_no, snapshot, submitted_by_user_id) "
				+ "SELECT supplier_quote_id, 1, '{}', submitted_by_user_id FROM supplier_quote_versions "
				+ "WHERE supplier_quote_version_id = " + versionId, "unique"); // a version number is never reused
		blocked("UPDATE customer_quote_lines SET margin_amount_hidden = 0 WHERE assignment_id = " + quote, "append-only");
		blocked("DELETE FROM customer_quote_lines WHERE assignment_id = " + quote, "append-only");
		blocked("TRUNCATE customer_quote_lines", "append-only");
		blocked("UPDATE customer_quote_submissions SET customer_view = '{}'::jsonb WHERE customer_quote_submission_id = "
				+ quotation, "cannot be changed");
		blocked("DELETE FROM customer_quote_submissions WHERE customer_quote_submission_id = " + quotation, "deleted");
		blocked("UPDATE audit_logs SET new_value = 'x' WHERE audit_log_id = " + auditId, "append-only");
		blocked("DELETE FROM audit_logs WHERE audit_log_id = " + auditId, "append-only");
		blocked("TRUNCATE audit_logs", "append-only");

		// once answered, even the response is final; while on hold only one final answer is possible
		respond(quotation, "APPROVED");
		blocked("UPDATE customer_quote_submissions SET response_status = 'DECLINED' WHERE customer_quote_submission_id = "
				+ quotation, "cannot be changed");
		blocked("UPDATE customer_quote_submissions SET response_comment = 'edited' WHERE customer_quote_submission_id = "
				+ quotation, "cannot be changed");
		// money rows: a priced quote without a currency cannot exist
		blocked("UPDATE supplier_quotes SET currency_id = NULL WHERE assignment_id = " + quote, "check");
		blocked("UPDATE customer_quote_lines SET currency_id = NULL", "append-only");
		blocked("UPDATE supplier_quotes SET moq = 0 WHERE assignment_id = " + quote, "check");
		blocked("UPDATE supplier_quotes SET production_piece_price = -1 WHERE assignment_id = " + quote, "check");
		blocked("UPDATE rfq_supplier_assignments SET status = 'SENT_TO_NOWHERE' WHERE assignment_id = " + quote, "check");
	}

	@Test
	void resubmissionAddsAnImmutableVersionAndLeavesTheEarlierOneAndTheCustomersQuotationUntouched() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-VR");
		long rfq = rfqOf(quote);
		driveTo(quote, sellerX, "ACCEPTED");
		long first = generate(rfq, quote);
		String customerSeen = text(get("/api/rfqs/{id}/customer-quotes", rfq), buyer, null);
		String v1 = jdbc.queryForObject("SELECT v.snapshot::text FROM supplier_quote_versions v JOIN supplier_quotes s ON "
				+ "s.supplier_quote_id = v.supplier_quote_id WHERE s.assignment_id = ? AND v.version_no = 1", String.class, quote);
		respond(first, "REVISION_REQUESTED");
		call(post("/api/quotes/{id}/revision", quote), ergoRfq, "{\"comment\": \"customer wants less\"}").andExpect(status().isOk());
		call(put("/api/quotes/{id}", quote), sellerX, TestRfq.supplierQuote(inr, "70")).andExpect(status().isOk());
		// the draft is private: neither the version table nor ERGO's view has changed yet
		assertThat(count("SELECT count(*) FROM supplier_quote_versions v JOIN supplier_quotes s ON s.supplier_quote_id = "
				+ "v.supplier_quote_id WHERE s.assignment_id = ?", quote)).isEqualTo(1);
		call(post("/api/quotes/{id}/submit", quote), sellerX, null).andExpect(jsonPath("$.version").value(2));
		accept(quote);
		long second = generate(rfq, quote);
		assertThat(second).isNotEqualTo(first);
		assertThat(jdbc.queryForObject("SELECT v.snapshot::text FROM supplier_quote_versions v JOIN supplier_quotes s ON "
				+ "s.supplier_quote_id = v.supplier_quote_id WHERE s.assignment_id = ? AND v.version_no = 1", String.class, quote))
			.isEqualTo(v1);
		assertThat(count("SELECT count(*) FROM supplier_quote_versions v JOIN supplier_quotes s ON s.supplier_quote_id = "
				+ "v.supplier_quote_id WHERE s.assignment_id = ?", quote)).isEqualTo(2);
		// the first quotation, as the customer received it, is byte for byte what it was
		String firstNow = JsonPath.read(text(get("/api/rfqs/{id}/customer-quotes", rfq), buyer, null),
				"$.quotations[1].content").toString();
		String firstBefore = JsonPath.read(customerSeen, "$.quotations[0].content").toString();
		assertThat(firstNow).isEqualTo(firstBefore);
		assertThat(count("SELECT supplier_quote_version FROM customer_quote_lines WHERE submission_id = ?", first)).isEqualTo(1);
		assertThat(count("SELECT supplier_quote_version FROM customer_quote_lines WHERE submission_id = ?", second)).isEqualTo(2);
	}

	// ---------------------------------------------------------------- error shape

	@Test
	void everyErrorIsAProblemDetailWithoutStackTracesOrInternals() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-E");
		long rfq = rfqOf(quote);
		driveTo(quote, sellerX, "ACCEPTED");
		long quotation = generate(rfq, quote);
		String good = TestRfq.supplierQuote(inr, "90");
		Object[][] cases = {
				{get("/api/quotes/{id}", 987654321L), sellerX, null, 404},
				{get("/api/quotes/{id}", quote), sellerY, null, 404},
				{put("/api/quotes/{id}", quote), sellerX, good, 409},
				{put("/api/quotes/{id}", quote), sellerX, "{broken", 400},
				{put("/api/quotes/{id}", quote), sellerX, good.replace("\"moq\": 500", "\"moq\": 0"), 400},
				{post("/api/quotes/{id}/accept", quote), ergoRfq, null, 409},
				{post("/api/quotes/{id}/revision", quote), ergoRfq, "{\"comment\": \"x\"}", 409},
				{post("/api/quotes/{id}/recall", quote), ergoRfq, "{\"comment\": \"x\"}", 409},
				{post("/api/rfqs/{id}/quotes", rfq), ergoRfq, "{}", 400},
				{post("/api/rfqs/{id}/quotes", 987654321L), ergoRfq, TestRfq.distribution(supplierX, List.of(1L)), 404},
				{post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(line(quote, "100")), 409},
				{post("/api/customer-quotes/{id}/respond", quotation), buyer, answer("MAYBE", "x"), 400},
				{post("/api/customer-quotes/{id}/respond", quotation), buyer, "{\"response\": \"APPROVED\"}", 422},
				{post("/api/customer-quotes/{id}/respond", 987654321L), buyer, answer("APPROVED", "x"), 404},
				{get("/api/quotes/{id}", "not-a-number"), sellerX, null, 400},
				{get("/api/rfqs/{id}", "not-a-number"), ergoRfq, null, 400},
		};
		int n = 0;
		for (Object[] c : cases) {
			n++;
			var result = call((org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) c[0], (Login) c[1],
					(String) c[2]).andReturn().getResponse();
			String body = result.getContentAsString();
			assertThat(result.getStatus()).as(c[0] + " " + body).isEqualTo(c[3]);
			assertThat(result.getContentType()).as("case " + n + " (status " + result.getStatus() + ") content type of [" + body + "]").startsWith("application/problem+json");
			assertThat(JsonPath.<Integer>read(body, "$.status")).isEqualTo(c[3]);
			assertThat(JsonPath.<String>read(body, "$.title")).isNotBlank();
			assertThat(body).doesNotContain("Exception", "stackTrace", "\"trace\"", "at com.ergo", "org.springframework",
					"org.hibernate", "jakarta.", "java.lang", "SQL", "ERROR:", "rfq_supplier_assignments", "supplier_quotes",
					"constraint", "psql", "postgres");
		}
		// no token and a wrong content type / method are still tidy
		mvc.perform(get("/api/quotes/{id}", quote)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/quotes/{id}/accept", quote).header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer())
			.contentType("text/plain").content("x")).andExpect(status().isConflict()); // no body is read there
		mvc.perform(put("/api/quotes/{id}", quote).header(HttpHeaders.AUTHORIZATION, sellerX.bearer())
			.contentType("text/plain").content("x")).andExpect(status().isUnsupportedMediaType())
			.andExpect(content().string(not(containsString("Exception"))));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/quotes/{id}", quote)
			.header(HttpHeaders.AUTHORIZATION, sellerX.bearer())).andExpect(status().is4xxClientError());
	}

	@Test
	void aRefusalByRoleIsAProblemDocumentToo() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-E3");
		for (var request : List.of(get("/api/quotes/{id}", quote), post("/api/quotes/{id}/accept", quote))) {
			var refused = call(request, buyer, null).andReturn().getResponse();
			assertThat(refused.getStatus()).isEqualTo(403);
			assertThat(refused.getContentType()).as("content type of a 403 for " + request.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI()).isNotNull().startsWith("application/problem+json");
		}
	}

	// ---------------------------------------------------------------- helpers

	@org.springframework.beans.factory.annotation.Autowired
	javax.sql.DataSource dataSource;

	/** Runs the statement in a transaction that is always rolled back; it must fail with a message containing {@code why}. */
	private void blocked(String sql, String why) throws Exception {
		try (var connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (var statement = connection.createStatement()) {
				statement.execute(sql);
				connection.rollback();
				throw new AssertionError("Expected the database to refuse: " + sql);
			}
			catch (java.sql.SQLException e) {
				connection.rollback();
				assertThat(e.getMessage().toLowerCase()).as(sql).contains(why.toLowerCase());
			}
		}
	}

}
