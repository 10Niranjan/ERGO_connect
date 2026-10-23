package com.ergo.connect.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
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
import com.ergo.connect.common.event.NotificationKind;
import com.ergo.connect.common.event.Notifier;
import com.ergo.connect.common.event.Recipients;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.AccessPolicy;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.communication.domain.OverdueReminders;
import com.ergo.connect.documents.api.DocumentStore;
import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.quality.domain.DeviationService;
import com.jayway.jsonpath.JsonPath;

/**
 * Strict module switches (docs/PERMISSIONS.md §9) on the paths that have no role check of their own on the module they
 * touch: comments, documents, notifications, the overdue reminders and the deviation-expiry job. V (read-only) refuses
 * every change from every path; NV (hidden) shows and notifies nothing; the data is kept, and switching back to O
 * restores normal operation, including the work that was paused.
 */
@IntegrationTest
class StrictSwitchSideChannelTests {

	private static final AtomicInteger SEQ = new AtomicInteger(800_000);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AccessPolicy policy;

	@Autowired
	Notifier notifier;

	@Autowired
	OverdueReminders reminders;

	@Autowired
	DeviationService deviations;

	@Autowired
	DocumentStore documentStore;

	TestUsers users;

	long ergo;

	long customer;

	long supplier;

	long inr;

	Login admin;

	Login ergoRfq;

	Login ergoQuality;

	Login buyer;

	Login seller;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		ergo = users.company("ERGO");
		customer = users.company("CUSTOMER");
		supplier = users.company("SUPPLIER");
		inr = users.currency("INR");
		admin = users.login(ergo, "ERGO_ADMIN");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		buyer = users.login(customer, "CUSTOMER_RFQ");
		seller = users.login(supplier, "SUPPLIER_RFQ");
	}

	@AfterEach
	void restoreShippedSheet() {
		SwitchTestSupport.restore(jdbc, policy);
	}

	// ---------------------------------------------------------------- comments

	@Test
	void commentsFollowTheirRecordsModule_readOnlyRefusesEveryChange_hiddenShowsNothing_backRestoresAll() throws Exception {
		long rfq = draftRfq();
		long comment = idOf(call(post("/api/comments"), buyer, commentForm(rfq, "Please confirm the tolerance")).andExpect(status().isCreated()));
		var gridBefore = SwitchTestSupport.grid(jdbc);

		cap("RFQ", "V"); // read-only: reading stays, every change is refused
		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfq), buyer, null).andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)));
		call(post("/api/comments"), buyer, commentForm(rfq, "A second one")).andExpect(status().isConflict());
		call(post("/api/comments"), ergoRfq, commentForm(rfq, "A reply from ERGO")).andExpect(status().isConflict());
		call(patch("/api/comments/{id}", comment), buyer, "{\"text\": \"edited\", \"query\": false, \"mentionedUserIds\": []}")
			.andExpect(status().isConflict());
		call(post("/api/comments/{id}/escalate", comment), ergoRfq, "{}").andExpect(status().isConflict());
		call(post("/api/comments/{id}/resolve", comment), buyer, "{}").andExpect(status().isConflict());
		mvc.perform(multipart("/api/comments/{id}/attachments", comment)
			.file(new MockMultipartFile("file", "note.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, buyer.bearer())).andExpect(status().isConflict());
		assertThat(count("SELECT count(*) FROM comments WHERE linked_module = 'rfq' AND linked_record_id = ?", rfq)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT comment_text FROM comments WHERE comment_id = ?", String.class, comment))
			.isEqualTo("Please confirm the tolerance");

		cap("RFQ", "NV"); // hidden: the record, its threads and its inbox entries do not exist for anyone
		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfq), buyer, null).andExpect(status().isNotFound());
		call(get("/api/comments/inbox"), buyer, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == %d)]".formatted(comment)).isEmpty());
		call(post("/api/comments"), buyer, commentForm(rfq, "Still nothing")).andExpect(status().isNotFound());
		call(patch("/api/comments/{id}", comment), buyer, "{\"text\": \"x\", \"query\": false, \"mentionedUserIds\": []}")
			.andExpect(status().isNotFound());

		cap("RFQ", "O"); // back: the thread is intact and works as before
		call(get("/api/comments?linkedModule=rfq&linkedRecordId=" + rfq), buyer, null).andExpect(jsonPath("$", hasSize(1)));
		call(get("/api/comments/inbox"), buyer, null).andExpect(jsonPath("$.items[?(@.id == %d)]".formatted(comment)).isNotEmpty());
		call(patch("/api/comments/{id}", comment), buyer, "{\"text\": \"edited\", \"query\": false, \"mentionedUserIds\": []}")
			.andExpect(status().isOk());
		call(post("/api/comments"), ergoRfq, commentForm(rfq, "Reply")).andExpect(status().isCreated());
		assertThat(SwitchTestSupport.grid(jdbc)).isEqualTo(gridBefore);
	}

	// ---------------------------------------------------------------- documents

	@Test
	void documentsOfAModuleFollowItsSwitch_lockApproveReplaceEditAreRefusedAtV_andHiddenAtNV() throws Exception {
		long rfq = draftRfq();
		upload("/api/rfqs/{id}/documents", rfq, buyer, "drawing.pdf");
		long doc = jdbc.queryForObject("SELECT max(document_id) FROM documents WHERE linked_module = 'rfq' AND linked_record_id = ?",
				Long.class, rfq);
		assertThat(documentStore.list("rfq", rfq)).hasSize(1);

		cap("RFQ", "V");
		call(get("/api/documents/{id}", doc), buyer, null).andExpect(status().isOk()).andExpect(jsonPath("$.actions", hasSize(0)));
		call(get("/api/documents?linkedModule=rfq"), admin, null).andExpect(jsonPath("$.items[?(@.id == %d)]".formatted(doc)).isNotEmpty());
		call(post("/api/documents/{id}/lock", doc), admin, "{}").andExpect(status().isConflict());
		call(post("/api/documents/{id}/approve", doc), admin, "{}").andExpect(status().isConflict());
		call(post("/api/documents/{id}/reject", doc), admin, "{\"reason\": \"no\"}").andExpect(status().isConflict());
		call(patch("/api/documents/{id}", doc), buyer, "{\"visibility\": \"CUSTOMER\"}").andExpect(status().isConflict());
		mvc.perform(multipart("/api/documents/{id}/replace", doc)
			.file(new MockMultipartFile("file", "drawing_v2.pdf", "application/pdf", "v2".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, buyer.bearer())).andExpect(status().isConflict());
		assertThat(jdbc.queryForObject("SELECT lock_status || '/' || approval_status FROM documents WHERE document_id = ?",
				String.class, doc)).isEqualTo("UNLOCKED/PENDING");
		assertThat(count("SELECT count(*) FROM documents WHERE linked_module = 'rfq' AND linked_record_id = ?", rfq)).isEqualTo(1);
		assertThat(documentStore.list("rfq", rfq)).hasSize(1); // reading through the store is unchanged

		cap("RFQ", "NV");
		call(get("/api/documents/{id}", doc), buyer, null).andExpect(status().isNotFound());
		call(get("/api/documents/{id}/content", doc), buyer, null).andExpect(status().isNotFound());
		call(get("/api/documents?linkedModule=rfq"), admin, null).andExpect(jsonPath("$.items[?(@.id == %d)]".formatted(doc)).isEmpty());
		call(post("/api/documents/{id}/lock", doc), admin, "{}").andExpect(status().isNotFound());
		assertThat(documentStore.list("rfq", rfq)).isEmpty(); // the module's own lookup is closed too

		cap("RFQ", "O"); // back: the file is where it was and the admin can act on it again
		call(get("/api/documents/{id}", doc), buyer, null).andExpect(status().isOk());
		call(post("/api/documents/{id}/lock", doc), admin, "{}").andExpect(status().isOk());
		call(post("/api/documents/{id}/unlock", doc), admin, "{}").andExpect(status().isOk());
		assertThat(documentStore.list("rfq", rfq)).hasSize(1);
	}

	@Test
	void theSharedDocumentWriterRefusesEveryModulesFilesWhileThatModuleIsReadOnlyOrHidden() {
		CurrentUser as = new CurrentUser(buyer.userId(), customer, CompanyType.CUSTOMER, Role.CUSTOMER_RFQ, 0L);
		Map<String, String> owners = Map.of("rfq", "RFQ", "quotation", "QUOTATION", "OFP_SHIPMENT", "OFP",
				"QUALITY_CAPA", "QUALITY");
		owners.forEach((linkedModule, module) -> {
			for (String cap : List.of("V", "NV")) {
				cap(module, cap);
				assertThatThrownBy(() -> CurrentUser.runAs(as,
						() -> documentStore.store(linkedModule, 1L, "a.pdf", "x".getBytes(StandardCharsets.UTF_8), Visibility.ALL)))
					.as("%s files while %s is %s", linkedModule, module, cap)
					.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
			}
			cap(module, "O");
		});
	}

	// ---------------------------------------------------------------- notifications

	@Test
	void notificationsOfAHiddenModuleAreKeptButInvisible_readOnlyStillDelivers_backShowsThemUnchanged() throws Exception {
		long rfq = draftRfq();
		upload("/api/rfqs/{id}/documents", rfq, buyer, "spec.pdf"); // tells ERGO RFQ about the file
		long doc = jdbc.queryForObject("SELECT max(document_id) FROM documents WHERE linked_module = 'rfq' AND linked_record_id = ?",
				Long.class, rfq);
		int base = totalOf(ergoRfq);
		notifier.send(NotificationKind.RFQ_SUBMITTED, "rfq", rfq, "RFQ-A", null, Recipients.ergo(Role.ERGO_RFQ));
		call(post("/api/documents/{id}/approve", doc), admin, "{}").andExpect(status().isOk()); // tells the uploader
		assertThat(totalOf(ergoRfq)).isEqualTo(base + 1);
		assertThat(totalOf(buyer)).isEqualTo(1);
		assertThat(moduleCodes(buyer)).containsExactly("RFQ"); // a document is filed under its record's module

		cap("RFQ", "V"); // read-only: still listed and readable, and a new one is still delivered
		notifier.send(NotificationKind.RFQ_STATUS_CHANGED, "rfq", rfq, "RFQ-A", "Validated", Recipients.ergo(Role.ERGO_RFQ));
		assertThat(totalOf(ergoRfq)).isEqualTo(base + 2);
		call(post("/api/notifications/read-all"), ergoRfq, null).andExpect(status().isOk()); // still markable
		assertThat(unreadOf(ergoRfq)).isZero();
		jdbc.update("UPDATE notifications SET read_flag = false, read_at = NULL WHERE recipient_user_id = ?", ergoRfq.userId());

		cap("RFQ", "NV"); // hidden: nothing listed, counted, markable or delivered - and the rows are kept
		assertThat(totalOf(ergoRfq)).isZero();
		assertThat(totalOf(buyer)).isZero(); // including the document notification
		assertThat(unreadOf(ergoRfq)).isZero();
		long hiddenId = jdbc.queryForObject("SELECT min(notification_id) FROM notifications WHERE recipient_user_id = ?",
				Long.class, ergoRfq.userId());
		call(post("/api/notifications/{id}/read", hiddenId), ergoRfq, null).andExpect(status().isNotFound());
		call(post("/api/notifications/read-all"), ergoRfq, null).andExpect(jsonPath("$.marked").value(0));
		notifier.send(NotificationKind.RFQ_STATUS_CHANGED, "rfq", rfq, "RFQ-A", "While hidden", Recipients.ergo(Role.ERGO_RFQ));
		assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ?", ergoRfq.userId())).isEqualTo(base + 2);
		assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND read_flag", ergoRfq.userId())).isZero();

		cap("RFQ", "O"); // back: exactly what was there, still unread
		assertThat(totalOf(ergoRfq)).isEqualTo(base + 2);
		assertThat(unreadOf(ergoRfq)).isEqualTo(base + 2);
		assertThat(totalOf(buyer)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- scheduled jobs

	@Test
	void quoteRemindersPauseWhileQuotationOrRfqIsReadOnlyOrHiddenAndResumeWhenBackOn() throws Exception {
		long rfq = validatedRfq();
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, invite(rfq, supplier)).andExpect(status().isOk());
		long quote = invitationOf(rfq);
		jdbc.update("UPDATE rfq_supplier_assignments SET response_due_date = ? WHERE rfq_id = ?", LocalDate.now().minusDays(1), rfq);

		for (String[] paused : new String[][] { { "QUOTATION", "V" }, { "QUOTATION", "NV" }, { "RFQ", "V" }, { "RFQ", "NV" } }) {
			cap(paused[0], paused[1]);
			reminders.scan(LocalDate.now());
			assertThat(remindersAbout(seller, "/quotes/" + quote)).as("%s %s", paused[0], paused[1]).isZero();
			cap(paused[0], "O");
		}
		// both back on: the next scan sends what is due, once - nothing was lost
		reminders.scan(LocalDate.now());
		assertThat(remindersAbout(seller, "/quotes/" + quote)).isEqualTo(1);
		reminders.scan(LocalDate.now());
		assertThat(remindersAbout(seller, "/quotes/" + quote)).isEqualTo(1);
	}

	@Test
	void ncrRemindersPauseWhileQualityIsReadOnlyOrHiddenAndResumeWhenBackOn() throws Exception {
		long ncr = idOf(call(post("/api/quality/ncrs"), ergoQuality, ncrForm()).andExpect(status().isCreated()));
		jdbc.update("UPDATE ncrs SET target_closure_date = ? WHERE ncr_id = ?", LocalDate.now().minusDays(2), ncr);
		Login supplierQuality = users.login(supplier, "SUPPLIER_QUALITY");

		for (String cap : List.of("V", "NV")) {
			cap("QUALITY", cap);
			reminders.scan(LocalDate.now());
			assertThat(remindersAbout(supplierQuality, "/quality/ncrs/" + ncr)).as("Quality %s", cap).isZero();
			assertThat(remindersAbout(ergoQuality, "/quality/ncrs/" + ncr)).as("Quality %s", cap).isZero();
		}
		cap("QUALITY", "O");
		reminders.scan(LocalDate.now());
		assertThat(remindersAbout(supplierQuality, "/quality/ncrs/" + ncr)).isEqualTo(1);
		assertThat(remindersAbout(ergoQuality, "/quality/ncrs/" + ncr)).isEqualTo(1);
	}

	@Test
	void switchingAModuleBackOnRunsThePausedRemindersAndExpiryAtOnce() throws Exception {
		long rfq = validatedRfq();
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, invite(rfq, supplier)).andExpect(status().isOk());
		long quote = invitationOf(rfq);
		jdbc.update("UPDATE rfq_supplier_assignments SET response_due_date = ? WHERE rfq_id = ?", LocalDate.now().minusDays(1), rfq);
		long deviation = approvedDeviationPastItsValidity();

		// the admin pauses Quality and Quotation through the real endpoint ...
		moduleSwitch("QUALITY", "V").andExpect(status().isOk());
		moduleSwitch("QUOTATION", "NV").andExpect(status().isOk());
		assertThat(remindersAbout(seller, "/quotes/" + quote)).isZero();
		assertThat(statusOfDeviation(deviation)).isEqualTo("APPROVED");

		// ... and switching them back on triggers the catch-up runs (no waiting for 02:00 / 06:00)
		moduleSwitch("QUALITY", "O").andExpect(status().isOk());
		moduleSwitch("QUOTATION", "O").andExpect(status().isOk());
		assertThat(eventually(() -> "EXPIRED".equals(statusOfDeviation(deviation)))).as("deviation expiry caught up").isTrue();
		assertThat(eventually(() -> remindersAbout(seller, "/quotes/" + quote) == 1)).as("overdue reminder caught up").isTrue();
	}

	@Test
	void deviationExpiryIsAStatusChangeSoItPausesWhileQualityIsReadOnlyOrHiddenAndCatchesUpAfterwards() throws Exception {
		long deviation = approvedDeviationPastItsValidity();

		for (String cap : List.of("V", "NV")) {
			cap("QUALITY", cap);
			deviations.expireOverdueDeviations();
			assertThat(statusOfDeviation(deviation)).as("while Quality is " + cap).isEqualTo("APPROVED");
		}
		cap("QUALITY", "A"); // A only removes final approvals: expiry is an automatic change, not an approval
		deviations.expireOverdueDeviations();
		assertThat(statusOfDeviation(deviation)).isEqualTo("EXPIRED"); // the date was selected, not a stored "due" flag
	}

	// ---------------------------------------------------------------- fixtures

	private void cap(String module, String cap) {
		SwitchTestSupport.cap(jdbc, policy, module, cap);
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private int remindersAbout(Login user, String actionUrl) {
		return count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND action_url = ? AND notification_type = 'OVERDUE_REMINDER'",
				user.userId(), actionUrl);
	}

	private int totalOf(Login user) throws Exception {
		return ((Number) JsonPath.read(call(get("/api/notifications"), user, null).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString(), "$.totalElements")).intValue();
	}

	private int unreadOf(Login user) throws Exception {
		return ((Number) JsonPath.read(call(get("/api/notifications/unread-count"), user, null).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(), "$.count")).intValue();
	}

	private List<String> moduleCodes(Login user) {
		return jdbc.queryForList("SELECT module_code FROM notifications WHERE recipient_user_id = ?", String.class,
				user.userId());
	}

	private String statusOfDeviation(long id) {
		return jdbc.queryForObject("SELECT current_status FROM deviations WHERE deviation_id = ?", String.class, id);
	}

	/** An APPROVED deviation whose validity has lapsed (set directly: the create form refuses a past date). */
	private long approvedDeviationPastItsValidity() {
		long order = jdbc.queryForObject("""
				INSERT INTO orders (order_number, customer_company_id, supplier_company_id, part_number,
				                    ordered_quantity, current_status)
				VALUES (?, ?, ?, 'PN-ORDER', 10, 'ORDER_CREATED') RETURNING order_id""", Long.class,
				"XO" + SEQ.incrementAndGet(), customer, supplier);
		long id = jdbc.queryForObject("""
				INSERT INTO deviations (deviation_number, linked_module, linked_record_id, customer_company_id,
				                        supplier_company_id, part_number, deviation_description, validity_end_date,
				                        current_status, requested_by_user_id, created_by_user_id, created_by_company_id)
				VALUES (?, 'OFP', ?, ?, ?, 'PN-DEV', 'desc', ?, 'APPROVED', ?, ?, ?) RETURNING deviation_id""", Long.class,
				"XD-" + SEQ.incrementAndGet(), order, customer, supplier, LocalDate.now().minusDays(1),
				ergoQuality.userId(), ergoQuality.userId(), ergo);
		return id;
	}

	private boolean eventually(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}
			Thread.sleep(200);
		}
		return condition.getAsBoolean();
	}

	private ResultActions moduleSwitch(String module, String level) throws Exception {
		long version = JsonPath.<Number>read(mvc.perform(get("/api/admin/permissions/ERGO")
			.header(HttpHeaders.AUTHORIZATION, admin.bearer())).andReturn().getResponse().getContentAsString(), "$.version")
			.longValue();
		return mvc.perform(put("/api/admin/permissions/ERGO/modules/" + module)
			.header(HttpHeaders.AUTHORIZATION, admin.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\": %d, \"reason\": \"test\", \"level\": \"%s\"}".formatted(version, level)));
	}

	private String ncrForm() {
		return """
				{"linkedModule": "INCOMING", "linkedRecordId": %d, "issueCategory": "Dimensional",
				 "issueDescription": "part out of tolerance", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "ownerUserId": null}"""
			.formatted(SEQ.incrementAndGet(), LocalDate.now().plusDays(30), customer, supplier);
	}

	private String commentForm(long rfq, String text) {
		return """
				{"linkedModule": "rfq", "linkedRecordId": %d, "commentType": null, "text": "%s",
				 "parentCommentId": null, "query": false, "mentionedUserIds": []}""".formatted(rfq, text);
	}

	private String rfqForm() {
		return """
				{"targetQuoteDate": "%s", "currencyId": %d, "items": [{"partNumber": "PN-SC1", "description": "Flange", "materialSpec": "EN8",
				 "targetProductionPiecePrice": 99.5,
				 "processSteps": [{"stepName": "Forging", "cycleTimeSeconds": 55, "sourcingType": "INHOUSE"},
				                  {"stepName": "Machining"}]}]}"""
			.formatted(LocalDate.now().plusDays(10), inr);
	}

	private long draftRfq() throws Exception {
		return idOf(call(post("/api/rfqs"), buyer, rfqForm()).andExpect(status().isCreated()));
	}

	private long validatedRfq() throws Exception {
		long id = draftRfq();
		call(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		return id;
	}

	private String invite(long rfqId, long supplierId) throws Exception {
		return TestRfq.wholeTo(mvc, ergoRfq, rfqId, supplierId);
	}

	private long invitationOf(long rfq) throws Exception {
		String number = JsonPath.read(call(get("/api/rfqs/{id}", rfq), ergoRfq, null).andReturn().getResponse()
			.getContentAsString(), "$.number");
		List<Number> ids = JsonPath.read(call(get("/api/quotes"), seller, null).andReturn().getResponse()
			.getContentAsString(), "$[?(@.rfqNumber == '" + number + "')].id");
		return ids.get(0).longValue();
	}

	private void upload(String url, long id, Login user, String name) throws Exception {
		mvc.perform(multipart(url, id)
			.file(new MockMultipartFile("file", name, "application/octet-stream", name.getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, user.bearer())).andExpect(status().isCreated());
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
