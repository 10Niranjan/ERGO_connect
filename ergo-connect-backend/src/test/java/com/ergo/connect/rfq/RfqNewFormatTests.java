package com.ergo.connect.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Year;

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
 * The client's revised RFQ format (V44): PREFIX-YEAR-SERIAL numbers, the title and the submit rules, the customer's
 * edit window (draft and clarification only), and that a customer's edit never wipes what ERGO added.
 */
@IntegrationTest
class RfqNewFormatTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long customer;

	long inr;

	Login ergoRfq;

	Login ergoAdmin;

	Login buyer;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		customer = users.company("CUSTOMER");
		inr = users.currency("INR");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		ergoAdmin = users.login(ergo, "ERGO_ADMIN");
		buyer = users.login(customer, "CUSTOMER_RFQ");
	}

	/** A prefix no other test uses (the counter table lives as long as the test database). */
	private static String freshPrefix() {
		return "Q" + Long.toString(System.nanoTime() % 46656 + 46656, 36).toUpperCase().substring(0, 3);
	}

	@Test
	void numbersReadPrefixYearSerialAndTheSerialCountsPerPrefix() throws Exception {
		String prefix = freshPrefix();
		jdbc.update("UPDATE companies SET rfq_prefix = ? WHERE company_id = ?", prefix, customer);
		String year = String.valueOf(Year.now().getValue());
		for (int serial = 1; serial <= 3; serial++) {
			long id = TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-" + serial));
			assertThat(TestRfq.numberOf(mvc, ergoRfq, id)).isEqualTo("%s-%s-%03d".formatted(prefix, year, serial));
		}
		// another customer has its own count; a clone takes the next number of the source's customer
		long other = users.company("CUSTOMER");
		String otherPrefix = freshPrefix() + "X";
		jdbc.update("UPDATE companies SET rfq_prefix = ? WHERE company_id = ?", otherPrefix, other);
		Login otherBuyer = users.login(other, "CUSTOMER_RFQ");
		long first = TestRfq.create(mvc, otherBuyer, TestRfq.form(inr, "PN-1"));
		assertThat(TestRfq.numberOf(mvc, ergoRfq, first)).isEqualTo("%s-%s-001".formatted(otherPrefix, year));
		String clone = JsonPath.read(send(post("/api/rfqs/{id}/clone", first), otherBuyer, null)
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.number");
		assertThat(clone).isEqualTo("%s-%s-002".formatted(otherPrefix, year));
	}

	@Test
	void aCompanyWithoutAPrefixGetsInitialsOfItsName() throws Exception {
		jdbc.update("UPDATE companies SET company_name = 'International Consortium', rfq_prefix = NULL WHERE company_id = ?",
				customer);
		long id = TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-1"));
		assertThat(TestRfq.numberOf(mvc, ergoRfq, id)).matches("IC-%d-\\d{3}".formatted(Year.now().getValue()));
	}

	@Test
	void onlyErgoSetsTheRfqPrefixAndItIsUnique() throws Exception {
		String prefix = freshPrefix();
		String profile = "{\"name\": \"Acme\", \"rfqPrefix\": \"%s\"}";
		// the customer's own edit cannot set it
		send(put("/api/companies/{id}", customer), buyer, profile.formatted(prefix)).andExpect(status().isOk())
			.andExpect(jsonPath("$.profile.rfqPrefix").doesNotExist());
		send(put("/api/companies/{id}", customer), ergoAdmin, profile.formatted(prefix)).andExpect(status().isOk())
			.andExpect(jsonPath("$.profile.rfqPrefix").value(prefix));
		// a customer's later edit leaves it as ERGO set it
		send(put("/api/companies/{id}", customer), buyer, "{\"name\": \"Acme Ltd\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.profile.rfqPrefix").value(prefix));
		long other = users.company("CUSTOMER");
		send(put("/api/companies/{id}", other), ergoAdmin, profile.formatted(prefix)).andExpect(status().isConflict());
		send(put("/api/companies/{id}", other), ergoAdmin, profile.formatted("lowercase")).andExpect(status().isBadRequest());
		send(put("/api/companies/{id}", other), ergoAdmin, profile.formatted("A")).andExpect(status().isBadRequest());
	}

	@Test
	void aDraftSavesWithoutTheFieldsSubmitNeedsButSubmitNamesWhatIsMissing() throws Exception {
		long id = TestRfq.create(mvc, buyer, "{\"currencyId\": %d, \"items\": [{\"partNumber\": \"PN-1\"}]}".formatted(inr));
		send(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("RFQ title")))
			.andExpect(jsonPath("$.detail", containsString("expected quote receipt date")))
			.andExpect(jsonPath("$.detail", containsString("part PN-1: annual volume (EAU)")));
		assertThat(statusOf(id)).isEqualTo("DRAFT");

		String yesterday = LocalDate.now().minusDays(1).toString();
		send(put("/api/rfqs/{id}", id), buyer, form("T", yesterday, "{\"partNumber\": \"PN-1\", \"eau\": 10}"))
			.andExpect(status().isOk()); // a draft may hold a past date; submit refuses it
		send(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("in the past")));

		send(put("/api/rfqs/{id}", id), buyer, form("T", LocalDate.now().toString(), "{\"partNumber\": \"PN-1\", \"eau\": 0}"))
			.andExpect(status().isBadRequest()); // a volume, when given, is positive
		send(put("/api/rfqs/{id}", id), buyer, form("   ", LocalDate.now().toString(), "{\"partNumber\": \"PN-1\", \"eau\": 10}"))
			.andExpect(status().isOk());
		send(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("RFQ title"))); // blank is not a title

		send(put("/api/rfqs/{id}", id), buyer,
				form("Brackets", LocalDate.now().toString(), "{\"partNumber\": \"PN-1\", \"eau\": 10, \"remarks\": \"Powder coat\"}"))
			.andExpect(status().isOk());
		send(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk()) // today is allowed
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.title").value("Brackets"))
			.andExpect(jsonPath("$.items[0].remarks").value("Powder coat"));
	}

	@Test
	void aCustomerEditsOrAttachesFilesOnlyInDraftOrWhenErgoAsksForAClarification() throws Exception {
		long id = TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-1"));
		upload(buyer, id).andExpect(status().isCreated());
		send(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		send(get("/api/rfqs/{id}", id), buyer, null).andExpect(jsonPath("$.actions", org.hamcrest.Matchers.not(hasItem("EDIT"))))
			.andExpect(jsonPath("$.actions", org.hamcrest.Matchers.not(hasItem("UPLOAD"))));
		send(put("/api/rfqs/{id}", id), buyer, TestRfq.form(inr, "PN-1")).andExpect(status().isConflict());
		upload(buyer, id).andExpect(status().isConflict());

		send(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		send(put("/api/rfqs/{id}", id), buyer, TestRfq.form(inr, "PN-1")).andExpect(status().isConflict());
		send(post("/api/rfqs/{id}/request-clarification", id), ergoRfq, "{\"comment\": \"Which finish?\"}")
			.andExpect(status().isOk());
		send(get("/api/rfqs/{id}", id), buyer, null).andExpect(jsonPath("$.actions", hasItem("EDIT")))
			.andExpect(jsonPath("$.actions", hasItem("UPLOAD")));
		send(put("/api/rfqs/{id}", id), buyer, TestRfq.form(inr, "PN-1")).andExpect(status().isOk());
		upload(buyer, id).andExpect(status().isCreated());
	}

	@Test
	void ergoNeverEditsACustomersRfqButKeepsItsOwnSettingsAndTheCustomerOwnsEveryPartField() throws Exception {
		long id = TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-1"));
		send(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		send(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		long part = TestRfq.itemIds(mvc, ergoRfq, id).getFirst();
		// the middle man cannot change the customer's RFQ in any status, and the customer is locked after submitting
		String edit = """
				{"title": "ERGO TITLE", "targetQuoteDate": "%s", "currencyId": %d,
				 "items": [{"id": %d, "partNumber": "PN-1", "revision": "B", "eau": 1000, "netWeightKg": 1.5}]}"""
			.formatted(LocalDate.now().plusDays(10), inr, part);
		send(put("/api/rfqs/{id}", id), ergoRfq, edit).andExpect(status().isConflict());
		send(put("/api/rfqs/{id}", id), buyer, edit).andExpect(status().isConflict());
		send(get("/api/rfqs/{id}", id), ergoRfq, null).andExpect(jsonPath("$.actions", not(hasItem("EDIT"))))
			.andExpect(jsonPath("$.actions", not(hasItem("SUBMIT")))).andExpect(jsonPath("$.actions", hasItem("EDIT_NOTES")));
		// ERGO's own settings are saved from the notes endpoint; a null priority or payment term leaves the old value
		long term = users.masterEntry("payment_terms", "payment_term");
		send(put("/api/rfqs/{id}/internal-notes", id), ergoRfq, "{\"internalNotes\": \"margin\", \"priority\": \"HIGH\", \"paymentTermId\": %d}".formatted(term))
			.andExpect(status().isOk());
		send(put("/api/rfqs/{id}/internal-notes", id), ergoRfq, "{\"internalNotes\": \"margin 2\"}").andExpect(status().isOk());
		send(put("/api/rfqs/{id}/internal-notes", id), ergoRfq, "{\"priority\": \"HIGH\"}").andExpect(status().isOk()); // notes untouched
		send(post("/api/rfqs/{id}/clone", id), ergoRfq, null).andExpect(status().isConflict()); // ERGO never copies a customer's RFQ
		send(put("/api/rfqs/{id}/internal-notes", id), ergoRfq, "{\"priority\": \"URGENT!!\"}").andExpect(status().isBadRequest());
		send(put("/api/rfqs/{id}/internal-notes", id), ergoRfq, "{\"paymentTermId\": 987654321}").andExpect(status().isUnprocessableContent());
		send(post("/api/rfqs/{id}/request-clarification", id), ergoRfq, "{\"comment\": \"Which finish?\"}").andExpect(status().isOk());

		// the customer answers with every part field: revision, weights, material, targets, lead times
		send(put("/api/rfqs/{id}", id), buyer, """
				{"title": "New title", "targetQuoteDate": "%s", "currencyId": %d,
				 "items": [{"id": %d, "partNumber": "PN-1", "revision": "B", "description": "Bracket v2", "eau": 2500,
				 "netWeightKg": 1.5, "grossWeightKg": 1.8, "materialSpec": "EN8", "coatingSpec": "Zinc", "moq": 500,
				 "targetToolingCost": 5000, "targetPpapPiecePrice": 120, "targetProductionPiecePrice": 80,
				 "materialLeadTimeWeeks": 3, "productionLeadTimeDays": 40, "remarks": "Zinc plate"}]}"""
			.formatted(LocalDate.now().plusDays(12), inr, part)).andExpect(status().isOk());

		send(get("/api/rfqs/{id}", id), ergoRfq, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("New title"))
			.andExpect(jsonPath("$.priority").value("HIGH"))
			.andExpect(jsonPath("$.paymentTerm.id").value(term))
			.andExpect(jsonPath("$.internalNotes").value("margin 2"))
			.andExpect(jsonPath("$.items[0].revision").value("B"))
			.andExpect(jsonPath("$.items[0].description").value("Bracket v2"))
			.andExpect(jsonPath("$.items[0].eau").value(2500))
			.andExpect(jsonPath("$.items[0].netWeightKg").value(1.5))
			.andExpect(jsonPath("$.items[0].materialSpec").value("EN8"))
			.andExpect(jsonPath("$.items[0].coatingSpec").value("Zinc"))
			.andExpect(jsonPath("$.items[0].moq").doesNotExist()) // moq lives under targets
			.andExpect(jsonPath("$.items[0].targets.moq").value(500))
			.andExpect(jsonPath("$.items[0].targets.targetToolingCost").value(5000))
			.andExpect(jsonPath("$.items[0].targets.targetProductionPiecePrice").value(80))
			.andExpect(jsonPath("$.items[0].targets.productionLeadTimeDays").value(40))
			.andExpect(jsonPath("$.items[0].remarks").value("Zinc plate"));
	}

	@Test
	void theCustomerSeesItsOwnPartDataButNeverErgosSettingsOrAnyProcessFlow() throws Exception {
		long id = TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-1"));
		send(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		send(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		long term = users.masterEntry("payment_terms", "payment_term");
		send(put("/api/rfqs/{id}/internal-notes", id), ergoRfq, "{\"internalNotes\": \"SECRET-MARGIN\", \"priority\": \"URGENT\", \"paymentTermId\": %d}".formatted(term))
			.andExpect(status().isOk());

		String json = send(get("/api/rfqs/{id}", id), buyer, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].targets.targetProductionPiecePrice").value(99.5))
			.andExpect(jsonPath("$.items[0].description").value("Bracket"))
			.andExpect(jsonPath("$.items[0].materialSpec").value("EN8"))
			.andReturn().getResponse().getContentAsString();
		assertThat(json).doesNotContain("processSteps", "priority", "paymentTerm", "internalNotes", "URGENT", "SECRET-MARGIN");
		String ergoJson = send(get("/api/rfqs/{id}", id), ergoRfq, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(ergoJson).doesNotContain("processSteps"); // old routes stay stored but nothing shows them
		String list = send(get("/api/rfqs"), buyer, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(list).doesNotContain("priority", "URGENT");
	}

	@Test
	void aCustomerCannotSetPriorityOrPaymentTerm() throws Exception {
		long term = users.masterEntry("payment_terms", "payment_term");
		String create = """
				{"title": "T", "currencyId": %d, "priority": "URGENT", "paymentTermId": %d,
				 "items": [{"partNumber": "PN-1", "eau": 5}]}""".formatted(inr, term);
		long id = JsonPath.<Number>read(send(post("/api/rfqs"), buyer, create).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id").longValue();
		send(get("/api/rfqs/{id}", id), ergoRfq, null).andExpect(jsonPath("$.priority").value("NORMAL"))
			.andExpect(jsonPath("$.paymentTerm").doesNotExist());
		send(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isUnprocessableContent()); // no date yet
		long part = TestRfq.itemIds(mvc, ergoRfq, id).getFirst();
		String withDate = """
				{"title": "T", "targetQuoteDate": "%s", "currencyId": %d, "priority": "URGENT", "paymentTermId": %d,
				 "items": [{"id": %d, "partNumber": "PN-1", "eau": 5}]}""".formatted(LocalDate.now().plusDays(5), inr, term, part);
		send(put("/api/rfqs/{id}", id), buyer, withDate).andExpect(status().isOk());
		send(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		send(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		send(put("/api/rfqs/{id}/internal-notes", id), ergoRfq, "{\"priority\": \"HIGH\", \"paymentTermId\": %d}".formatted(term)).andExpect(status().isOk());
		// neither the customer's notes call nor a later edit can touch them
		send(put("/api/rfqs/{id}/internal-notes", id), buyer, "{\"priority\": \"LOW\"}").andExpect(status().isForbidden());
		send(post("/api/rfqs/{id}/request-clarification", id), ergoRfq, "{\"comment\": \"Check\"}").andExpect(status().isOk());
		send(put("/api/rfqs/{id}", id), buyer, withDate.replace("\"eau\": 5", "\"eau\": 9")).andExpect(status().isOk());
		send(get("/api/rfqs/{id}", id), ergoRfq, null).andExpect(jsonPath("$.priority").value("HIGH"))
			.andExpect(jsonPath("$.paymentTerm.id").value(term))
			.andExpect(jsonPath("$.items[0].eau").value(9));
	}

	@Test
	void ergoMayCreateAndEditItsOwnDraftOnlyAndNobodyElsesFieldsChange() throws Exception {
		long customerId = jdbc.queryForObject("SELECT customer_company_id FROM rfqs WHERE rfq_id = ?", Long.class,
				TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-0")));
		long id = TestRfq.create(mvc, ergoRfq, TestRfq.form(inr, "PN-E").replace("{\"title\"", "{\"customerCompanyId\": %d, \"title\"".formatted(customerId)));
		send(get("/api/rfqs/{id}", id), ergoRfq, null).andExpect(jsonPath("$.actions", hasItem("EDIT")))
			.andExpect(jsonPath("$.actions", hasItem("SUBMIT")));
		long part = TestRfq.itemIds(mvc, ergoRfq, id).getFirst();
		send(put("/api/rfqs/{id}", id), ergoRfq, TestRfq.form(inr, "PN-E").replace("\"PN-E\"", "\"PN-E\", \"id\": %d".formatted(part)).replace("Test RFQ", "Edited by ERGO"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Edited by ERGO"));
		send(post("/api/rfqs/{id}/submit", id), ergoRfq, "{}").andExpect(status().isOk());
		// once submitted it is read-only for ERGO too
		send(put("/api/rfqs/{id}", id), ergoRfq, TestRfq.form(inr, "PN-E")).andExpect(status().isConflict());
		send(get("/api/rfqs/{id}", id), ergoRfq, null).andExpect(jsonPath("$.actions", not(hasItem("EDIT"))));
	}

	@Test
	void theTitleIsListedSearchableAndCopiedByClone() throws Exception {
		String title = "Gearbox housings " + System.nanoTime();
		long id = TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-1").replace("Test RFQ", title));
		send(get("/api/rfqs?q=" + title.substring(4, 20).toUpperCase()), buyer, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == %d)].title".formatted(id)).value(title));
		send(get("/api/rfqs?q=no-such-title-zzz"), buyer, null).andExpect(jsonPath("$.items", hasSize(0)));
		send(get("/api/rfqs?q=" + title.substring(4, 20)), ergoRfq, null)
			.andExpect(jsonPath("$.items[?(@.id == %d)].title".formatted(id)).value(title));
		send(post("/api/rfqs/{id}/clone", id), buyer, null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value(title));
	}

	// ---------------------------------------------------------------- helpers

	private String form(String title, String date, String item) {
		return "{\"title\": \"%s\", \"targetQuoteDate\": \"%s\", \"currencyId\": %d, \"items\": [%s]}".formatted(title, date,
				inr, item);
	}

	private String statusOf(long id) {
		return jdbc.queryForObject("SELECT status FROM rfqs WHERE rfq_id = ?", String.class, id);
	}

	private ResultActions send(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private ResultActions upload(Login user, long id) throws Exception {
		return mvc.perform(multipart("/api/rfqs/{id}/documents", id)
			.file(new MockMultipartFile("file", "drawing.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

}
