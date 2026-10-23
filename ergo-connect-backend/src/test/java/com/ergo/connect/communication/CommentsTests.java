package com.ergo.connect.communication;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Phase 6c: threaded comments (SRS §10.10, §12.12.1, §13.7.3) - audience separation across 2 customers and 2
 * suppliers on every record kind that has both sides, the per-kind audience limits (RFQ: customer side only;
 * quotation: supplier side only), one-level replies, the author-only edit window, query/resolve/reopen, ERGO-only
 * escalation, mentions, attachments (as Documents) and the Communication Center list.
 */
@IntegrationTest
class CommentsTests {

	private static final AtomicInteger SEQ = new AtomicInteger(700_000);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long ergo;

	long customerA;

	long customerB;

	long supplierX;

	long supplierY;

	long inr;

	Login ergoAdmin;

	Login ergoRfq;

	Login ergoManagement;

	Login ergoQuality;

	Login buyerA;

	Login buyerA2;

	Login buyerB;

	Login sellerX;

	Login sellerY;

	Login customerAOfp;

	Login customerBOfp;

	Login supplierXOfp;

	Login supplierYOfp;

	Login supplierXQuality;

	Login supplierYQuality;

	Login customerAQuality;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		ergo = users.company("ERGO");
		customerA = users.company("CUSTOMER");
		customerB = users.company("CUSTOMER");
		supplierX = users.company("SUPPLIER");
		supplierY = users.company("SUPPLIER");
		inr = users.currency("INR");
		ergoAdmin = users.login(ergo, "ERGO_ADMIN");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		ergoManagement = users.login(ergo, "ERGO_MANAGEMENT");
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		buyerA2 = users.login(customerA, "CUSTOMER_KAM");
		buyerB = users.login(customerB, "CUSTOMER_RFQ");
		sellerX = users.login(supplierX, "SUPPLIER_RFQ");
		sellerY = users.login(supplierY, "SUPPLIER_RFQ");
		customerAOfp = users.login(customerA, "CUSTOMER_OFP");
		customerBOfp = users.login(customerB, "CUSTOMER_OFP");
		supplierXOfp = users.login(supplierX, "SUPPLIER_OFP");
		supplierYOfp = users.login(supplierY, "SUPPLIER_OFP");
		supplierXQuality = users.login(supplierX, "SUPPLIER_QUALITY");
		supplierYQuality = users.login(supplierY, "SUPPLIER_QUALITY");
		customerAQuality = users.login(customerA, "CUSTOMER_QUALITY");
	}

	// ---------------------------------------------------------------- audience: RFQ (customer side only)

	@Test
	void aCustomerSeesOnlyTheExternalCustomerCommentsOnItsRfqAndAnotherCustomerSeesNothing() throws Exception {
		long rfqId = draftRfq(buyerA);
		say("rfq", rfqId, ergoRfq, "INTERNAL", "ERGO-only note");
		say("rfq", rfqId, ergoRfq, "EXTERNAL_CUSTOMER", "Hello customer");
		say("rfq", rfqId, buyerA, null, "Hello ERGO");

		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfqId), buyerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$[*].text", containsInAnyOrder("Hello customer", "Hello ERGO")))
			.andExpect(jsonPath("$[*].text", not(hasItem("ERGO-only note"))));
		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfqId), ergoRfq, null)
			.andExpect(jsonPath("$", hasSize(3)));
		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfqId), buyerB, null)
			.andExpect(status().isNotFound());
		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfqId), sellerX, null)
			.andExpect(status().isNotFound());
	}

	@Test
	void externalCallersCanOnlyPostToTheirOwnSideAndRfqsHaveNoSupplierSide() throws Exception {
		long rfqId = draftRfq(buyerA);
		for (String wrong : new String[] { "INTERNAL", "EXTERNAL_SUPPLIER" }) {
			call(post("/api/comments"), buyerA, body("rfq", rfqId, wrong, "nope", null))
				.andExpect(status().isUnprocessableContent());
		}
		call(post("/api/comments"), buyerA, body("rfq", rfqId, null, "default side", null))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.commentType").value("EXTERNAL_CUSTOMER"));
		// an RFQ is shared by several suppliers, so ERGO cannot open a supplier thread on it
		call(post("/api/comments"), ergoRfq, body("rfq", rfqId, "EXTERNAL_SUPPLIER", "x", null))
			.andExpect(status().isUnprocessableContent());
		call(post("/api/comments"), buyerB, body("rfq", rfqId, null, "intruder", null))
			.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- audience: quotation (supplier side only)

	@Test
	void supplierThreadsLiveOnEachSuppliersOwnQuoteAndNeverCrossOrReachTheCustomer() throws Exception {
		long rfqId = validatedRfq(buyerA);
		invite(rfqId, supplierX, supplierY);
		long ix = invitationOf(sellerX, rfqId);
		long iy = invitationOf(sellerY, rfqId);

		say("quotation", ix, sellerX, null, "X question");
		say("quotation", ix, ergoRfq, "INTERNAL", "internal on X");
		say("quotation", iy, sellerY, null, "Y question");

		call(get("/api/comments?linkedModule=quotation&linkedRecordId=" + ix), sellerX, null)
			.andExpect(jsonPath("$[*].text", containsInAnyOrder("X question")));
		call(get("/api/comments?linkedModule=quotation&linkedRecordId=" + ix), sellerY, null)
			.andExpect(status().isNotFound());
		call(get("/api/comments?linkedModule=quotation&linkedRecordId=" + ix), buyerA, null)
			.andExpect(status().isNotFound());
		call(get("/api/comments?linkedModule=quotation&linkedRecordId=" + ix), ergoRfq, null)
			.andExpect(jsonPath("$", hasSize(2)));
		call(post("/api/comments"), sellerX, body("quotation", ix, "INTERNAL", "x", null))
			.andExpect(status().isUnprocessableContent());
		call(post("/api/comments"), sellerY, body("quotation", ix, null, "x", null)).andExpect(status().isNotFound());
		call(post("/api/comments"), ergoRfq, body("quotation", ix, "EXTERNAL_CUSTOMER", "x", null))
			.andExpect(status().isUnprocessableContent());
	}

	// ---------------------------------------------------------------- audience: order (both sides)

	@Test
	void anOrderHasSeparateCustomerAndSupplierThreadsAndEachSideSeesOnlyItsOwn() throws Exception {
		long orderId = order(customerA, supplierX);
		say("ofp", orderId, ergoAdmin, "EXTERNAL_CUSTOMER", "for the customer");
		say("ofp", orderId, ergoAdmin, "EXTERNAL_SUPPLIER", "for the supplier");
		say("ofp", orderId, ergoAdmin, "INTERNAL", "internal");

		call(get("/api/comments?linkedModule=ofp&linkedRecordId=" + orderId), customerAOfp, null)
			.andExpect(jsonPath("$[*].text", containsInAnyOrder("for the customer")));
		call(get("/api/comments?linkedModule=ofp&linkedRecordId=" + orderId), supplierXOfp, null)
			.andExpect(jsonPath("$[*].text", containsInAnyOrder("for the supplier")));
		call(get("/api/comments?linkedModule=ofp&linkedRecordId=" + orderId), customerBOfp, null)
			.andExpect(status().isNotFound());
		call(get("/api/comments?linkedModule=ofp&linkedRecordId=" + orderId), supplierYOfp, null)
			.andExpect(status().isNotFound());
		call(get("/api/comments?linkedModule=ofp&linkedRecordId=" + orderId), ergoAdmin, null)
			.andExpect(jsonPath("$", hasSize(3)));
	}

	@Test
	void qualityRecordsHonourTheirOwnVisibilityGates() throws Exception {
		long ncrId = createNcr(customerA, supplierX);
		// the NCR is not customer-visible, so the customer cannot see the record or comment on it
		call(post("/api/comments"), customerAQuality, body("ncr", ncrId, null, "can I?", null))
			.andExpect(status().isNotFound());
		call(post("/api/comments"), supplierXQuality, body("ncr", ncrId, null, "root cause attached", null))
			.andExpect(status().isCreated());
		call(get("/api/comments?linkedModule=ncr&linkedRecordId=" + ncrId), ergoQuality, null)
			.andExpect(jsonPath("$", hasSize(1)));
	}

	// ---------------------------------------------------------------- replies

	@Test
	void repliesTakeTheirParentsAudienceAndThreadsAreOneLevelDeep() throws Exception {
		long rfqId = draftRfq(buyerA);
		long other = draftRfq(buyerA);
		long top = say("rfq", rfqId, ergoRfq, "EXTERNAL_CUSTOMER", "question");

		call(post("/api/comments"), buyerA, body("rfq", rfqId, null, "answer", top)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.commentType").value("EXTERNAL_CUSTOMER"))
			.andExpect(jsonPath("$.parentCommentId").value(top));
		long reply = idOf(call(post("/api/comments"), ergoRfq, body("rfq", rfqId, null, "thanks", top))
			.andExpect(status().isCreated()));
		call(post("/api/comments"), ergoRfq, body("rfq", rfqId, "INTERNAL", "sneaky", top))
			.andExpect(status().isUnprocessableContent());
		call(post("/api/comments"), ergoRfq, body("rfq", rfqId, null, "nested", reply))
			.andExpect(status().isUnprocessableContent());
		call(post("/api/comments"), ergoRfq, body("rfq", other, null, "wrong record", top))
			.andExpect(status().isUnprocessableContent());

		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfqId), buyerA, null)
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].replies", hasSize(2)));
	}

	// ---------------------------------------------------------------- edit window

	@Test
	void onlyTheAuthorEditsAndOnlyUntilSomeoneReplies() throws Exception {
		long rfqId = draftRfq(buyerA);
		long id = say("rfq", rfqId, buyerA, null, "first draft");

		call(patch("/api/comments/{id}", id), buyerA, edit("corrected", false)).andExpect(status().isOk())
			.andExpect(jsonPath("$.text").value("corrected"));
		// a colleague in the same company, and an ERGO Admin, are not the author
		call(patch("/api/comments/{id}", id), buyerA2, edit("hijack", false)).andExpect(status().isConflict());
		call(patch("/api/comments/{id}", id), ergoAdmin, edit("hijack", false)).andExpect(status().isConflict());

		call(post("/api/comments"), ergoRfq, body("rfq", rfqId, null, "reply", id)).andExpect(status().isCreated());
		call(patch("/api/comments/{id}", id), buyerA, edit("too late", false)).andExpect(status().isConflict());
		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfqId), buyerA, null)
			.andExpect(jsonPath("$[0].actions", not(hasItem("EDIT"))));

		// every edit is audited with old and new text
		Integer audited = jdbc.queryForObject(
				"SELECT count(*) FROM audit_logs WHERE module_name = 'communication' AND record_id = ? "
						+ "AND field_name ILIKE 'comment%text'",
				Integer.class, id);
		org.junit.jupiter.api.Assertions.assertTrue(audited != null && audited >= 1, "edit must be audited");
	}

	@Test
	void ergoManagementMayCreateAndEditItsOwnComment() throws Exception {
		long rfqId = draftRfq(buyerA);
		long id = say("rfq", rfqId, ergoManagement, "INTERNAL", "management note");
		call(patch("/api/comments/{id}", id), ergoManagement, edit("management note v2", false))
			.andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- queries, resolve, reopen

	@Test
	void queriesAreResolvedByTheAuthorOrErgoAndReopeningNeedsAReason() throws Exception {
		long rfqId = draftRfq(buyerA);
		long query = idOf(call(post("/api/comments"), buyerA, body("rfq", rfqId, null, "when can you quote?", null,
				true)).andExpect(status().isCreated()));
		long plain = say("rfq", rfqId, buyerA, null, "just a remark");

		call(post("/api/comments/{id}/resolve", plain), buyerA, "{}").andExpect(status().isConflict());
		call(post("/api/comments/{id}/resolve", query), buyerA2, "{}").andExpect(status().isForbidden());
		call(post("/api/comments/{id}/resolve", query), buyerB, "{}").andExpect(status().isNotFound());
		call(post("/api/comments/{id}/resolve", query), ergoRfq, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.resolved").value(true))
			.andExpect(jsonPath("$.actions", hasItem("REOPEN")));
		call(post("/api/comments/{id}/resolve", query), buyerA, "{}").andExpect(status().isConflict());

		call(post("/api/comments/{id}/reopen", query), buyerA, "{}").andExpect(status().isUnprocessableContent());
		call(post("/api/comments/{id}/reopen", query), buyerA, "{\"reason\": \"still open\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.resolved").value(false));
		// a reply cannot itself be a query
		call(post("/api/comments"), buyerA, body("rfq", rfqId, null, "reply", query, true))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void aResolvedQueryCanNoLongerBeEditedByItsAuthor() throws Exception {
		long rfqId = draftRfq(buyerA);
		long query = idOf(call(post("/api/comments"), buyerA, body("rfq", rfqId, null, "question?", null, true))
			.andExpect(status().isCreated()));
		call(post("/api/comments/{id}/resolve", query), buyerA, "{}").andExpect(status().isOk());
		call(patch("/api/comments/{id}", query), buyerA, edit("rewritten", true)).andExpect(status().isConflict());
	}

	// ---------------------------------------------------------------- escalation

	@Test
	void onlyErgoEscalatesAndLoweringNeedsAReason() throws Exception {
		long rfqId = draftRfq(buyerA);
		long top = say("rfq", rfqId, buyerA, null, "urgent");
		long reply = idOf(call(post("/api/comments"), ergoRfq, body("rfq", rfqId, null, "on it", top))
			.andExpect(status().isCreated()));

		call(post("/api/comments/{id}/escalate", top), buyerA, "{}").andExpect(status().isForbidden());
		call(post("/api/comments/{id}/escalate", reply), ergoRfq, "{}").andExpect(status().isConflict());
		call(post("/api/comments/{id}/escalate", top), ergoRfq, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.escalated").value(true));
		call(post("/api/comments/{id}/escalate", top), ergoRfq, "{}").andExpect(status().isConflict());
		// the customer sees the marker but never who set it
		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfqId), buyerA, null)
			.andExpect(jsonPath("$[0].escalated").value(true))
			.andExpect(jsonPath("$[0].escalatedBy").value("ERGO"));

		call(post("/api/comments/{id}/deescalate", top), ergoRfq, "{}").andExpect(status().isUnprocessableContent());
		call(post("/api/comments/{id}/deescalate", top), buyerA, "{\"reason\": \"x\"}")
			.andExpect(status().isForbidden());
		call(post("/api/comments/{id}/deescalate", top), ergoRfq, "{\"reason\": \"resolved offline\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.escalated").value(false));
	}

	// ---------------------------------------------------------------- people labels and mentions

	@Test
	void externalCallersSeeErgoStaffOnlyAsErgoAndErgoSeesRealNamesAndCompanies() throws Exception {
		long rfqId = draftRfq(buyerA);
		say("rfq", rfqId, ergoRfq, "EXTERNAL_CUSTOMER", "from staff");
		say("rfq", rfqId, buyerA, null, "from customer");

		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfqId), buyerA, null)
			.andExpect(jsonPath("$[0].author").value("ERGO"))
			.andExpect(jsonPath("$[1].mine").value(true));
		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfqId), ergoRfq, null)
			.andExpect(jsonPath("$[1].author", containsString("(")));
	}

	@Test
	void mentionsAreLimitedToPeopleWhoCanReadTheComment() throws Exception {
		long rfqId = draftRfq(buyerA);
		// a customer may mention a colleague but not ERGO staff or the other company's people
		call(post("/api/comments"), buyerA, body("rfq", rfqId, null, "fyi", null, false, buyerA2.userId()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.mentions", hasSize(1)));
		call(post("/api/comments"), buyerA, body("rfq", rfqId, null, "fyi", null, false, ergoRfq.userId()))
			.andExpect(status().isUnprocessableContent());
		call(post("/api/comments"), buyerA, body("rfq", rfqId, null, "fyi", null, false, buyerB.userId()))
			.andExpect(status().isUnprocessableContent());
		// ERGO may mention the customer on a customer-facing comment but not on an internal one
		call(post("/api/comments"), ergoRfq, body("rfq", rfqId, "EXTERNAL_CUSTOMER", "fyi", null, false,
				buyerA.userId())).andExpect(status().isCreated());
		call(post("/api/comments"), ergoRfq, body("rfq", rfqId, "INTERNAL", "fyi", null, false, buyerA.userId()))
			.andExpect(status().isUnprocessableContent());

		call(get("/api/comments/mentionable?linkedModule=rfq&linkedRecordId=" + rfqId), buyerA, null)
			.andExpect(jsonPath("$[*].userId", hasItem((int) buyerA2.userId())))
			.andExpect(jsonPath("$[*].userId", not(hasItem((int) ergoRfq.userId()))))
			.andExpect(jsonPath("$[*].userId", not(hasItem((int) buyerB.userId()))));
		call(get("/api/comments/mentionable?linkedModule=rfq&linkedRecordId=" + rfqId
				+ "&commentType=EXTERNAL_CUSTOMER"), ergoRfq, null)
			.andExpect(jsonPath("$[*].userId", hasItem((int) buyerA.userId())))
			.andExpect(jsonPath("$[*].userId", hasItem((int) ergoAdmin.userId())))
			.andExpect(jsonPath("$[*].userId", not(hasItem((int) sellerX.userId()))));
	}

	@Test
	void youCanOnlyMentionPeopleWhoCouldActuallyReadTheRecord() throws Exception {
		long rfqId = draftRfq(buyerA);
		// ERGO Quality has no RFQ scope, so mentioning them on an RFQ thread would disclose the thread
		call(post("/api/comments"), ergoRfq, body("rfq", rfqId, "INTERNAL", "heads up", null, false, ergoQuality.userId()))
			.andExpect(status().isUnprocessableContent());
		call(post("/api/comments"), ergoRfq, body("rfq", rfqId, "INTERNAL", "heads up", null, false, ergoAdmin.userId()))
			.andExpect(status().isCreated());

		// on an order only Customer OFP reaches the customer's records, not the same company's Customer RFQ user
		long orderId = order(customerA, supplierX);
		call(post("/api/comments"), ergoAdmin, body("ofp", orderId, "EXTERNAL_CUSTOMER", "x", null, false,
				buyerA.userId())).andExpect(status().isUnprocessableContent());
		call(post("/api/comments"), ergoAdmin, body("ofp", orderId, "EXTERNAL_CUSTOMER", "x", null, false,
				customerAOfp.userId())).andExpect(status().isCreated());
		call(get("/api/comments/mentionable?linkedModule=ofp&linkedRecordId=" + orderId
				+ "&commentType=EXTERNAL_CUSTOMER"), ergoAdmin, null)
			.andExpect(jsonPath("$[*].userId", hasItem((int) customerAOfp.userId())))
			.andExpect(jsonPath("$[*].userId", not(hasItem((int) buyerA.userId()))));
	}

	// ---------------------------------------------------------------- attachments (Documents, "comment")

	@Test
	void attachmentsAreDocumentsVisibleToExactlyTheCommentsAudienceAndFollowTheEditWindow() throws Exception {
		long rfqId = draftRfq(buyerA);
		long id = say("rfq", rfqId, buyerA, null, "see attached");
		long docId = ((Number) JsonPath.read(attach(id, buyerA, "spec.pdf").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.attachments[0].id")).longValue();

		call(get("/api/comments/{id}/attachments/{doc}/content", id, docId), buyerA, null)
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("spec.pdf")));
		call(get("/api/comments/{id}/attachments/{doc}/content", id, docId), ergoRfq, null).andExpect(status().isOk());
		call(get("/api/comments/{id}/attachments/{doc}/content", id, docId), buyerB, null)
			.andExpect(status().isNotFound());
		// the Document Library resolves the same audience through the communication facade
		call(get("/api/documents/{id}", docId), buyerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.linkedModule").value("comment"));
		call(get("/api/documents/{id}", docId), ergoAdmin, null).andExpect(status().isOk());
		call(get("/api/documents/{id}", docId), buyerB, null).andExpect(status().isNotFound());
		call(get("/api/documents/{id}", docId), sellerX, null).andExpect(status().isNotFound());

		// a new version is allowed while the comment is editable ... and blocked once someone replies
		mvc.perform(multipart("/api/documents/{id}/replace", docId)
			.file(new MockMultipartFile("file", "spec-v2.pdf", "application/octet-stream", "v2".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, buyerA.bearer())).andExpect(status().isOk());
		call(post("/api/comments"), ergoRfq, body("rfq", rfqId, null, "got it", id)).andExpect(status().isCreated());
		attach(id, buyerA, "late.pdf").andExpect(status().isConflict());
		call(delete("/api/comments/{id}/attachments/{doc}", id, docId), buyerA, null).andExpect(status().isConflict());
	}

	@Test
	void anInternalCommentsAttachmentIsNeverVisibleToACustomerEvenThroughTheLibrary() throws Exception {
		long rfqId = draftRfq(buyerA);
		long id = say("rfq", rfqId, ergoRfq, "INTERNAL", "internal file");
		long docId = ((Number) JsonPath.read(attach(id, ergoRfq, "internal.pdf").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.attachments[0].id")).longValue();
		call(get("/api/documents/{id}", docId), buyerA, null).andExpect(status().isNotFound());
		call(get("/api/comments/{id}/attachments/{doc}/content", id, docId), buyerA, null)
			.andExpect(status().isNotFound());
	}

	@Test
	void anAuthorCanRemoveAnAttachmentWhileTheCommentIsEditable() throws Exception {
		long rfqId = draftRfq(buyerA);
		long id = say("rfq", rfqId, buyerA, null, "oops wrong file");
		long docId = ((Number) JsonPath.read(attach(id, buyerA, "wrong.pdf").andReturn().getResponse()
			.getContentAsString(), "$.attachments[0].id")).longValue();
		call(delete("/api/comments/{id}/attachments/{doc}", id, docId), buyerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.attachments", hasSize(0)));
	}

	// ---------------------------------------------------------------- Communication Center

	@Test
	void theInboxListsOnlyThreadsTheCallerMayReadAndFiltersOpenQueriesAndEscalations() throws Exception {
		long rfqA = draftRfq(buyerA);
		long rfqB = draftRfq(buyerB);
		long openQuery = idOf(call(post("/api/comments"), buyerA, body("rfq", rfqA, null, "open question", null, true))
			.andExpect(status().isCreated()));
		long resolved = idOf(call(post("/api/comments"), buyerA, body("rfq", rfqA, null, "closed question", null,
				true)).andExpect(status().isCreated()));
		call(post("/api/comments/{id}/resolve", resolved), buyerA, "{}").andExpect(status().isOk());
		say("rfq", rfqA, ergoRfq, "INTERNAL", "internal thread");
		say("rfq", rfqB, buyerB, null, "other customer");
		call(post("/api/comments/{id}/escalate", openQuery), ergoRfq, "{}").andExpect(status().isOk());

		call(get("/api/comments/inbox?linkedModule=rfq"), buyerA, null)
			.andExpect(jsonPath("$.items[*].text", containsInAnyOrder("open question", "closed question")));
		call(get("/api/comments/inbox?linkedModule=rfq&openQueriesOnly=true"), buyerA, null)
			.andExpect(jsonPath("$.items[*].text", containsInAnyOrder("open question")));
		call(get("/api/comments/inbox?linkedModule=rfq&escalatedOnly=true"), buyerB, null)
			.andExpect(jsonPath("$.totalElements").value(0));
		call(get("/api/comments/inbox?linkedModule=rfq&openQueriesOnly=true"), ergoRfq, null)
			.andExpect(jsonPath("$.items[*].text", hasItem("open question")))
			.andExpect(jsonPath("$.items[*].text", not(hasItem("closed question"))));
		call(get("/api/comments/inbox?linkedModule=quotation"), buyerA, null)
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	// ---------------------------------------------------------------- second-pass regressions (H1, M1, M2, L3)

	@Test
	void reassigningARecordToAnotherCompanyDoesNotHandItTheOldPartysThread() throws Exception {
		long ncrId = createNcr(customerA, supplierX);
		say("ncr", ncrId, supplierXQuality, null, "supplier X root cause");
		jdbc.update("UPDATE ncrs SET supplier_company_id = ? WHERE ncr_id = ?", supplierY, ncrId);

		call(get("/api/comments?linkedModule=ncr&linkedRecordId=" + ncrId), supplierYQuality, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(0)));
		call(get("/api/comments?linkedModule=ncr&linkedRecordId=" + ncrId), supplierXQuality, null)
			.andExpect(status().isNotFound());
		call(get("/api/comments?linkedModule=ncr&linkedRecordId=" + ncrId), ergoQuality, null)
			.andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void mentionChangesAreAudited() throws Exception {
		long rfqId = draftRfq(buyerA);
		long id = idOf(call(post("/api/comments"), buyerA, body("rfq", rfqId, null, "fyi", null, false,
				buyerA2.userId())).andExpect(status().isCreated()));
		call(patch("/api/comments/{id}", id), buyerA, edit("fyi", false)).andExpect(status().isOk());
		Integer audited = jdbc.queryForObject(
				"SELECT count(*) FROM audit_logs WHERE module_name = 'communication' AND record_id = ? "
						+ "AND field_name = 'mentions'", Integer.class, id);
		org.junit.jupiter.api.Assertions.assertEquals(2, audited, "creating with a mention and removing it are both audited");
	}

	@Test
	void anAttachmentErgoHidesInTheLibraryIsHiddenOnTheCommentPathToo() throws Exception {
		long rfqId = draftRfq(buyerA);
		long id = say("rfq", rfqId, ergoRfq, "EXTERNAL_CUSTOMER", "see file");
		long docId = ((Number) JsonPath.read(attach(id, ergoRfq, "file.pdf").andReturn().getResponse()
			.getContentAsString(), "$.attachments[0].id")).longValue();
		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfqId), buyerA, null)
			.andExpect(jsonPath("$[0].attachments", hasSize(1)));

		call(patch("/api/documents/{id}", docId), ergoRfq, "{\"visibility\": \"INTERNAL\"}").andExpect(status().isOk());
		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfqId), buyerA, null)
			.andExpect(jsonPath("$[0].attachments", hasSize(0)));
		call(get("/api/comments/{id}/attachments/{doc}/content", id, docId), buyerA, null)
			.andExpect(status().isNotFound());
		call(get("/api/comments/{id}/attachments/{doc}/content", id, docId), ergoRfq, null).andExpect(status().isOk());
	}

	@Test
	void aReplacedAttachmentShowsOnlyItsLatestVersion() throws Exception {
		long rfqId = draftRfq(buyerA);
		long id = say("rfq", rfqId, buyerA, null, "file");
		long v1 = ((Number) JsonPath.read(attach(id, buyerA, "a.pdf").andReturn().getResponse()
			.getContentAsString(), "$.attachments[0].id")).longValue();
		mvc.perform(multipart("/api/documents/{id}/replace", v1)
			.file(new MockMultipartFile("file", "a-v2.pdf", "application/octet-stream", "v2".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, buyerA.bearer())).andExpect(status().isOk());
		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfqId), buyerA, null)
			.andExpect(jsonPath("$[0].attachments", hasSize(1)))
			.andExpect(jsonPath("$[0].attachments[0].fileName").value("a-v2.pdf"));
	}

	// ---------------------------------------------------------------- validation

	@Test
	void blankTextUnknownRecordKindsAndMissingRecordsAreRejected() throws Exception {
		long rfqId = draftRfq(buyerA);
		call(post("/api/comments"), buyerA, body("rfq", rfqId, null, "   ", null)).andExpect(status().is4xxClientError());
		call(post("/api/comments"), buyerA, body("spaceship", rfqId, null, "hi", null))
			.andExpect(status().isUnprocessableContent());
		call(post("/api/comments"), buyerA, body("rfq", 999_999_999L, null, "hi", null))
			.andExpect(status().isNotFound());
		call(patch("/api/comments/{id}", 999_999_999L), buyerA, edit("x", false)).andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- fixtures

	private long say(String module, long recordId, Login user, String type, String text) throws Exception {
		return idOf(call(post("/api/comments"), user, body(module, recordId, type, text, null))
			.andExpect(status().isCreated()));
	}

	private static String body(String module, long recordId, String type, String text, Long parent) {
		return body(module, recordId, type, text, parent, false);
	}

	private static String body(String module, long recordId, String type, String text, Long parent, boolean query,
			Long... mentions) {
		return """
				{"linkedModule": "%s", "linkedRecordId": %d, "commentType": %s, "text": "%s",
				 "parentCommentId": %s, "query": %s, "mentionedUserIds": %s}"""
			.formatted(module, recordId, type == null ? "null" : "\"" + type + "\"", text,
					parent == null ? "null" : parent.toString(), query, java.util.Arrays.toString(mentions));
	}

	private static String edit(String text, boolean query) {
		return "{\"text\": \"%s\", \"query\": %s, \"mentionedUserIds\": []}".formatted(text, query);
	}

	private ResultActions attach(long commentId, Login user, String name) throws Exception {
		return mvc.perform(multipart("/api/comments/{id}/attachments", commentId)
			.file(new MockMultipartFile("file", name, "application/octet-stream", name.getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

	private String rfqForm() {
		return TestRfq.form(inr, "PN-1", "PN-2");
	}

	private long draftRfq(Login buyer) throws Exception {
		return idOf(call(post("/api/rfqs"), buyer, rfqForm()).andExpect(status().isCreated()));
	}

	private long validatedRfq(Login buyer) throws Exception {
		long id = draftRfq(buyer);
		call(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		return id;
	}

	private void invite(long rfqId, long... supplierIds) throws Exception {
		TestRfq.invite(mvc, ergoRfq, rfqId, supplierIds);
	}

	private long invitationOf(Login supplier, long rfqId) throws Exception {
		String number = JsonPath.read(
				call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andReturn().getResponse().getContentAsString(),
				"$.number");
		List<Number> ids = JsonPath.read(call(get("/api/quotes"), supplier, null).andReturn().getResponse()
			.getContentAsString(), "$[?(@.rfqNumber == '" + number + "')].id");
		return ids.get(0).longValue();
	}

	private long order(long customerCompanyId, long supplierCompanyId) {
		return jdbc.queryForObject("""
				INSERT INTO orders (order_number, customer_company_id, supplier_company_id, part_number,
				                    ordered_quantity, current_status)
				VALUES (?, ?, ?, 'PN-ORDER', 10, 'DISPATCHED') RETURNING order_id""", Long.class,
				"CO" + SEQ.incrementAndGet(), customerCompanyId, supplierCompanyId);
	}

	private long createNcr(long customerCompanyId, long supplierCompanyId) throws Exception {
		String body = """
				{"linkedModule": "INCOMING", "linkedRecordId": %d, "issueCategory": "Dimensional",
				 "issueDescription": "part out of tolerance", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "ownerUserId": null}"""
			.formatted(SEQ.incrementAndGet(), LocalDate.now().plusDays(30), customerCompanyId, supplierCompanyId);
		return idOf(call(post("/api/quality/ncrs"), ergoQuality, body).andExpect(status().isCreated()));
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private static long idOf(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

}
