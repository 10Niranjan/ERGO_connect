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
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Flow doc §2-4: an RFQ of three parts is sent to suppliers whole or split into sub-RFQs, each supplier quotes each
 * part number on its own, and ERGO sends a quote back (revision required) or accepts it (SRS §5.3, §10.5, §14.3).
 */
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

	String number;

	long p1;

	long p2;

	long p3;

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
		rfqId = reviewedRfq();
		number = TestRfq.numberOf(mvc, ergoRfq, rfqId);
		List<Long> parts = TestRfq.itemIds(mvc, ergoRfq, rfqId);
		p1 = parts.get(0);
		p2 = parts.get(1);
		p3 = parts.get(2);
	}

	@Test
	void anRfqSplitsIntoSubRfqsAndSuppliersSeeOnlyTheirOwnParts() throws Exception {
		long draft = idOf(call(post("/api/rfqs"), buyer, rfqForm()).andReturn());
		long draftPart = TestRfq.itemIds(mvc, ergoRfq, draft).get(0);
		call(post("/api/rfqs/{id}/quotes", draft), ergoRfq, TestRfq.distribution(supplierX, List.of(draftPart)))
			.andExpect(status().isConflict()); // ERGO has not reviewed it yet

		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq,
				TestRfq.distribution(supplierX, List.of(p1, p2), supplierY, List.of(p3)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parts", hasSize(3)))
			.andExpect(jsonPath("$.parts[0].subRfqNo").value(1))
			.andExpect(jsonPath("$.parts[2].subRfqNo").value(2))
			.andExpect(jsonPath("$.parts[*].status", contains("NOT_STARTED", "NOT_STARTED", "NOT_STARTED")))
			.andExpect(jsonPath("$.canDistribute").value(false)); // every part is with a supplier
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("SENT_TO_SUPPLIERS"));
		call(get("/api/rfqs/{id}", rfqId), buyer, null).andExpect(jsonPath("$.status").value("IN_PROGRESS"));

		call(get("/api/quotes"), sellerX, null).andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[*].partNumber", containsInAnyOrder("PN-Q1", "PN-Q2")));
		call(get("/api/quotes"), sellerY, null).andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].partNumber").value("PN-Q3"));

		long x1 = quoteOf(sellerX, "PN-Q1");
		call(get("/api/quotes/{id}", x1), sellerX, null)
			.andExpect(jsonPath("$.audience").value("SUPPLIER"))
			.andExpect(jsonPath("$.part.partNumber").value("PN-Q1"))
			.andExpect(jsonPath("$.part.processSteps[0].stepName").value("Forging"))
			.andExpect(jsonPath("$.part.customer").doesNotExist())
			.andExpect(jsonPath("$.part.targetProductionPiecePrice").doesNotExist())
			.andExpect(content().string(not(containsString("Test CUSTOMER")))) // the customer's name
			.andExpect(content().string(not(containsString("Keep target secret"))))
			.andExpect(content().string(not(containsString("99.5")))) // the customer's target price
			.andExpect(content().string(not(containsString("Rival Forge Ltd")))) // RFQ step sub-supplier
			.andExpect(content().string(not(containsString("AS-12")))) // RFQ step remark
			.andExpect(content().string(not(containsString("PN-Q3")))) // a part that is with another supplier
			.andExpect(jsonPath("$.part.processSteps[0].cycleTimeSeconds").doesNotExist());

		call(get("/api/quotes/{id}", quoteOf(sellerY, "PN-Q3")), sellerX, null).andExpect(status().isNotFound());
		call(get("/api/rfqs"), sellerX, null).andExpect(status().isForbidden()); // never the RFQ module itself
		call(get("/api/quotes"), users.login(supplierX, "SUPPLIER_NPD"), null).andExpect(status().isForbidden());

		// the same part cannot go to a second supplier, nor a supplier twice, nor a customer or a stranger's part
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, TestRfq.distribution(supplierY, List.of(p1)))
			.andExpect(status().isUnprocessableContent());
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, TestRfq.distribution(customer, List.of(p1)))
			.andExpect(status().isUnprocessableContent());
		long otherRfq = reviewedRfq();
		long foreign = TestRfq.itemIds(mvc, ergoRfq, otherRfq).get(0);
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, TestRfq.distribution(supplierY, List.of(foreign)))
			.andExpect(status().isUnprocessableContent());
		call(post("/api/rfqs/{id}/quotes", otherRfq), ergoRfq,
				TestRfq.distribution(supplierX, List.of(foreign), supplierX, List.of(foreign)))
			.andExpect(status().isUnprocessableContent());

		// the whole RFQ to one supplier is the other way to distribute
		call(post("/api/rfqs/{id}/quotes", otherRfq), ergoRfq, TestRfq.wholeTo(mvc, ergoRfq, otherRfq, supplierY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[*].subRfqNo", contains(1, 1, 1)));
	}

	@Test
	void eachPartHasItsOwnStatusAndTheCustomerSeesWhereItStands() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq,
				TestRfq.distribution(supplierX, List.of(p1, p2), supplierY, List.of(p3)));
		long x1 = quoteOf(sellerX, "PN-Q1");
		call(put("/api/quotes/{id}", x1), sellerX, quote("81.25")).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", x1), sellerX, null).andExpect(jsonPath("$.status").value("SUBMITTED"));

		call(get("/api/rfqs/{id}/parts", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$[*].status", contains("SUBMITTED", "NOT_STARTED", "NOT_STARTED")))
			.andExpect(jsonPath("$[*].partNumber", contains("PN-Q1", "PN-Q2", "PN-Q3")));
		call(get("/api/rfqs/{id}/parts", rfqId), buyer, null)
			.andExpect(jsonPath("$[*].status", contains("IN_PROGRESS", "IN_PROGRESS", "IN_PROGRESS")));
		call(get("/api/rfqs/{id}/parts", rfqId), sellerX, null).andExpect(status().isForbidden());
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("QUOTES_PARTIAL"));
	}

	@Test
	void submissionLocksRevisionNeedsCommentsAcceptingEndsTheLoopAndEveryVersionIsKept() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq,
				TestRfq.distribution(supplierX, List.of(p1, p2), supplierY, List.of(p3)));
		long x1 = quoteOf(sellerX, "PN-Q1");

		call(put("/api/quotes/{id}", x1), sellerX, "{\"productionPiecePrice\": 80}")
			.andExpect(status().isUnprocessableContent()); // money is always tagged with its currency
		call(put("/api/quotes/{id}", x1), sellerX, "{\"currencyId\": %d, \"productionPiecePrice\": 80}".formatted(inr))
			.andExpect(jsonPath("$.status").value("DRAFT_SAVED"));
		call(post("/api/quotes/{id}/submit", x1), sellerX, null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("cost breakdown")));
		call(get("/api/rfqs/{id}/quotes", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$.parts[0].productionPiecePrice").doesNotExist()); // drafts stay private

		call(put("/api/quotes/{id}", x1), sellerX, quote("81.25")).andExpect(status().isOk())
			.andExpect(jsonPath("$.quote.toolingCostTotal").value(150000.0)); // sum of the tooling lines
		call(post("/api/quotes/{id}/submit", x1), sellerX, null).andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.actions", not(hasItem("EDIT"))));
		call(put("/api/quotes/{id}", x1), sellerX, quote("70")).andExpect(status().isConflict()); // locked

		// ERGO sends the quote back with comments, and only with comments
		call(post("/api/quotes/{id}/revision", x1), ergoRfq, "{}").andExpect(status().isUnprocessableContent());
		call(post("/api/quotes/{id}/revision", x1), sellerX, "{\"comment\": \"self\"}").andExpect(status().isForbidden());
		call(post("/api/quotes/{id}/revision", x1), ergoRfq, "{\"comment\": \"Please quote with 2 cavities\"}")
			.andExpect(jsonPath("$.status").value("REVISION_REQUIRED"));
		call(get("/api/quotes/{id}", x1), sellerX, null)
			.andExpect(jsonPath("$.status").value("REVISION_REQUIRED"))
			.andExpect(jsonPath("$.revisionReason").value("Please quote with 2 cavities"))
			.andExpect(jsonPath("$.actions", hasItem("EDIT")));

		call(put("/api/quotes/{id}", x1), sellerX, quote("76.40")).andExpect(status().isOk());
		call(get("/api/quotes/{id}", x1), ergoRfq, null) // ERGO still sees what was submitted, not the new draft
			.andExpect(jsonPath("$.quote.productionPiecePrice").value(81.25));
		call(post("/api/quotes/{id}/submit", x1), sellerX, null).andExpect(jsonPath("$.status").value("RESUBMITTED"))
			.andExpect(jsonPath("$.version").value(2));

		// only a submitted quote can be accepted; accepting is the final-approval role's job
		long x2 = quoteOf(sellerX, "PN-Q2");
		call(post("/api/quotes/{id}/accept", x2), ergoRfq, null).andExpect(status().isConflict());
		call(post("/api/quotes/{id}/accept", x1), sellerX, null).andExpect(status().isForbidden());
		call(post("/api/quotes/{id}/accept", x1), ergoRfq, null).andExpect(jsonPath("$.status").value("ACCEPTED"));
		call(get("/api/quotes/{id}", x1), sellerX, null).andExpect(jsonPath("$.status").value("ACCEPTED"));
		call(get("/api/rfqs/{id}/parts", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$[*].status", contains("ACCEPTED", "NOT_STARTED", "NOT_STARTED")));

		call(get("/api/quotes/{id}/versions", x1), ergoRfq, null)
			.andExpect(jsonPath("$[*].version", contains(1, 2)))
			.andExpect(jsonPath("$[0].quote.productionPiecePrice").value(81.25))
			.andExpect(jsonPath("$[1].quote.productionPiecePrice").value(76.40))
			.andExpect(jsonPath("$[1].reopenReason").value("Please quote with 2 cavities"));
		assertThatThrownBy(() -> jdbc.update("UPDATE supplier_quote_versions SET version_no = 9"))
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.execute("TRUNCATE supplier_quote_versions")).hasMessageContaining("append-only");
	}

	@Test
	void rfqStatusFollowsTheParts() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq,
				TestRfq.distribution(supplierX, List.of(p1, p2), supplierY, List.of(p3)));
		long x1 = quoteOf(sellerX, "PN-Q1");
		long x2 = quoteOf(sellerX, "PN-Q2");
		long y3 = quoteOf(sellerY, "PN-Q3");
		for (long quote : List.of(x1, x2)) {
			call(put("/api/quotes/{id}", quote), sellerX, quote("90")).andExpect(status().isOk());
			call(post("/api/quotes/{id}/submit", quote), sellerX, null).andExpect(status().isOk());
		}
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("QUOTES_PARTIAL"));
		call(put("/api/quotes/{id}", y3), sellerY, quote("95")).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", y3), sellerY, null).andExpect(status().isOk());
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("QUOTES_COMPLETE"));
		call(get("/api/rfqs/{id}", rfqId), buyer, null).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
		// sending one part back makes the RFQ wait for that supplier again
		call(post("/api/quotes/{id}/revision", y3), ergoRfq, "{\"comment\": \"Price check\"}").andExpect(status().isOk());
		call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andExpect(jsonPath("$.status").value("QUOTES_PARTIAL"));
	}

	@Test
	void ergoCanTakeAPartBackAndSendItToAnotherSupplier() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq,
				TestRfq.distribution(supplierX, List.of(p1, p2), supplierY, List.of(p3)));
		long x1 = quoteOf(sellerX, "PN-Q1");
		call(put("/api/quotes/{id}", x1), sellerX, quote("90"));

		call(post("/api/quotes/{id}/recall", x1), ergoRfq, "{}").andExpect(status().isUnprocessableContent());
		call(post("/api/quotes/{id}/recall", x1), sellerX, "{\"comment\": \"mine\"}").andExpect(status().isForbidden());
		call(post("/api/quotes/{id}/recall", x1), ergoRfq, "{\"comment\": \"Supplier lacks forging capacity\"}")
			.andExpect(jsonPath("$.status").value("RECALLED"));
		call(get("/api/quotes/{id}", x1), sellerX, null).andExpect(status().isNotFound());
		call(get("/api/quotes"), sellerX, null).andExpect(jsonPath("$", hasSize(1)));
		call(get("/api/rfqs/{id}/quotes", rfqId), ergoRfq, null)
			.andExpect(jsonPath("$.parts[0].quoteId").doesNotExist())
			.andExpect(jsonPath("$.canDistribute").value(true));

		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, TestRfq.distribution(supplierY, List.of(p1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[0].supplier.id").value(supplierY))
			.andExpect(jsonPath("$.parts[0].subRfqNo").value(3));
		call(get("/api/quotes"), sellerY, null).andExpect(jsonPath("$", hasSize(2)));
	}

	@Test
	void anRfqCannotBeEditedOnceItIsOutWithSuppliers() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, TestRfq.wholeTo(mvc, ergoRfq, rfqId, supplierX));
		call(put("/api/rfqs/{id}", rfqId), ergoRfq, rfqForm()).andExpect(status().isConflict());
	}

	@Test
	void ergoScopeAppliesToQuotesAndCustomersSeeNone() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, TestRfq.wholeTo(mvc, ergoRfq, rfqId, supplierX));
		long x1 = quoteOf(sellerX, "PN-Q1");
		Login kam = users.login(users.company("ERGO"), "ERGO_KAM");
		users.assignAccounts(kam.userId(), customer);
		Login otherKam = users.login(users.company("ERGO"), "ERGO_KAM");
		Login npd = users.login(users.company("ERGO"), "ERGO_NPD");

		call(get("/api/rfqs/{id}/quotes", rfqId), kam, null).andExpect(jsonPath("$.canDistribute").value(false));
		call(post("/api/rfqs/{id}/quotes", rfqId), kam, TestRfq.distribution(supplierY, List.of(p1)))
			.andExpect(status().isForbidden());
		call(get("/api/rfqs/{id}/quotes", rfqId), otherKam, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}", x1), otherKam, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}", x1), npd, null).andExpect(status().isNotFound()); // not approved yet
		call(get("/api/rfqs/{id}/quotes", rfqId), buyer, null).andExpect(status().isForbidden());
		call(get("/api/quotes/{id}", x1), buyer, null).andExpect(status().isForbidden());
		call(get("/api/quotes"), buyer, null).andExpect(status().isForbidden());

		// view-only ERGO roles never act on quotes
		call(put("/api/quotes/{id}", x1), sellerX, quote("90"));
		call(post("/api/quotes/{id}/submit", x1), sellerX, null);
		for (Login viewer : List.of(kam, npd)) {
			call(post("/api/quotes/{id}/accept", x1), viewer, null).andExpect(status().isForbidden());
			call(post("/api/quotes/{id}/revision", x1), viewer, "{\"comment\": \"r\"}").andExpect(status().isForbidden());
			call(post("/api/quotes/{id}/recall", x1), viewer, "{\"comment\": \"r\"}").andExpect(status().isForbidden());
			upload("/api/quotes/{id}/documents", x1, viewer, "note.pdf").andExpect(status().isForbidden());
		}
	}

	@Test
	void anotherSupplierCannotTouchAQuote() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq,
				TestRfq.distribution(supplierX, List.of(p1, p2), supplierY, List.of(p3)));
		long x1 = quoteOf(sellerX, "PN-Q1");
		call(put("/api/quotes/{id}", x1), sellerX, quote("90")).andExpect(status().isOk());

		call(put("/api/quotes/{id}", x1), sellerY, quote("1")).andExpect(status().isNotFound());
		call(post("/api/quotes/{id}/submit", x1), sellerY, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/versions", x1), sellerY, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/documents", x1), sellerY, null).andExpect(status().isNotFound());
		upload("/api/quotes/{id}/documents", x1, sellerY, "y.pdf").andExpect(status().isNotFound());
	}

	@Test
	void suppliersGetOnlyTheFilesErgoSharedAndTheirOwn() throws Exception {
		long shared = idOf(upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "drawing.pdf").andReturn());
		long internal = idOf(upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "costing.xlsx").andReturn()); // INTERNAL
		// shared for part 1 only: supplier Y, which holds another part, must never see it
		call(put("/api/rfqs/{id}/documents/{doc}/visibility", rfqId, shared), ergoRfq,
				"{\"visibility\": \"ALL\"}").andExpect(status().isUnprocessableContent());
		call(put("/api/rfqs/{id}/documents/{doc}/visibility", rfqId, shared), ergoRfq,
				"{\"visibility\": \"ALL\", \"itemIds\": [%d]}".formatted(p1)).andExpect(status().isOk());
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq,
				TestRfq.distribution(supplierX, List.of(p1, p2), supplierY, List.of(p3)));
		long x1 = quoteOf(sellerX, "PN-Q1");
		long y3 = quoteOf(sellerY, "PN-Q3");

		long sheet = idOf(upload("/api/quotes/{id}/documents", x1, sellerX, "x-process-sheet.pdf")
			.andExpect(status().isCreated()).andReturn());
		call(get("/api/quotes/{id}/documents", x1), sellerX, null)
			.andExpect(jsonPath("$[*].fileName", containsInAnyOrder("drawing.pdf", "x-process-sheet.pdf")));
		call(get("/api/quotes/{id}/documents", y3), sellerY, null).andExpect(jsonPath("$", hasSize(0)));
		call(get("/api/quotes/{id}/documents/{doc}/content?source=RFQ", y3, shared), sellerY, null)
			.andExpect(status().isNotFound());
		call(get("/api/documents/{id}", shared), sellerY, null).andExpect(status().isNotFound());
		call(get("/api/documents/{id}", shared), sellerX, null).andExpect(status().isOk());
		call(get("/api/quotes/{id}/documents/{doc}/content?source=RFQ", x1, shared), sellerX, null)
			.andExpect(status().isOk());
		call(get("/api/quotes/{id}/documents/{doc}/content?source=RFQ", x1, internal), sellerX, null)
			.andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/documents/{doc}/content", y3, sheet), sellerY, null).andExpect(status().isNotFound());

		// a draft file stays with the supplier until it is submitted with the quote
		call(get("/api/quotes/{id}/documents", x1), ergoRfq, null).andExpect(jsonPath("$", hasSize(0)));
		call(get("/api/quotes/{id}/documents/{doc}/content", x1, sheet), ergoRfq, null).andExpect(status().isNotFound());
		call(put("/api/quotes/{id}", x1), sellerX, quote("90"));
		call(post("/api/quotes/{id}/submit", x1), sellerX, null).andExpect(status().isOk());
		call(get("/api/quotes/{id}/documents", x1), ergoRfq, null)
			.andExpect(jsonPath("$[*].fileName", contains("x-process-sheet.pdf")));
		call(get("/api/quotes/{id}/documents/{doc}/content", x1, sheet), ergoRfq, null).andExpect(status().isOk());

		// after a revision request, submitted files stay; new ones are private again until resubmitted
		call(post("/api/quotes/{id}/revision", x1), ergoRfq, "{\"comment\": \"Add the PPAP plan\"}");
		call(delete("/api/quotes/{id}/documents/{doc}", x1, sheet), sellerX, null).andExpect(status().isConflict());
		long plan = idOf(upload("/api/quotes/{id}/documents", x1, sellerX, "ppap-plan.pdf").andReturn());
		call(get("/api/quotes/{id}/documents", x1), ergoRfq, null)
			.andExpect(jsonPath("$[*].fileName", contains("x-process-sheet.pdf")));
		call(post("/api/quotes/{id}/submit", x1), sellerX, null).andExpect(status().isOk());
		call(delete("/api/quotes/{id}/documents/{doc}", x1, plan), sellerX, null).andExpect(status().isConflict());
		call(get("/api/quotes/{id}/versions", x1), ergoRfq, null)
			.andExpect(jsonPath("$[0].files", contains("x-process-sheet.pdf")))
			.andExpect(jsonPath("$[1].files", contains("ppap-plan.pdf", "x-process-sheet.pdf")));
	}

	@Test
	void anRfqCanBeCancelledOnlyBeforeQuotesArriveThenOnlyClosed() throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfqId), ergoRfq, TestRfq.wholeTo(mvc, ergoRfq, rfqId, supplierX));
		long other = reviewedRfq();
		call(post("/api/rfqs/{id}/quotes", other), ergoRfq, TestRfq.wholeTo(mvc, ergoRfq, other, supplierX));
		call(post("/api/rfqs/{id}/cancel", other), ergoRfq, "{\"comment\": \"Customer withdrew\"}")
			.andExpect(jsonPath("$.status").value("CANCELLED"));

		long x1 = quoteOf(sellerX, "PN-Q1");
		call(put("/api/quotes/{id}", x1), sellerX, quote("90"));
		call(post("/api/quotes/{id}/submit", x1), sellerX, null);
		call(post("/api/rfqs/{id}/cancel", rfqId), ergoRfq, "{\"comment\": \"Too late\"}").andExpect(status().isConflict());
		call(post("/api/rfqs/{id}/close", rfqId), ergoRfq, "{\"comment\": \"Customer lost the program\"}")
			.andExpect(jsonPath("$.status").value("CLOSED"));
		Login npd = users.login(users.company("ERGO"), "ERGO_NPD"); // NPD sees approved RFQs only
		call(get("/api/rfqs/{id}", rfqId), npd, null).andExpect(status().isNotFound());
		call(get("/api/rfqs/{id}/quotes", rfqId), npd, null).andExpect(status().isNotFound());
		call(get("/api/quotes/{id}/versions", x1), npd, null).andExpect(status().isNotFound());
		call(post("/api/quotes/{id}/revision", x1), ergoRfq, "{\"comment\": \"late\"}").andExpect(status().isConflict());
	}

	// ---------------------------------------------------------------- helpers

	/** Created by the customer, submitted, and taken into review by ERGO (flow doc §1-2). */
	private long reviewedRfq() throws Exception {
		long id = idOf(call(post("/api/rfqs"), buyer, rfqForm()).andReturn());
		call(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		return id;
	}

	/** Three parts: the first with a process route that names a sub-supplier, which suppliers must never see. */
	private String rfqForm() {
		return """
				{"targetQuoteDate": "%s", "currencyId": %d, "customerRemarks": "Keep target secret",
				 "items": [
				  {"partNumber": "PN-Q1", "description": "Flange", "materialSpec": "EN8", "targetProductionPiecePrice": 99.5,
				   "processSteps": [{"stepName": "Forging", "cycleTimeSeconds": 55, "sourcingType": "OUTSOURCED",
				                     "outsourcedSupplierName": "Rival Forge Ltd", "remarks": "per customer spec AS-12"},
				                    {"stepName": "Machining"}]},
				  {"partNumber": "PN-Q2", "description": "Housing", "materialSpec": "AlSi9",
				   "processSteps": [{"stepName": "Casting"}]},
				  {"partNumber": "PN-Q3", "description": "Cap", "materialSpec": "PA66",
				   "processSteps": [{"stepName": "Moulding"}]}]}"""
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

	private long quoteOf(Login supplier, String partNumber) throws Exception {
		return TestRfq.quoteOf(mvc, supplier, number, partNumber);
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
