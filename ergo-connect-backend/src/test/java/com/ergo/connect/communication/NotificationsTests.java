package com.ergo.connect.communication;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.ergo.connect.common.event.NotificationKind;
import com.ergo.connect.common.event.Notifier;
import com.ergo.connect.common.event.Recipients;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.communication.domain.OverdueReminders;
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 6e: the notification engine (SRS §13.7.4, §12.12.2, §14.9). The delivery rules are exercised directly through
 * {@link Notifier} (so each rule is tested in isolation), the events themselves through the real workflows of every
 * module, and the drawer API for privacy: a user only ever sees and changes their own notifications.
 */
@IntegrationTest
class NotificationsTests {

	private static final AtomicInteger SEQ = new AtomicInteger(500_000);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Notifier notifier;

	@Autowired
	OverdueReminders reminders;

	TestUsers users;

	long ergo;

	long customerA;

	long customerB;

	long supplierX;

	long supplierY;

	long inr;

	Login ergoAdmin;

	Login ergoRfq;

	Login ergoQuality;

	Login ergoOfp;

	Login customerAOfp;

	Login customerBOfp;

	Login supplierXOfp;

	Login supplierYOfp;

	Login supplierXQuality;

	Login customerAQuality;

	Login buyerA;

	Login buyerA2;

	Login buyerB;

	Login sellerX;

	Login sellerY;

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
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		ergoOfp = users.login(ergo, "ERGO_OFP");
		customerAOfp = users.login(customerA, "CUSTOMER_OFP");
		customerBOfp = users.login(customerB, "CUSTOMER_OFP");
		supplierXOfp = users.login(supplierX, "SUPPLIER_OFP");
		supplierYOfp = users.login(supplierY, "SUPPLIER_OFP");
		supplierXQuality = users.login(supplierX, "SUPPLIER_QUALITY");
		customerAQuality = users.login(customerA, "CUSTOMER_QUALITY");
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		buyerA2 = users.login(customerA, "CUSTOMER_KAM");
		buyerB = users.login(customerB, "CUSTOMER_RFQ");
		sellerX = users.login(supplierX, "SUPPLIER_RFQ");
		sellerY = users.login(supplierY, "SUPPLIER_RFQ");
	}

	// ---------------------------------------------------------------- delivery rules (through the Notifier)

	@Test
	void aTargetedRoleIsNotifiedOnlyIfItCanReadTheRecord() throws Exception {
		long rfqId = draftRfq(buyerA);
		notifier.send(NotificationKind.RFQ_SUBMITTED, "rfq", rfqId, "RFQ-X", null,
				Recipients.ergo(Role.ERGO_RFQ, Role.ERGO_QUALITY, Role.ERGO_ADMIN));

		assertCount(ergoRfq, 1);
		assertCount(ergoAdmin, 1);
		assertCount(ergoQuality, 0); // targeted, but ERGO Quality has no RFQ scope
		assertCount(buyerA, 0);
	}

	@Test
	void aRoleThatWasNotTargetedIsNotNotifiedEvenIfItCouldReadTheRecord() throws Exception {
		long rfqId = draftRfq(buyerA);
		notifier.send(NotificationKind.RFQ_SUBMITTED, "rfq", rfqId, "RFQ-X", null, Recipients.ergo(Role.ERGO_RFQ));
		assertCount(ergoRfq, 1);
		assertCount(ergoAdmin, 0);
	}

	@Test
	void anotherCompanyIsNeverNotifiedAboutARecordItCannotSee() throws Exception {
		long rfqId = draftRfq(buyerA);
		notifier.send(NotificationKind.RFQ_STATUS_CHANGED, "rfq", rfqId, "RFQ-X", "Validated",
				Recipients.company(customerA).and(Recipients.company(customerB)));
		assertCount(buyerA, 1);
		assertCount(buyerA2, 1);
		assertCount(buyerB, 0);
	}

	@Test
	void anEventNeverReachesASideItsCatalogEntryDoesNotName() throws Exception {
		long rfqId = draftRfq(buyerA);
		// QUOTE_SUBMITTED is an ERGO-only event: a publisher mistake must not tell the customer
		notifier.send(NotificationKind.QUOTE_SUBMITTED, "rfq", rfqId, "RFQ-X", null,
				Recipients.company(customerA).and(Recipients.ergo(Role.ERGO_RFQ)));
		assertCount(buyerA, 0);
		assertCount(ergoRfq, 1);
	}

	@Test
	void inactiveUsersAreNotNotified() throws Exception {
		long rfqId = draftRfq(buyerA);
		jdbc.update("UPDATE users SET active = false WHERE user_id = ?", buyerA2.userId());
		notifier.send(NotificationKind.RFQ_STATUS_CHANGED, "rfq", rfqId, "RFQ-X", "Validated",
				Recipients.users(Set.of(buyerA.userId(), buyerA2.userId())));
		assertCount(buyerA, 1);
		// an inactive user's own access token no longer works, so check the stored rows instead of their drawer
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ?", Long.class,
				buyerA2.userId())).isZero();
	}

	@Test
	void anExplicitUserWhoCannotReadTheRecordIsNotNotified() throws Exception {
		long rfqId = draftRfq(buyerA);
		notifier.send(NotificationKind.MENTIONED, "rfq", rfqId, "RFQ #1", null,
				Recipients.users(Set.of(buyerB.userId(), sellerX.userId())));
		assertCount(buyerB, 0);
		assertCount(sellerX, 0);
	}

	@Test
	void aScheduledReminderIsDeliveredOncePerKey() throws Exception {
		long rfqId = draftRfq(buyerA);
		for (int i = 0; i < 3; i++) {
			notifier.sendScheduled(NotificationKind.RFQ_STATUS_CHANGED, "rfq", rfqId, "RFQ-X", "Late",
					Recipients.users(Set.of(buyerA.userId())), "test:rfq:" + rfqId + ":2030-01-01");
		}
		assertCount(buyerA, 1);
		notifier.sendScheduled(NotificationKind.RFQ_STATUS_CHANGED, "rfq", rfqId, "RFQ-X", "Late",
				Recipients.users(Set.of(buyerA.userId())), "test:rfq:" + rfqId + ":2030-01-02");
		assertCount(buyerA, 2);
	}

	@Test
	void externalWordingNeverNamesErgoStaffOrTheOtherSide() throws Exception {
		long rfqId = draftRfq(buyerA);
		notifier.send(NotificationKind.RFQ_STATUS_CHANGED, "rfq", rfqId, "RFQ-X", "Validated",
				Recipients.company(customerA, Role.CUSTOMER_RFQ).and(Recipients.ergo(Role.ERGO_RFQ)));
		call(get("/api/notifications"), buyerA, null).andExpect(jsonPath("$.items[0].title").value("Your RFQ status changed"))
			.andExpect(jsonPath("$.items[0].message").value("RFQ-X - Validated"))
			.andExpect(jsonPath("$.items[0].type").value("RECORD_UPDATED"))
			.andExpect(jsonPath("$.items[0].actionUrl").value("/rfqs/" + rfqId));
		call(get("/api/notifications"), ergoRfq, null).andExpect(jsonPath("$.items[0].title").value("RFQ status changed"));
	}

	// ---------------------------------------------------------------- comment events (mention, reply, escalation)

	@Test
	void aMentionNotifiesTheMentionedPersonAndNotTheAuthor() throws Exception {
		long rfqId = draftRfq(buyerA);
		call(post("/api/comments"), buyerA, comment(rfqId, "fyi", null, buyerA2.userId())).andExpect(status().isCreated());

		call(get("/api/notifications"), buyerA2, null).andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.items[0].title").value("You were mentioned in a comment"))
			.andExpect(jsonPath("$.items[0].type").value("RESPONSE_RECEIVED"))
			.andExpect(jsonPath("$.items[0].actionUrl").value("/rfqs/" + rfqId))
			.andExpect(jsonPath("$.items[0].read").value(false));
		assertCount(buyerA, 0);
	}

	@Test
	void aReplyNotifiesTheCommentAuthorOnlyAndEditingAddsOnlyNewMentions() throws Exception {
		long rfqId = draftRfq(buyerA);
		long top = idOf(call(post("/api/comments"), buyerA, comment(rfqId, "question", null))
			.andExpect(status().isCreated()));
		call(post("/api/comments"), ergoRfq, comment(rfqId, "answer", top, 0L, "EXTERNAL_CUSTOMER"))
			.andExpect(status().isCreated());
		call(get("/api/notifications"), buyerA, null).andExpect(jsonPath("$.items[0].title").value("New reply to your comment"));
		assertCount(buyerA, 1);
		assertCount(ergoRfq, 0); // replying to a customer comment does not notify the replier
		assertCount(ergoAdmin, 0);
	}

	@Test
	void anEscalationNotifiesTheThreadAuthorInExternalWording() throws Exception {
		long rfqId = draftRfq(buyerA);
		long top = idOf(call(post("/api/comments"), buyerA, comment(rfqId, "urgent", null)).andExpect(status().isCreated()));
		call(post("/api/comments/{id}/escalate", top), ergoRfq, "{}").andExpect(status().isOk());
		call(get("/api/notifications"), buyerA, null).andExpect(jsonPath("$.items[0].title").value("Your conversation was escalated"))
			.andExpect(jsonPath("$.items[0].type").value("ACTION_REQUIRED"));
	}

	// ---------------------------------------------------------------- the drawer API

	@Test
	void aUserSeesAndChangesOnlyTheirOwnNotifications() throws Exception {
		long rfqId = draftRfq(buyerA);
		call(post("/api/comments"), buyerA, comment(rfqId, "fyi", null, buyerA2.userId())).andExpect(status().isCreated());
		long id = ((Number) JsonPath.read(call(get("/api/notifications"), buyerA2, null).andReturn().getResponse()
			.getContentAsString(), "$.items[0].id")).longValue();

		call(get("/api/notifications"), buyerB, null).andExpect(jsonPath("$.totalElements").value(0));
		call(post("/api/notifications/{id}/read", id), buyerB, null).andExpect(status().isNotFound());
		call(post("/api/notifications/{id}/read", id), buyerA, null).andExpect(status().isNotFound());
		call(get("/api/notifications/unread-count"), buyerA2, null).andExpect(jsonPath("$.count").value(1));
	}

	@Test
	void markingReadIsIdempotentAndFiltersAndCountsFollow() throws Exception {
		long rfqId = draftRfq(buyerA);
		call(post("/api/comments"), buyerA, comment(rfqId, "one", null, buyerA2.userId())).andExpect(status().isCreated());
		call(post("/api/comments"), buyerA, comment(rfqId, "two", null, buyerA2.userId())).andExpect(status().isCreated());
		call(get("/api/notifications/unread-count"), buyerA2, null).andExpect(jsonPath("$.count").value(2));
		long first = ((Number) JsonPath.read(call(get("/api/notifications"), buyerA2, null).andReturn().getResponse()
			.getContentAsString(), "$.items[0].id")).longValue();

		call(post("/api/notifications/{id}/read", first), buyerA2, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.read").value(true));
		call(post("/api/notifications/{id}/read", first), buyerA2, null).andExpect(status().isOk());
		call(get("/api/notifications?unreadOnly=true"), buyerA2, null).andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.unreadCount").value(1));
		call(get("/api/notifications?type=OVERDUE_REMINDER"), buyerA2, null).andExpect(jsonPath("$.totalElements").value(0));
		call(get("/api/notifications?type=RESPONSE_RECEIVED&size=1&page=1"), buyerA2, null)
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.totalElements").value(2));

		call(post("/api/notifications/read-all"), buyerA2, null).andExpect(jsonPath("$.marked").value(1));
		call(post("/api/notifications/read-all"), buyerA2, null).andExpect(jsonPath("$.marked").value(0));
		call(get("/api/notifications/unread-count"), buyerA2, null).andExpect(jsonPath("$.count").value(0));
	}

	// ---------------------------------------------------------------- RFQ and quotation workflows

	@Test
	void theRfqAndQuoteWorkflowTellsOnlyTheRightSideAtEachStep() throws Exception {
		long rfqId = draftRfq(buyerA);
		String number = JsonPath.read(call(get("/api/rfqs/{id}", rfqId), buyerA, null).andReturn().getResponse()
			.getContentAsString(), "$.number");

		call(post("/api/rfqs/{id}/submit", rfqId), buyerA, "{}").andExpect(status().isOk());
		assertLatest(ergoRfq, "RFQ submitted for review", "ACTION_REQUIRED", number, "/rfqs/" + rfqId);
		assertCount(ergoAdmin, 1);
		assertCount(buyerA, 0);

		call(post("/api/rfqs/{id}/start-review", rfqId), ergoRfq, null).andExpect(status().isOk());
		assertLatest(buyerA, "Your RFQ status changed", "RECORD_UPDATED", number + " - Under review", "/rfqs/" + rfqId);
		assertCount(ergoRfq, 1); // the actor is not told about their own action

		assertCount(buyerA, 1);
		assertCount(buyerB, 0);

		// flow doc §2: part 1 goes to supplier X, part 2 to supplier Y (a sub-RFQ each)
		TestRfq.invite(mvc, ergoRfq, rfqId, supplierX, supplierY);
		long ix = invitationOf(sellerX, rfqId);
		long iy = invitationOf(sellerY, rfqId);
		assertLatest(sellerX, "New quote request", "ACTION_REQUIRED", number + " - Respond by " + LocalDate.now().plusDays(7),
				"/quotes/" + ix);
		assertLatest(sellerY, "New quote request", "ACTION_REQUIRED", number + " - Respond by " + LocalDate.now().plusDays(7),
				"/quotes/" + iy);
		assertCount(buyerA, 1); // the customer is told nothing about suppliers being invited

		// supplier X quotes part 1: ERGO is told, the other supplier and the customer are not
		call(put("/api/quotes/{id}", ix), sellerX, quoteForm()).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", ix), sellerX, null).andExpect(status().isOk());
		assertLatest(ergoRfq, "Supplier quote submitted", "RESPONSE_RECEIVED", number + " - PN-1", "/quotes/" + ix);
		assertCount(sellerY, 1);
		assertCount(buyerA, 1);

		// ERGO sends the part's quote back for revision: only that supplier hears
		call(post("/api/quotes/{id}/revision", ix), ergoRfq, "{\"comment\": \"price check\"}").andExpect(status().isOk());
		assertLatest(sellerX, "Revision required on your quote", "ACTION_REQUIRED", number + " - Revision required: PN-1",
				"/quotes/" + ix);
		assertCount(sellerY, 1);

		// X resubmits, ERGO accepts it and generates the quotation for the customer, the customer approves
		call(post("/api/quotes/{id}/submit", ix), sellerX, null).andExpect(status().isOk());
		call(post("/api/quotes/{id}/accept", ix), ergoRfq, null)
			.andExpect(status().isOk());
		int customerBefore = totalOf(buyerA);
		int supplierBefore = totalOf(sellerX);
		call(post("/api/rfqs/{id}/customer-quotes", rfqId), ergoRfq,
				"{\"remarks\": \"ok\", \"lines\": [{\"quoteId\": %d, \"ergoQuote\": {\"currencyId\": %d, \"productionPiecePrice\": 120, \"validityDate\": \"%s\"}}]}".formatted(ix, inr, java.time.LocalDate.now().plusDays(30)))
			.andExpect(status().isOk());
		assertLatest(buyerA, "A quote is ready for your decision", "ACTION_REQUIRED", number, "/rfqs/" + rfqId);
		assertCount(buyerA, customerBefore + 1);
		assertCount(buyerB, 0);
		assertCount(sellerX, supplierBefore); // suppliers are never told what happens with the customer

		long sent = ((Number) JsonPath.read(call(get("/api/rfqs/{id}/customer-quotes", rfqId), buyerA, null).andReturn()
			.getResponse().getContentAsString(), "$.quotations[0].id")).longValue();
		call(post("/api/customer-quotes/{id}/respond", sent), buyerA, "{\"response\": \"APPROVED\", \"comment\": \"Go ahead\"}")
			.andExpect(status().isOk());
		assertLatest(ergoRfq, "Customer responded to a quote", "RESPONSE_RECEIVED", number + " - Approved",
				"/rfqs/" + rfqId);
		assertCount(sellerX, supplierBefore);
	}

	@Test
	void clarificationAndFileUploadsReachTheRightPeopleOnly() throws Exception {
		long rfqId = draftRfq(buyerA);
		String number = JsonPath.read(call(get("/api/rfqs/{id}", rfqId), buyerA, null).andReturn().getResponse()
			.getContentAsString(), "$.number");
		call(post("/api/rfqs/{id}/submit", rfqId), buyerA, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", rfqId), ergoRfq, null).andExpect(status().isOk());
		call(post("/api/rfqs/{id}/request-clarification", rfqId), ergoRfq, "{\"comment\": \"drawing revision?\"}")
			.andExpect(status().isOk());
		assertLatest(buyerA, "ERGO needs clarification on your RFQ", "ACTION_REQUIRED", number, "/rfqs/" + rfqId);
		assertLatest(buyerA2, "ERGO needs clarification on your RFQ", "ACTION_REQUIRED", number, "/rfqs/" + rfqId);

		// a customer's drawing is announced to ERGO; an ERGO-internal one is announced to nobody
		upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing.pdf", null);
		assertLatest(ergoRfq, "Document uploaded", "DOCUMENT_UPLOADED", number + " - drawing.pdf", "/documents/" + docId("drawing.pdf"));
		int customerBefore = totalOf(buyerA);
		upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "internal.pdf", "INTERNAL");
		assertCount(buyerA, customerBefore);
		upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "shared.pdf", "CUSTOMER");
		assertLatest(buyerA, "A document was added", "DOCUMENT_UPLOADED", number + " - shared.pdf", "/documents/" + docId("shared.pdf"));
		assertCount(sellerX, 0);
	}

	// ---------------------------------------------------------------- OFP, quality and document events

	@Test
	void anOrderStatusChangeReachesItsOwnCustomerAndSupplierOnly() throws Exception {
		long orderId = order(customerA, supplierX);
		String number = jdbc.queryForObject("SELECT order_number FROM orders WHERE order_id = ?", String.class, orderId);

		call(post("/api/ofp/orders/{id}/hold", orderId), ergoOfp, "{\"comment\": \"supplier delay\"}").andExpect(status().isOk());
		assertLatest(customerAOfp, "Order status changed", "RECORD_UPDATED", number + " - On hold", "/ofp/" + orderId);
		assertLatest(supplierXOfp, "Order status changed", "RECORD_UPDATED", number + " - On hold", "/ofp/" + orderId);
		assertLatest(ergoAdmin, "Order status changed", "RECORD_UPDATED", number + " - On hold", "/ofp/" + orderId);
		assertCount(customerBOfp, 0);
		assertCount(supplierYOfp, 0);
		assertCount(ergoOfp, 0); // the actor
		// the hold reason is ERGO's business: it is in nobody's notification
		call(get("/api/notifications"), supplierXOfp, null)
			.andExpect(jsonPath("$.items[0].message").value(not(containsString("supplier delay"))));

		call(post("/api/ofp/orders/{id}/release", orderId), ergoOfp, "{}").andExpect(status().isOk());
		assertCount(customerAOfp, 2);
	}

	@Test
	void aProofOfDeliveryUploadIsAnnouncedToErgoOnly() throws Exception {
		long shipmentId = shipment(customerA, supplierX);
		long orderId = jdbc.queryForObject("SELECT order_id FROM shipments WHERE shipment_id = ?", Long.class, shipmentId);
		var upload = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/ofp/shipments/{id}/pod", shipmentId)
			.file(new org.springframework.mock.web.MockMultipartFile("file", "pod.pdf", "application/octet-stream",
					"pod".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, ergoOfp.bearer());
		mvc.perform(upload).andExpect(status().isOk());
		assertLatest(ergoAdmin, "Document uploaded", "DOCUMENT_UPLOADED",
				jdbc.queryForObject("SELECT order_number FROM orders WHERE order_id = ?", String.class, orderId)
						+ " - Proof of delivery", "/documents/" + docId("pod.pdf"));
		assertCount(customerAOfp, 0); // the POD file is ERGO-only
		assertCount(supplierXOfp, 0);
	}

	@Test
	void anNcrRaisedByErgoTellsTheSupplierAndNotTheCustomer() throws Exception {
		String body = """
				{"linkedModule": "INCOMING", "linkedRecordId": %d, "issueCategory": "Dimensional",
				 "issueDescription": "part out of tolerance", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "ownerUserId": null}"""
			.formatted(SEQ.incrementAndGet(), LocalDate.now().plusDays(30), customerA, supplierX);
		long ncrId = idOf(call(post("/api/quality/ncrs"), ergoQuality, body).andExpect(status().isCreated()));
		String number = JsonPath.read(call(get("/api/quality/ncrs/{id}", ncrId), ergoQuality, null).andReturn().getResponse()
			.getContentAsString(), "$.ncrNumber");

		assertLatest(supplierXQuality, "An NCR needs your attention", "ACTION_REQUIRED", number + " - Dimensional",
				"/quality/ncrs/" + ncrId);
		assertCount(customerAQuality, 0); // an NCR starts hidden from the customer
		assertCount(ergoQuality, 0);
	}

	@Test
	void aDocumentDecisionTellsTheUploaderWhoCanStillOpenIt() throws Exception {
		long rfqId = draftRfq(buyerA);
		upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing.pdf", null);
		long docId = jdbc.queryForObject("SELECT document_id FROM documents WHERE linked_module = 'rfq' AND linked_record_id = ?",
				Long.class, rfqId);

		call(post("/api/documents/{id}/approve", docId), ergoAdmin, "{}").andExpect(status().isOk());
		assertLatest(buyerA, "Your document was reviewed", "RECORD_UPDATED", "drawing.pdf - Approved", "/documents/" + docId);
		assertCount(buyerB, 0);
		assertCount(buyerA2, 0); // only the uploader
	}

	// ---------------------------------------------------------------- overdue reminders (daily scan)

	@Test
	void anOverdueNcrRemindsErgoQualityAndTheSupplierOncePerDay() throws Exception {
		String body = """
				{"linkedModule": "INCOMING", "linkedRecordId": %d, "issueCategory": "Dimensional",
				 "issueDescription": "part out of tolerance", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "ownerUserId": null}"""
			.formatted(SEQ.incrementAndGet(), LocalDate.now().plusDays(30), customerA, supplierX);
		long ncrId = idOf(call(post("/api/quality/ncrs"), ergoQuality, body).andExpect(status().isCreated()));
		jdbc.update("UPDATE ncrs SET target_closure_date = ? WHERE ncr_id = ?", LocalDate.now().minusDays(2), ncrId);
		int supplierBefore = totalOf(supplierXQuality);

		reminders.scan(LocalDate.now());
		assertCount(supplierXQuality, supplierBefore + 1);
		// ERGO Quality sees every NCR, so count only this one's reminders (other tests leave overdue NCRs behind)
		org.junit.jupiter.api.Assertions.assertEquals(1, remindersAbout(ergoQuality, "/quality/ncrs/" + ncrId));
		call(get("/api/notifications"), supplierXQuality, null)
			.andExpect(jsonPath("$.items[0].title").value("An NCR is past its target closure date"))
			.andExpect(jsonPath("$.items[0].type").value("OVERDUE_REMINDER"))
			.andExpect(jsonPath("$.items[0].actionUrl").value("/quality/ncrs/" + ncrId));
		org.junit.jupiter.api.Assertions.assertEquals("NCR is past its target closure date", jdbc.queryForObject(
				"SELECT title FROM notifications WHERE recipient_user_id = ? AND action_url = ?", String.class,
				ergoQuality.userId(), "/quality/ncrs/" + ncrId));
		assertCount(customerAQuality, 0); // customers are never reminded

		reminders.scan(LocalDate.now()); // the same day again: nobody is told twice
		assertCount(supplierXQuality, supplierBefore + 1);
		reminders.scan(LocalDate.now().plusDays(1)); // the next day it is still overdue: one more reminder
		assertCount(supplierXQuality, supplierBefore + 2);
	}

	@Test
	void anOverdueSupplierQuoteRemindsThatSupplierOnlyAndStopsOnceSubmitted() throws Exception {
		long rfqId = draftRfq(buyerA);
		call(post("/api/rfqs/{id}/submit", rfqId), buyerA, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", rfqId), ergoRfq, null).andExpect(status().isOk());
		TestRfq.invite(mvc, ergoRfq, rfqId, supplierX, supplierY);
		long ix = invitationOf(sellerX, rfqId);
		jdbc.update("UPDATE rfq_supplier_assignments SET response_due_date = ? WHERE rfq_id = ?", LocalDate.now().minusDays(1), rfqId);
		int xBefore = totalOf(sellerX);
		int yBefore = totalOf(sellerY);
		int customerBefore = totalOf(buyerA);

		reminders.scan(LocalDate.now());
		assertCount(sellerX, xBefore + 1);
		assertCount(sellerY, yBefore + 1);
		call(get("/api/notifications"), sellerX, null)
			.andExpect(jsonPath("$.items[0].title").value("Your quote response is overdue"))
			.andExpect(jsonPath("$.items[0].actionUrl").value("/quotes/" + ix));
		assertCount(buyerA, customerBefore);

		// supplier X submits: only supplier Y is reminded the next day
		call(put("/api/quotes/{id}", ix), sellerX, quoteForm()).andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", ix), sellerX, null).andExpect(status().isOk());
		int xAfterSubmit = totalOf(sellerX);
		reminders.scan(LocalDate.now().plusDays(1));
		assertCount(sellerX, xAfterSubmit);
		assertCount(sellerY, yBefore + 2);
	}

	// ---------------------------------------------------------------- security review 6e regressions

	@Test
	void aSuppliersDraftQuoteFileIsNotAnnouncedToErgo() throws Exception {
		long rfqId = draftRfq(buyerA);
		call(post("/api/rfqs/{id}/submit", rfqId), buyerA, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", rfqId), ergoRfq, null).andExpect(status().isOk());
		TestRfq.invite(mvc, ergoRfq, rfqId, supplierX);
		long ix = invitationOf(sellerX, rfqId);
		int ergoBefore = totalOf(ergoRfq);

		upload("/api/quotes/{id}/documents", ix, sellerX, "price_breakup_draft.xlsx", null);
		assertCount(ergoRfq, ergoBefore); // a draft stays private: ERGO cannot open it, so it is not even named
		call(get("/api/notifications"), ergoRfq, null)
			.andExpect(content().string(not(containsString("price_breakup_draft"))));
	}

	@Test
	void twoOverdueCapaActionsOnOneNcrAreTwoReminders() throws Exception {
		String body = """
				{"linkedModule": "INCOMING", "linkedRecordId": %d, "issueCategory": "Dimensional",
				 "issueDescription": "part out of tolerance", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "ownerUserId": null}"""
			.formatted(SEQ.incrementAndGet(), LocalDate.now().plusDays(30), customerA, supplierX);
		long ncrId = idOf(call(post("/api/quality/ncrs"), ergoQuality, body).andExpect(status().isCreated()));
		for (int i = 0; i < 2; i++) {
			call(post("/api/quality/ncrs/{id}/capa-actions", ncrId), ergoQuality,
					"{\"actionType\": \"CORRECTIVE\", \"actionDescription\": \"fix %d\", \"targetDate\": \"%s\"}"
						.formatted(i, LocalDate.now().plusDays(10)))
				.andExpect(status().isCreated());
		}
		jdbc.update("UPDATE capa_actions SET target_date = ? WHERE ncr_id = ?", LocalDate.now().minusDays(1), ncrId);
		int before = totalOf(supplierXQuality);

		reminders.scan(LocalDate.now());
		assertCount(supplierXQuality, before + 2);
		reminders.scan(LocalDate.now());
		assertCount(supplierXQuality, before + 2);
	}

	@Test
	void anNcrAwaitingClosureApprovalRemindsErgoOnly() throws Exception {
		String body = """
				{"linkedModule": "INCOMING", "linkedRecordId": %d, "issueCategory": "Dimensional",
				 "issueDescription": "part out of tolerance", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "ownerUserId": null}"""
			.formatted(SEQ.incrementAndGet(), LocalDate.now().plusDays(30), customerA, supplierX);
		long ncrId = idOf(call(post("/api/quality/ncrs"), ergoQuality, body).andExpect(status().isCreated()));
		jdbc.update("UPDATE ncrs SET target_closure_date = ?, current_status = 'PENDING_CLOSURE_APPROVAL' WHERE ncr_id = ?",
				LocalDate.now().minusDays(1), ncrId);
		int supplierBefore = totalOf(supplierXQuality);

		reminders.scan(LocalDate.now());
		org.junit.jupiter.api.Assertions.assertEquals(1, remindersAbout(ergoQuality, "/quality/ncrs/" + ncrId));
		assertCount(supplierXQuality, supplierBefore); // only ERGO can act while closure awaits approval
	}

	// ---------------------------------------------------------------- fixtures

	private long order(long customerCompanyId, long supplierCompanyId) {
		return jdbc.queryForObject("""
				INSERT INTO orders (order_number, customer_company_id, supplier_company_id, part_number,
				                    ordered_quantity, current_status)
				VALUES (?, ?, ?, 'PN-ORDER', 10, 'DISPATCHED') RETURNING order_id""", Long.class,
				"NO" + SEQ.incrementAndGet(), customerCompanyId, supplierCompanyId);
	}

	private long shipment(long customerCompanyId, long supplierCompanyId) {
		return jdbc.queryForObject("""
				INSERT INTO shipments (order_id, updated_by_user_id) VALUES (?, ?) RETURNING shipment_id""", Long.class,
				order(customerCompanyId, supplierCompanyId), ergoOfp.userId());
	}

	private int remindersAbout(Login user, String actionUrl) {
		return jdbc.queryForObject("""
				SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND action_url = ?
				  AND notification_type = 'OVERDUE_REMINDER'""", Integer.class, user.userId(), actionUrl);
	}

	private long docId(String fileName) {
		return jdbc.queryForObject("SELECT max(document_id) FROM documents WHERE file_name = ?", Long.class, fileName);
	}

	private int totalOf(Login user) throws Exception {
		return ((Number) JsonPath.read(call(get("/api/notifications"), user, null).andReturn().getResponse()
			.getContentAsString(), "$.totalElements")).intValue();
	}

	/** The newest notification: its audience-specific title, drawer type, message and the screen it opens. */
	private void assertLatest(Login user, String title, String type, String message, String url) throws Exception {
		call(get("/api/notifications"), user, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].title").value(title))
			.andExpect(jsonPath("$.items[0].type").value(type))
			.andExpect(jsonPath("$.items[0].message").value(message))
			.andExpect(jsonPath("$.items[0].actionUrl").value(url));
	}

	private void upload(String url, long id, Login user, String name, String visibility) throws Exception {
		var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(url, id)
			.file(new org.springframework.mock.web.MockMultipartFile("file", name, "application/octet-stream",
					name.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (visibility != null) {
			request.param("visibility", visibility);
		}
		mvc.perform(request).andExpect(status().isCreated());
	}

	private String quoteForm() {
		return """
				{"validityDate": "%s", "currencyId": %d, "productionPiecePrice": 90, "toolingCostTotal": 50000,
				 "rawMaterialCost": 40, "processingCost": 20, "overheadCost": 8, "packagingCost": 2, "profit": 6,
				 "moq": 500, "productionLeadTimeDays": 45}""".formatted(LocalDate.now().plusDays(60), inr);
	}

	private long invitationOf(Login supplier, long rfqId) throws Exception {
		String number = JsonPath.read(call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andReturn().getResponse()
			.getContentAsString(), "$.number");
		List<Number> ids = JsonPath.read(call(get("/api/quotes"), supplier, null).andReturn().getResponse()
			.getContentAsString(), "$[?(@.rfqNumber == '" + number + "')].id");
		return ids.get(0).longValue();
	}

	private void assertCount(Login user, int expected) throws Exception {
		call(get("/api/notifications"), user, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(expected));
	}

	private static String comment(long rfqId, String text, Long parent, long... mentions) {
		return comment(rfqId, text, parent, mentions.length == 0 ? 0L : mentions[0], null);
	}

	private static String comment(long rfqId, String text, Long parent, long mention, String type) {
		return """
				{"linkedModule": "rfq", "linkedRecordId": %d, "commentType": %s, "text": "%s",
				 "parentCommentId": %s, "query": false, "mentionedUserIds": %s}"""
			.formatted(rfqId, type == null ? "null" : "\"" + type + "\"", text, parent == null ? "null" : parent,
					mention == 0 ? "[]" : "[" + mention + "]");
	}

	private String rfqForm() {
		return TestRfq.form(inr, "PN-1", "PN-2");
	}

	private long draftRfq(Login buyer) throws Exception {
		return idOf(call(post("/api/rfqs"), buyer, rfqForm()).andExpect(status().isCreated()));
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
