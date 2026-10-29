package com.ergo.connect.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ergo.connect.AdversarialSupport;
import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Adversarial coverage of the RFQ header and its parts (flow doc §1-2): who edits what and when, part identity, input
 * boundaries, files, roles, the RFQ module switch and the audit trail. Distribution, quotes and the customer quotation
 * are attacked in the quotation package.
 */
@IntegrationTest
class RfqAdversarialTests extends AdversarialSupport {

	private static String item(String number) {
		return "{\"partNumber\": \"%s\", \"description\": \"Bracket\", \"materialSpec\": \"EN8\", \"processSteps\": [{\"stepName\": \"Casting\"}]}"
			.formatted(number);
	}

	private String form(String... items) {
		return "{\"targetQuoteDate\": \"%s\", \"currencyId\": %d, \"items\": [%s]}".formatted(LocalDate.now().plusDays(10), inr,
				String.join(", ", items));
	}

	private long draft(String... parts) throws Exception {
		return TestRfq.create(mvc, buyer, TestRfq.form(inr, parts));
	}

	// ---------------------------------------------------------------- parts: identity and edits

	@Test
	void aPartIdBelongingToAnotherRfqCannotBeEditedThroughThisOne() throws Exception {
		long mine = draft("PN-1");
		long theirs = TestRfq.create(mvc, buyerB, TestRfq.form(inr, "PN-SECRET"));
		long theirPart = TestRfq.itemIds(mvc, ergoRfq, theirs).get(0);
		long myPart = TestRfq.itemIds(mvc, ergoRfq, mine).get(0);
		String hijack = form(item("PN-1").replace("{\"partNumber\"", "{\"id\": %d, \"partNumber\"".formatted(myPart)),
				item("PN-HIJACK").replace("{\"partNumber\"", "{\"id\": %d, \"partNumber\"".formatted(theirPart)));
		call(put("/api/rfqs/{id}", mine), buyer, hijack).andExpect(status().isUnprocessableContent());
		call(put("/api/rfqs/{id}", mine), ergoRfq, hijack).andExpect(status().isUnprocessableContent());
		assertThat(jdbc.queryForObject("SELECT part_number FROM rfq_items WHERE rfq_item_id = ?", String.class, theirPart))
			.isEqualTo("PN-SECRET");
		assertThat(jdbc.queryForObject("SELECT rfq_id FROM rfq_items WHERE rfq_item_id = ?", Long.class, theirPart)).isEqualTo(theirs);
		call(get("/api/rfqs/{id}", theirs), buyerB, null).andExpect(jsonPath("$.items[0].partNumber").value("PN-SECRET"));
		// a refused edit is all-or-nothing: the valid first part was not changed either
		call(get("/api/rfqs/{id}", mine), buyer, null).andExpect(jsonPath("$.items[0].partNumber").value("PN-1"));
	}

	@Test
	void partsKeepTheirIdsWhenReorderedAndLineNumbersFollowTheForm() throws Exception {
		long rfq = draft("PN-1", "PN-2", "PN-3");
		List<Long> ids = TestRfq.itemIds(mvc, ergoRfq, rfq);
		String reordered = form(
				item("PN-3").replace("{\"partNumber\"", "{\"id\": %d, \"partNumber\"".formatted(ids.get(2))),
				item("PN-1").replace("{\"partNumber\"", "{\"id\": %d, \"partNumber\"".formatted(ids.get(0))),
				item("PN-2").replace("{\"partNumber\"", "{\"id\": %d, \"partNumber\"".formatted(ids.get(1))));
		call(put("/api/rfqs/{id}", rfq), buyer, reordered).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", contains(ids.get(2).intValue(), ids.get(0).intValue(), ids.get(1).intValue())))
			.andExpect(jsonPath("$.items[*].lineNo", contains(1, 2, 3)))
			.andExpect(jsonPath("$.items[*].partNumber", contains("PN-3", "PN-1", "PN-2")));
		// the same part number twice is refused whatever the case, a different revision is a different part
		call(put("/api/rfqs/{id}", rfq), buyer, form(item("PN-1"), item("pn-1"))).andExpect(status().isUnprocessableContent());
		call(put("/api/rfqs/{id}", rfq), buyer, form(item("PN-1"), item("PN-1").replace("\"description\"", "\"revision\": \"B\", \"description\"")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)));
		call(put("/api/rfqs/{id}", rfq), buyer, form(item("PN-1"), item(" PN-1 "))).andExpect(status().isUnprocessableContent());
	}

	@Test
	void partsCannotBeRemovedOnceTheRfqIsOutAndNothingChangesWhenTheEditIsRefused() throws Exception {
		long rfq = reviewedRfq("PN-1", "PN-2");
		List<Long> p = parts(rfq);
		distribute(rfq, supplierX, List.of(p.get(0)));
		String shrink = form(item("PN-1").replace("{\"partNumber\"", "{\"id\": %d, \"partNumber\"".formatted(p.get(0))));
		for (Login user : List.of(ergoRfq, ergoMgmt, buyer)) {
			call(put("/api/rfqs/{id}", rfq), user, shrink).andExpect(status().isConflict());
		}
		assertThat(count("SELECT count(*) FROM rfq_items WHERE rfq_id = ?", rfq)).isEqualTo(2);
		call(put("/api/rfqs/{id}/internal-notes", rfq), ergoRfq, "{\"internalNotes\": \"still allowed\"}").andExpect(status().isOk());
		assertThat(count("SELECT count(*) FROM rfq_items WHERE rfq_id = ?", rfq)).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_id = ?", rfq)).isEqualTo(1);
	}

	@Test
	void aCustomerCannotCreateAnRfqForAnotherCompanyOrSetInternalFields() throws Exception {
		String body = form(item("PN-1")).replace("{\"targetQuoteDate\"", "{\"customerCompanyId\": %d, \"internalNotes\": \"sneaky\", \"targetQuoteDate\"".formatted(customerB));
		long id = JsonPath.<Number>read(call(post("/api/rfqs"), buyer, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id").longValue();
		assertThat(jdbc.queryForObject("SELECT customer_company_id FROM rfqs WHERE rfq_id = ?", Long.class, id)).isEqualTo(customer);
		assertThat(jdbc.queryForObject("SELECT internal_notes FROM rfqs WHERE rfq_id = ?", String.class, id)).isNull();
		call(get("/api/rfqs/{id}", id), buyerB, null).andExpect(status().isNotFound());
		// an edit by the customer cannot move the RFQ to another company or set notes either
		call(put("/api/rfqs/{id}", id), buyer, body).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT customer_company_id FROM rfqs WHERE rfq_id = ?", Long.class, id)).isEqualTo(customer);
		assertThat(jdbc.queryForObject("SELECT internal_notes FROM rfqs WHERE rfq_id = ?", String.class, id)).isNull();
		// ERGO may not create for a company that is not a customer, an unknown or an inactive one
		String ergoBody = form(item("PN-1"));
		for (long bad : List.of(supplierX, ergoCo, 987654321L)) {
			call(post("/api/rfqs"), ergoRfq, ergoBody.replace("{\"targetQuoteDate\"", "{\"customerCompanyId\": %d, \"targetQuoteDate\"".formatted(bad)))
				.andExpect(status().isUnprocessableContent());
		}
		call(post("/api/rfqs"), ergoRfq, ergoBody).andExpect(status().isUnprocessableContent()); // "choose the customer"
		jdbc.update("UPDATE companies SET status = 'INACTIVE' WHERE company_id = ?", customerB);
		call(post("/api/rfqs"), ergoRfq, ergoBody.replace("{\"targetQuoteDate\"", "{\"customerCompanyId\": %d, \"targetQuoteDate\"".formatted(customerB)))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void rfqInputBoundariesAreEnforcedAndNothingIsSavedWhenTheyFail() throws Exception {
		String ok = item("PN-1");
		int before = count("SELECT count(*) FROM rfqs WHERE created_by_user_id = ?", buyer.userId());
		List<String> badItems = new ArrayList<>(List.of(
				ok.replace("PN-1", ""), ok.replace("PN-1", "   "), ok.replace("PN-1", "P".repeat(101)),
				ok.replace("\"description\"", "\"revision\": \"%s\", \"description\"".formatted("R".repeat(31))),
				ok.replace("Bracket", "d".repeat(1001)), ok.replace("\"description\"", "\"eau\": 0, \"description\""),
				ok.replace("\"description\"", "\"eau\": -5, \"description\""), ok.replace("\"description\"", "\"moq\": 0, \"description\""),
				ok.replace("\"description\"", "\"targetProductionPiecePrice\": -1, \"description\""),
				ok.replace("\"description\"", "\"targetProductionPiecePrice\": 1.00001, \"description\""),
				ok.replace("\"description\"", "\"targetToolingCost\": 123456789012345, \"description\""),
				ok.replace("\"description\"", "\"netWeightKg\": 123456789.1, \"description\""),
				ok.replace("\"description\"", "\"materialLeadTimeWeeks\": -1, \"description\""),
				ok.replace("\"EN8\"", "\"" + "m".repeat(501) + "\""),
				ok.replace("\"Casting\"", "\"\""), ok.replace("\"Casting\"", "\"%s\"".formatted("s".repeat(201))),
				ok.replace("{\"stepName\": \"Casting\"}", "{\"stepName\": \"A\", \"sourcingType\": \"MAGIC\"}"),
				ok.replace("{\"stepName\": \"Casting\"}", "{\"stepName\": \"A\", \"cycleTimeSeconds\": -3}")));
		for (String bad : badItems) {
			assertThat(send(post("/api/rfqs"), buyer, form(bad))).as(bad).isEqualTo(400);
		}
		assertThat(send(post("/api/rfqs"), buyer, "{\"currencyId\": %d}".formatted(inr))).isEqualTo(400);
		assertThat(send(post("/api/rfqs"), buyer, "{\"items\": [%s]}".formatted(ok))).as("no currency").isEqualTo(400);
		assertThat(send(post("/api/rfqs"), buyer, "{\"currencyId\": %d, \"items\": [null]}".formatted(inr))).isEqualTo(400);
		assertThat(send(post("/api/rfqs"), buyer, "{\"currencyId\": %d, \"items\": [%s], \"customerRemarks\": \"%s\"}"
			.formatted(inr, ok, "c".repeat(4001)))).isEqualTo(400);
		assertThat(send(post("/api/rfqs"), buyer, "{\"currencyId\": %d, \"priority\": \"URGENT!!\", \"items\": [%s]}".formatted(inr, ok))).isEqualTo(400);
		assertThat(send(post("/api/rfqs"), buyer, "{\"currencyId\": %d, \"items\": [%s]}".formatted(inr, String.join(",", java.util.Collections.nCopies(101, ok)))))
			.as("101 parts").isEqualTo(400);
		assertThat(send(post("/api/rfqs"), buyer, "{broken")).isEqualTo(400);
		assertThat(send(post("/api/rfqs"), buyer, "{\"currencyId\": 987654321, \"items\": [%s]}".formatted(ok))).as("unknown currency").isEqualTo(422);
		assertThat(send(post("/api/rfqs"), buyer, "{\"currencyId\": %d, \"paymentTermId\": 987654321, \"items\": [%s]}".formatted(inr, ok)))
			.as("unknown payment term").isEqualTo(422);
		assertThat(send(post("/api/rfqs"), buyer, form(ok.replace("\"description\"", "\"materialId\": 987654321, \"description\""))))
			.as("unknown material").isEqualTo(422);
		assertThat(send(post("/api/rfqs"), buyer, form(ok.replace("\"description\"", "\"coatingId\": 987654321, \"description\""))))
			.as("unknown coating").isEqualTo(422);
		assertThat(send(post("/api/rfqs"), buyer, form(ok.replace("{\"stepName\": \"Casting\"}", "{\"stepName\": \"A\", \"processId\": 987654321}"))))
			.as("unknown process").isEqualTo(422);
		assertThat(count("SELECT count(*) FROM rfqs WHERE created_by_user_id = ?", buyer.userId())).isEqualTo(before);
		// the largest values that are legal are kept exactly
		String max = ok.replace("PN-1", "P".repeat(100)).replace("\"description\"",
				"\"revision\": \"%s\", \"targetToolingCost\": 12345678901234.5678, \"eau\": 2147483647, \"description\"".formatted("R".repeat(30)));
		long id = JsonPath.<Number>read(call(post("/api/rfqs"), buyer, form(max)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id").longValue();
		assertThat(jdbc.queryForObject("SELECT target_tooling_cost FROM rfq_items WHERE rfq_id = ?", java.math.BigDecimal.class, id))
			.isEqualByComparingTo("12345678901234.5678");
	}

	// ---------------------------------------------------------------- workflow

	@Test
	void rfqTransitionsAreRefusedOutOfOrderAndEveryReasonIsMandatory() throws Exception {
		long rfq = draft("PN-1");
		// a draft cannot be reviewed, clarified, closed or cancelled by ERGO into a corner
		call(post("/api/rfqs/{id}/start-review", rfq), ergoRfq, null).andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/request-clarification", rfq), ergoRfq, "{\"comment\": \"why\"}").andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/close", rfq), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierX, TestRfq.itemIds(mvc, ergoRfq, rfq)))
			.andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/submit", rfq), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/submit", rfq), buyer, "{}").andExpect(status().isConflict()); // twice
		// start-review needs an approving role; an acting-only role (KAM) and the customer are refused
		Login kam = users.login(ergoCo, "ERGO_KAM");
		users.assignAccounts(kam.userId(), customer);
		call(post("/api/rfqs/{id}/start-review", rfq), kam, null).andExpect(status().isForbidden());
		call(post("/api/rfqs/{id}/start-review", rfq), buyer, null).andExpect(status().isForbidden());
		call(post("/api/rfqs/{id}/start-review", rfq), ergoRfq, null).andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", rfq), ergoRfq, null).andExpect(status().isConflict()); // twice
		// clarification: a real question; the customer cannot ask; a blank one is refused
		for (String body : List.of("{}", "{\"comment\": null}", "{\"comment\": \"  \"}")) {
			call(post("/api/rfqs/{id}/request-clarification", rfq), ergoRfq, body).andExpect(status().isUnprocessableContent());
		}
		call(post("/api/rfqs/{id}/request-clarification", rfq), buyer, "{\"comment\": \"hm\"}").andExpect(status().isForbidden());
		call(post("/api/rfqs/{id}/request-clarification", rfq), ergoRfq, "{\"comment\": \"%s\"}".formatted("q".repeat(2001))).andExpect(status().isBadRequest());
		call(post("/api/rfqs/{id}/request-clarification", rfq), ergoRfq, "{\"comment\": \"Which finish?\"}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/request-clarification", rfq), ergoRfq, "{\"comment\": \"again\"}").andExpect(status().isConflict());
		for (String body : List.of("{}", "{\"comment\": \"   \"}")) {
			call(post("/api/rfqs/{id}/submit", rfq), buyer, body).andExpect(status().isUnprocessableContent());
		}
		call(post("/api/rfqs/{id}/submit", rfq), buyer, "{\"comment\": \"Ra 1.6\"}").andExpect(status().isOk());
		// cancel and close: blank reasons refused, repeated or late ones illegal
		for (String body : List.of("{}", "{\"comment\": \" \"}")) {
			call(post("/api/rfqs/{id}/cancel", rfq), ergoRfq, body).andExpect(status().isUnprocessableContent());
		}
		call(post("/api/rfqs/{id}/cancel", rfq), buyerB, "{\"comment\": \"hijack\"}").andExpect(status().isNotFound());
		call(post("/api/rfqs/{id}/cancel", rfq), ergoRfq, "{\"comment\": \"Duplicate\"}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/cancel", rfq), ergoRfq, "{\"comment\": \"Duplicate\"}").andExpect(status().isConflict());
		for (String action : List.of("submit", "request-clarification", "close")) {
			call(post("/api/rfqs/{id}/" + action, rfq), ergoRfq, "{\"comment\": \"x\"}").andExpect(status().isConflict());
		}
		call(post("/api/rfqs/{id}/start-review", rfq), ergoRfq, null).andExpect(status().isConflict());
		call(put("/api/rfqs/{id}", rfq), ergoRfq, TestRfq.form(inr, "PN-1")).andExpect(status().isConflict());
		call(put("/api/rfqs/{id}/internal-notes", rfq), ergoRfq, "{\"internalNotes\": \"late\"}").andExpect(status().isConflict());
		upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "late.pdf").andExpect(status().isConflict());
		call(get("/api/rfqs/{id}", rfq), buyer, null).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.actions", hasSize(1)))
			.andExpect(jsonPath("$.actions", contains("CLONE")));
	}

	@Test
	void anRfqIncompleteAtSubmissionOrAtDistributionIsRefusedWithoutMovingIt() throws Exception {
		long id = TestRfq.create(mvc, buyer, "{\"currencyId\": %d, \"items\": [{\"partNumber\": \"PN-A\"}, {\"partNumber\": \"PN-B\", \"description\": \"x\"}]}".formatted(inr));
		call(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("target quote date")));
		assertThat(rfqStatus(id)).isEqualTo("DRAFT");
		// ERGO strips the process flow of a part while reviewing: distribution is refused and the RFQ stays under review
		long ok = reviewedRfq("PN-1", "PN-2");
		List<Long> p = parts(ok);
		String stripped = form(item("PN-1").replace("{\"partNumber\"", "{\"id\": %d, \"partNumber\"".formatted(p.get(0))),
				"{\"id\": %d, \"partNumber\": \"PN-2\", \"description\": \"Bracket\", \"materialSpec\": \"EN8\"}".formatted(p.get(1)));
		call(put("/api/rfqs/{id}", ok), ergoRfq, stripped).andExpect(status().isOk());
		call(post("/api/rfqs/{id}/quotes", ok), ergoRfq, TestRfq.distribution(supplierX, p)).andExpect(status().isUnprocessableContent());
		assertThat(rfqStatus(ok)).isEqualTo("UNDER_REVIEW");
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_id = ?", ok)).isZero();
	}

	// ---------------------------------------------------------------- roles and scope

	@Test
	void everyRfqEndpointRefusesTheRolesTheSheetDoesNotGrant() throws Exception {
		long rfq = reviewedRfq("PN-1");
		Login admin = users.login(ergoCo, "ERGO_ADMIN"); // RFQ: V
		Login ofp = users.login(ergoCo, "ERGO_OFP"); // RFQ: NV
		Login qa = users.login(ergoCo, "ERGO_QUALITY"); // RFQ: NV
		Login customerQa = users.login(customer, "CUSTOMER_QUALITY"); // RFQ: V
		Login customerOfp = users.login(customer, "CUSTOMER_OFP"); // RFQ: NV
		List<Login> readOnly = List.of(admin, customerQa);
		List<Login> noAccess = List.of(ofp, qa, customerOfp, sellerX, sellerY, sellerZ, users.login(supplierX, "SUPPLIER_NPD"));
		for (Login user : readOnly) {
			call(get("/api/rfqs/{id}", rfq), user, null).andExpect(status().isOk());
			call(post("/api/rfqs"), user, TestRfq.form(inr, "PN-X")).andExpect(status().isForbidden());
			call(put("/api/rfqs/{id}", rfq), user, TestRfq.form(inr, "PN-1")).andExpect(status().isForbidden());
			call(post("/api/rfqs/{id}/submit", rfq), user, "{}").andExpect(status().isForbidden());
			call(post("/api/rfqs/{id}/cancel", rfq), user, "{\"comment\": \"x\"}").andExpect(status().isForbidden());
			call(post("/api/rfqs/{id}/clone", rfq), user, null).andExpect(status().isForbidden());
			call(post("/api/rfqs/{id}/start-review", rfq), user, null).andExpect(status().isForbidden());
			call(post("/api/rfqs/{id}/close", rfq), user, "{\"comment\": \"x\"}").andExpect(status().isForbidden());
			call(put("/api/rfqs/{id}/internal-notes", rfq), user, "{\"internalNotes\": \"x\"}").andExpect(status().isForbidden());
			upload("/api/rfqs/{id}/documents", rfq, user, "a.pdf").andExpect(status().isForbidden());
		}
		call(get("/api/rfqs/{id}/history", rfq), customerQa, null).andExpect(status().isForbidden());
		for (Login user : noAccess) {
			call(get("/api/rfqs"), user, null).andExpect(status().isForbidden());
			call(get("/api/rfqs/{id}", rfq), user, null).andExpect(status().isForbidden());
			call(get("/api/rfqs/{id}/history", rfq), user, null).andExpect(status().isForbidden());
			call(get("/api/rfqs/{id}/documents", rfq), user, null).andExpect(status().isForbidden());
			call(post("/api/rfqs"), user, TestRfq.form(inr, "PN-X")).andExpect(status().isForbidden());
			call(post("/api/rfqs/{id}/start-review", rfq), user, null).andExpect(status().isForbidden());
		}
		assertThat(rfqStatus(rfq)).isEqualTo("UNDER_REVIEW");
		assertThat(jdbc.queryForObject("SELECT internal_notes FROM rfqs WHERE rfq_id = ?", String.class, rfq)).isNull();
	}

	@Test
	void aKamSeesAndActsOnItsOwnAccountsOnlyAndAnApprovedRfqOpensToNpd() throws Exception {
		Login kam = users.login(ergoCo, "ERGO_KAM");
		users.assignAccounts(kam.userId(), customer, customerB);
		Login lonelyKam = users.login(ergoCo, "ERGO_KAM");
		Login npd = users.login(ergoCo, "ERGO_NPD");
		long a = draft("PN-A");
		long b = TestRfq.create(mvc, buyerB, TestRfq.form(inr, "PN-B"));
		call(get("/api/rfqs"), kam, null).andExpect(jsonPath("$.items[*].id", hasItem((int) a))).andExpect(jsonPath("$.items[*].id", hasItem((int) b)));
		call(get("/api/rfqs"), lonelyKam, null).andExpect(jsonPath("$.items", hasSize(0)));
		call(put("/api/rfqs/{id}", a), lonelyKam, TestRfq.form(inr, "PN-A")).andExpect(status().isNotFound());
		call(post("/api/rfqs/{id}/submit", a), lonelyKam, "{}").andExpect(status().isNotFound());
		call(post("/api/rfqs/{id}/clone", a), lonelyKam, null).andExpect(status().isNotFound());
		call(put("/api/rfqs/{id}/internal-notes", a), lonelyKam, "{\"internalNotes\": \"x\"}").andExpect(status().isNotFound());
		upload("/api/rfqs/{id}/documents", a, lonelyKam, "x.pdf").andExpect(status().isNotFound());
		// the KAM creates only for its accounts, even when told otherwise
		call(post("/api/rfqs"), lonelyKam, form(item("PN-1")).replace("{\"targetQuoteDate\"", "{\"customerCompanyId\": %d, \"targetQuoteDate\"".formatted(customer)))
			.andExpect(status().isUnprocessableContent());
		// NPD: the RFQ appears when the whole RFQ is approved
		call(get("/api/rfqs"), npd, null).andExpect(jsonPath("$.items[*].id", not(hasItem((int) a))));
		call(post("/api/rfqs/{id}/submit", a), buyer, "{}");
		call(post("/api/rfqs/{id}/start-review", a), ergoRfq, null);
		TestRfq.approveWhole(mvc, buyer, ergoRfq, sellerX, supplierX, inr, a, "90");
		assertThat(rfqStatus(a)).isEqualTo("CUSTOMER_APPROVED");
		call(get("/api/rfqs"), npd, null).andExpect(jsonPath("$.items[*].id", hasItem((int) a))).andExpect(jsonPath("$.items[*].id", not(hasItem((int) b))));
		call(get("/api/rfqs/{id}", a), npd, null).andExpect(status().isOk()).andExpect(jsonPath("$.actions", hasSize(0)));
		call(put("/api/rfqs/{id}", a), npd, TestRfq.form(inr, "PN-A")).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- files

	@Test
	void rfqFilesRefuseBadTypesEmptyContentAndHostileNamesAndAreServedAsDownloadsOnly() throws Exception {
		long rfq = reviewedRfq("PN-1");
		for (String name : List.of("virus.exe", "script.js", "page.html", "a.pdf.exe", "noextension", "trailingdot.", "x.svg", "x.php", "x.sh", "archive.rar")) {
			upload("/api/rfqs/{id}/documents", rfq, ergoRfq, name).andExpect(status().isUnprocessableContent());
		}
		upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "empty.pdf", new byte[0]).andExpect(status().isUnprocessableContent());
		// a hostile name is stored as a plain file name
		String stored = JsonPath.read(upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "../../etc/passwd.pdf").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.fileName");
		assertThat(stored).doesNotContain("/", "\\", "..");
		long upper = JsonPath.<Number>read(upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "DRAWING.PDF").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id").longValue();
		mvc.perform(get("/api/rfqs/{id}/documents/{doc}/content", rfq, upper).header("Authorization", ergoRfq.bearer()))
			.andExpect(status().isOk()).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
			.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
		// html disguised by a pdf name is still only ever sent as a download
		long disguised = JsonPath.<Number>read(upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "page.pdf", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id").longValue();
		mvc.perform(get("/api/rfqs/{id}/documents/{doc}/content", rfq, disguised).header("Authorization", ergoRfq.bearer()))
			.andExpect(header().string("Content-Type", "application/pdf"))
			.andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
		// a file can be fetched only through the RFQ it belongs to
		long other = reviewedRfq("PN-O");
		call(get("/api/rfqs/{id}/documents/{doc}/content", other, upper), ergoRfq, null).andExpect(status().isNotFound());
		call(delete("/api/rfqs/{id}/documents/{doc}", other, upper), ergoRfq, null).andExpect(status().isNotFound());
		call(put("/api/rfqs/{id}/documents/{doc}/visibility", other, upper), ergoRfq, "{\"visibility\": \"CUSTOMER\"}").andExpect(status().isNotFound());
		// out with suppliers: files stay (suppliers may have quoted against them), and the customer cannot add any
		distribute(rfq, supplierX, parts(rfq));
		call(delete("/api/rfqs/{id}/documents/{doc}", rfq, upper), ergoRfq, null).andExpect(status().isConflict());
		upload("/api/rfqs/{id}/documents", rfq, buyer, "more.pdf").andExpect(status().isConflict());
		assertThat(count("SELECT count(*) FROM documents WHERE document_id = ?", upper)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- module switch and audit

	@Test
	void aReadOnlyRfqModuleRefusesEveryChangeAndAHiddenOneShowsNothing() throws Exception {
		long rfq = draft("PN-1");
		long submitted = reviewedRfq("PN-S");
		long doc = idOf(upload("/api/rfqs/{id}/documents", submitted, ergoRfq, "a.pdf").andReturn());
		cap("RFQ", "V");
		assertThat(send(post("/api/rfqs"), buyer, TestRfq.form(inr, "PN-X"))).isEqualTo(403);
		assertThat(send(put("/api/rfqs/{id}", rfq), buyer, TestRfq.form(inr, "PN-1"))).isEqualTo(403);
		assertThat(send(post("/api/rfqs/{id}/submit", rfq), buyer, "{}")).isEqualTo(403);
		assertThat(send(post("/api/rfqs/{id}/cancel", rfq), buyer, "{\"comment\": \"x\"}")).isEqualTo(403);
		assertThat(send(post("/api/rfqs/{id}/clone", rfq), buyer, null)).isEqualTo(403);
		assertThat(send(post("/api/rfqs/{id}/request-clarification", submitted), ergoRfq, "{\"comment\": \"x\"}")).isEqualTo(403);
		assertThat(send(post("/api/rfqs/{id}/close", submitted), ergoMgmt, "{\"comment\": \"x\"}")).isEqualTo(403);
		assertThat(send(put("/api/rfqs/{id}/internal-notes", submitted), ergoRfq, "{\"internalNotes\": \"x\"}")).isEqualTo(403);
		assertThat(send(put("/api/rfqs/{id}/documents/{doc}/visibility", submitted, doc), ergoRfq, "{\"visibility\": \"CUSTOMER\"}")).isEqualTo(403);
		assertThat(send(delete("/api/rfqs/{id}/documents/{doc}", submitted, doc), ergoRfq, null)).isEqualTo(403);
		assertThat(upload("/api/rfqs/{id}/documents", submitted, ergoRfq, "b.pdf").andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(send(post("/api/rfqs/{id}/quotes", submitted), ergoRfq, TestRfq.distribution(supplierX, parts(submitted)))).isBetween(400, 409);
		assertThat(rfqStatus(rfq)).isEqualTo("DRAFT");
		assertThat(rfqStatus(submitted)).isEqualTo("UNDER_REVIEW");
		assertThat(count("SELECT count(*) FROM documents WHERE linked_module = 'rfq' AND linked_record_id = ?", submitted)).isEqualTo(1);
		call(get("/api/rfqs/{id}", rfq), buyer, null).andExpect(status().isOk());
		call(get("/api/rfqs"), ergoRfq, null).andExpect(status().isOk());

		cap("RFQ", "NV");
		call(get("/api/rfqs"), buyer, null).andExpect(status().isForbidden());
		call(get("/api/rfqs/{id}", rfq), buyer, null).andExpect(status().isForbidden());
		call(get("/api/rfqs/{id}/documents", submitted), ergoRfq, null).andExpect(status().isForbidden());
		assertThat(send(post("/api/rfqs/{id}/quotes", submitted), ergoRfq, TestRfq.distribution(supplierX, List.of(1L)))).isGreaterThanOrEqualTo(400);
		assertThat(com.jayway.jsonpath.JsonPath.<List<?>>read(text(get("/api/dashboard"), buyer, null), "$.rfqItems")).isEmpty();
		cap("RFQ", "O");
		call(get("/api/rfqs/{id}", rfq), buyer, null).andExpect(status().isOk());
	}

	@Test
	void everyRfqWriteIsAuditedWithWhoRoleCompanyAndTheOldAndNewValue() throws Exception {
		long rfq = draft("PN-1");
		call(put("/api/rfqs/{id}", rfq), buyer, form(item("PN-1")).replace("\"currencyId\"", "\"customerRemarks\": \"first\", \"currencyId\"")).andExpect(status().isOk());
		call(put("/api/rfqs/{id}", rfq), buyer, form(item("PN-1")).replace("\"currencyId\"", "\"customerRemarks\": \"second\", \"currencyId\"")).andExpect(status().isOk());
		call(post("/api/rfqs/{id}/submit", rfq), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", rfq), ergoRfq, null).andExpect(status().isOk());
		call(put("/api/rfqs/{id}/internal-notes", rfq), ergoRfq, "{\"internalNotes\": \"margin 12%\"}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/cancel", rfq), ergoRfq, "{\"comment\": \"Customer withdrew\"}").andExpect(status().isOk());

		List<java.util.Map<String, Object>> remarks = jdbc.queryForList("""
				SELECT old_value, new_value, changed_by_role_code, changed_by_company_id FROM audit_logs
				WHERE entity_name = 'Rfq' AND record_id = ? AND field_name = 'customerRemarks' ORDER BY audit_log_id""", rfq);
		assertThat(remarks).hasSize(2);
		assertThat(remarks.get(1)).containsEntry("old_value", "first").containsEntry("new_value", "second").containsEntry("changed_by_role_code", "CUSTOMER_RFQ");
		assertThat(((Number) remarks.get(1).get("changed_by_company_id")).longValue()).isEqualTo(customer);
		List<java.util.Map<String, Object>> status = jdbc.queryForList("""
				SELECT old_value, new_value, action_reason, changed_by_role_code, changed_by_company_id FROM audit_logs
				WHERE entity_name = 'Rfq' AND record_id = ? AND field_name = 'status' ORDER BY audit_log_id""", rfq);
		assertThat(status.stream().map(r -> r.get("old_value") + ">" + r.get("new_value")).toList())
			.containsExactly("DRAFT>SUBMITTED", "SUBMITTED>UNDER_REVIEW", "UNDER_REVIEW>CANCELLED");
		assertThat(status.get(2)).containsEntry("action_reason", "Customer withdrew").containsEntry("changed_by_role_code", "ERGO_RFQ");
		assertThat(((Number) status.get(2).get("changed_by_company_id")).longValue()).isEqualTo(ergoCo);
		assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_name = 'Rfq' AND record_id = ? AND event_type = 'CREATE' "
				+ "AND changed_by_role_code = 'CUSTOMER_RFQ' AND changed_by_company_id = ?", rfq, customer)).isEqualTo(1);
		// the internal note lives in the trail, which only ERGO may read
		call(get("/api/rfqs/{id}/history", rfq), ergoRfq, null).andExpect(status().isOk()).andExpect(jsonPath("$[*].newValue", hasItem("margin 12%")));
		call(get("/api/rfqs/{id}/history", rfq), buyer, null).andExpect(status().isForbidden());
		call(get("/api/rfqs/{id}/history", rfq), buyerB, null).andExpect(status().isNotFound());
		// a refused write leaves no row
		int rows = count("SELECT count(*) FROM audit_logs WHERE entity_name IN ('Rfq', 'RfqItem') AND record_id = ?", rfq);
		call(put("/api/rfqs/{id}", rfq), buyer, form(item("PN-1"))).andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/cancel", rfq), ergoRfq, "{\"comment\": \"again\"}").andExpect(status().isConflict());
		assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_name IN ('Rfq', 'RfqItem') AND record_id = ?", rfq)).isEqualTo(rows);
	}

	@Test
	void aClonedRfqCarriesNeitherInternalNotesNorFilesNorSuppliers() throws Exception {
		long rfq = reviewedRfq("PN-1", "PN-2");
		call(put("/api/rfqs/{id}/internal-notes", rfq), ergoRfq, "{\"internalNotes\": \"SECRET-INTERNAL\"}").andExpect(status().isOk());
		upload("/api/rfqs/{id}/documents", rfq, ergoRfq, "costing.xlsx").andExpect(status().isCreated());
		distribute(rfq, supplierX, parts(rfq));
		call(post("/api/rfqs/{id}/clone", rfq), buyerB, null).andExpect(status().isNotFound());
		long copy = idOf(call(post("/api/rfqs/{id}/clone", rfq), buyer, null).andExpect(status().isCreated()).andReturn());
		assertThat(rfqStatus(copy)).isEqualTo("DRAFT");
		assertThat(jdbc.queryForObject("SELECT internal_notes FROM rfqs WHERE rfq_id = ?", String.class, copy)).isNull();
		assertThat(count("SELECT count(*) FROM documents WHERE linked_module = 'rfq' AND linked_record_id = ?", copy)).isZero();
		assertThat(count("SELECT count(*) FROM rfq_supplier_assignments WHERE rfq_id = ?", copy)).isZero();
		assertThat(count("SELECT count(*) FROM rfq_items WHERE rfq_id = ?", copy)).isEqualTo(2);
		call(get("/api/rfqs/{id}", copy), buyer, null).andExpect(jsonPath("$.internalNotes").doesNotExist());
	}

}
