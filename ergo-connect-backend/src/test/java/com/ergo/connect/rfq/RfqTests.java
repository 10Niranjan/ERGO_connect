package com.ergo.connect.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/** Phase 2a: RFQ creation, review and validation (SRS §14.2, §17.1), record scope (§10.4) and drawings. */
@IntegrationTest
class RfqTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long customerA;

	long customerB;

	long material;

	long process;

	long inr;

	Login ergoRfq;

	Login buyerA;

	Login buyerB;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		customerA = users.company("CUSTOMER");
		customerB = users.company("CUSTOMER");
		material = users.masterEntry("materials", "material");
		process = users.masterEntry("processes", "process");
		inr = users.currency("INR");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		buyerB = users.login(customerB, "CUSTOMER_RFQ");
	}

	@Test
	void aCustomerDraftCanOnlyBeSubmittedWhenComplete() throws Exception {
		long id = create(buyerA, "{\"currencyId\": %d, \"items\": [{\"partNumber\": \"PN-100\"}]}".formatted(inr))
			.andExpect(jsonPath("$.number", matchesPattern("RFQ-\\d{4}-\\d{5}")))
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.audience").value("CUSTOMER"))
			.andReturn().getResponse().getContentAsString().transform(RfqTests::idOf);

		act(buyerA, id, "submit", null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("process flow")));
		mvc.perform(json(put("/api/rfqs/{id}", id), completeForm(null, "PN-100"), buyerA)).andExpect(status().isOk());
		act(buyerA, id, "submit", null).andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.internalNotes").doesNotExist())
			.andExpect(jsonPath("$.owner").doesNotExist());
	}

	@Test
	void anRfqNeedsAtLeastOnePartAndEveryPartNeedsItsOwnData() throws Exception {
		create(buyerA, "{\"currencyId\": %d, \"items\": []}".formatted(inr)).andExpect(status().isBadRequest());
		long id = create(buyerA, ("{\"currencyId\": %d, \"targetQuoteDate\": \"%s\", \"items\": ["
				+ "{\"partNumber\": \"PN-A\", \"description\": \"Flange\", \"materialSpec\": \"EN8\", "
				+ "\"processSteps\": [{\"stepName\": \"Forging\"}]}, {\"partNumber\": \"PN-B\"}]}")
			.formatted(inr, LocalDate.now().plusDays(14))).andExpect(status().isCreated())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[*].partNumber", contains("PN-A", "PN-B")))
			.andExpect(jsonPath("$.items[*].lineNo", contains(1, 2)))
			.andReturn().getResponse().getContentAsString().transform(RfqTests::idOf);
		// the second part is incomplete: the message names it
		act(buyerA, id, "submit", null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("part PN-B")))
			.andExpect(jsonPath("$.detail", not(containsString("part PN-A"))));
		create(buyerA, "{\"currencyId\": %d, \"items\": [{\"partNumber\": \"PN-A\"}, {\"partNumber\": \"pn-a\"}]}"
			.formatted(inr)).andExpect(status().isUnprocessableContent()); // the same part twice
	}

	@Test
	void ergoReviewsAsksForClarificationAndMayChangeTheRfqBeforeDistributing() throws Exception {
		long id = submitted(buyerA);
		Login kam = users.login(users.company("ERGO"), "ERGO_KAM");
		users.assignAccounts(kam.userId(), customerA);

		act(ergoRfq, id, "start-review", null).andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
			.andExpect(jsonPath("$.owner").exists());
		act(ergoRfq, id, "request-clarification", " ").andExpect(status().isUnprocessableContent());
		act(ergoRfq, id, "request-clarification", "Which surface finish?")
			.andExpect(jsonPath("$.status").value("CLARIFICATION_REQUIRED"));
		mvc.perform(get("/api/rfqs/{id}", id).header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(jsonPath("$.clarificationQuestion").value("Which surface finish?"))
			.andExpect(jsonPath("$.actions", hasItem("SUBMIT")));

		act(buyerA, id, "submit", null).andExpect(status().isUnprocessableContent());
		act(buyerA, id, "submit", "Ra 1.6 on all faces").andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

		// flow doc §2: after the review ERGO distributes to suppliers; there is no separate validation step
		mvc.perform(post("/api/rfqs/{id}/validate", id).header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
		mvc.perform(get("/api/rfqs/{id}", id).header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer()))
			.andExpect(jsonPath("$.clarificationAnswer").value("Ra 1.6 on all faces"));
		mvc.perform(json(put("/api/rfqs/{id}", id), completeForm(null, "PN-1"), buyerA))
			.andExpect(status().isConflict()); // customers wait while ERGO reviews
		assertThat(jdbc.queryForList("""
				SELECT action_reason FROM audit_logs WHERE entity_name = 'Rfq' AND record_id = ? AND field_name = 'status'
				ORDER BY audit_log_id""", String.class, id))
			.contains("Which surface finish?", "Ra 1.6 on all faces");
	}

	@Test
	void ergoMayChangeEverythingWhileReviewingAndPartsKeepTheirIdentity() throws Exception {
		long id = create(buyerA, completeForm(null, "PN-100")).andReturn().getResponse().getContentAsString()
			.transform(RfqTests::idOf);
		act(buyerA, id, "submit", null);
		act(ergoRfq, id, "start-review", null);
		long partId = JsonPath.<Number>read(mvc.perform(get("/api/rfqs/{id}", id)
			.header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer())).andReturn().getResponse().getContentAsString(),
				"$.items[0].id").longValue();

		// ERGO changes the part number, the quantity and adds a second part; the first keeps its id
		String edit = completeForm(null, "PN-CHANGED").replace("\"eau\": 5000", "\"eau\": 7000")
			.replace("\"partNumber\": \"PN-CHANGED\"", "\"id\": %d, \"partNumber\": \"PN-CHANGED\"".formatted(partId))
			.replace("\"items\": [", "\"items\": [{\"partNumber\": \"PN-NEW\"}, ");
		mvc.perform(json(put("/api/rfqs/{id}", id), edit, ergoRfq))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[1].id").value(partId))
			.andExpect(jsonPath("$.items[1].partNumber").value("PN-CHANGED"))
			.andExpect(jsonPath("$.items[1].eau").value(7000));
		mvc.perform(json(put("/api/rfqs/{id}", id), edit.replace("\"id\": " + partId, "\"id\": 999999999"), ergoRfq))
			.andExpect(status().isUnprocessableContent()); // not a part of this RFQ
		mvc.perform(json(put("/api/rfqs/{id}", id), completeForm(null, "PN-CHANGED"), buyerA))
			.andExpect(status().isConflict()); // customers wait while ERGO reviews

		// removing a part is possible before distribution; the changes are in the RFQ's history, labelled by part
		mvc.perform(json(put("/api/rfqs/{id}", id), completeForm(null, "PN-ONLY").replace("\"partNumber\": \"PN-ONLY\"",
				"\"id\": %d, \"partNumber\": \"PN-ONLY\"".formatted(partId)), ergoRfq))
			.andExpect(jsonPath("$.items", hasSize(1)));
		mvc.perform(get("/api/rfqs/{id}/history", id).header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer()))
			.andExpect(jsonPath("$[*].field", hasItem(containsString("Part PN-ONLY"))))
			.andExpect(jsonPath("$[*].event", hasItem("PART_REMOVED")));
	}

	@Test
	void everyRoleSeesOnlyItsOwnRfqs() throws Exception {
		long rfqA = submitted(buyerA);
		long rfqB = submitted(buyerB);
		Login kam = users.login(users.company("ERGO"), "ERGO_KAM");
		users.assignAccounts(kam.userId(), customerA);
		Login ergoNpd = users.login(users.company("ERGO"), "ERGO_NPD");
		Login customerNpd = users.login(customerA, "CUSTOMER_NPD");
		Login supplier = users.login(users.company("SUPPLIER"), "SUPPLIER_RFQ");

		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, buyerB.bearer()))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) rfqA))));
		mvc.perform(get("/api/rfqs/{id}", rfqA).header(HttpHeaders.AUTHORIZATION, buyerB.bearer()))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, kam.bearer()))
			.andExpect(jsonPath("$.items[*].id", hasItem((int) rfqA)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) rfqB))));
		mvc.perform(get("/api/rfqs/{id}", rfqB).header(HttpHeaders.AUTHORIZATION, kam.bearer()))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/rfqs/{id}", rfqA).header(HttpHeaders.AUTHORIZATION, ergoNpd.bearer()))
			.andExpect(status().isNotFound()); // NPD sees approved work only
		// role sheet: Customer NPD has "V" on RFQ - its own company's RFQs, read-only
		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, customerNpd.bearer()))
			.andExpect(jsonPath("$.items[*].id", hasItem((int) rfqA)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) rfqB))));
		mvc.perform(get("/api/rfqs/{id}", rfqA).header(HttpHeaders.AUTHORIZATION, customerNpd.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", empty()));
		mvc.perform(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, customerNpd.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currencyId\": %d, \"items\": [{\"partNumber\": \"X\"}]}".formatted(inr)))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, supplier.bearer()))
			.andExpect(status().isForbidden()); // until RFQs are sent to suppliers (Phase 2b)
		mvc.perform(get("/api/rfqs").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer()))
			.andExpect(jsonPath("$.items[*].id", hasItem((int) rfqA)))
			.andExpect(jsonPath("$.items[*].customer").exists());
	}

	@Test
	void internalNotesNeverReachTheCustomer() throws Exception {
		long id = create(buyerA, completeForm(null, "PN-NOTES").replace("\"customerRemarks\"",
				"\"internalNotes\": \"sneaky\", \"customerRemarks\""))
			.andReturn().getResponse().getContentAsString().transform(RfqTests::idOf);
		mvc.perform(get("/api/rfqs/{id}", id).header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer()))
			.andExpect(jsonPath("$.internalNotes").doesNotExist()); // a customer cannot write internal notes

		mvc.perform(json(put("/api/rfqs/{id}/internal-notes", id), "{\"internalNotes\": \"Margin target 12%\"}",
				ergoRfq)).andExpect(jsonPath("$.internalNotes").value("Margin target 12%"));
		mvc.perform(get("/api/rfqs/{id}", id).header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(jsonPath("$.internalNotes").doesNotExist())
			.andExpect(content().string(not(containsString("Margin target"))));
		mvc.perform(get("/api/rfqs/{id}/history", id).header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/rfqs/{id}/history", id).header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer()))
			.andExpect(jsonPath("$[*].event", hasItem("CREATE")));
	}

	@Test
	void keyAccountManagersCreateRfqsOnlyForTheirCustomers() throws Exception {
		Login kam = users.login(users.company("ERGO"), "ERGO_KAM");
		users.assignAccounts(kam.userId(), customerA);

		create(kam, completeForm(customerB, "PN-KAM")).andExpect(status().isUnprocessableContent());
		create(kam, completeForm(customerA, "PN-KAM")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.audience").value("ERGO"))
			.andExpect(jsonPath("$.customer.id").value(customerA))
			.andExpect(jsonPath("$.sourceType").value("ERGO"));
	}

	@Test
	void cancellingNeedsAReasonAndCustomersCanOnlyWithdrawEarly() throws Exception {
		long draft = create(buyerA, completeForm(null, "PN-C1")).andReturn().getResponse().getContentAsString()
			.transform(RfqTests::idOf);
		act(buyerA, draft, "cancel", null).andExpect(status().isUnprocessableContent());
		act(buyerA, draft, "cancel", "Project dropped").andExpect(jsonPath("$.status").value("CANCELLED"));

		long reviewed = submitted(buyerA);
		act(ergoRfq, reviewed, "start-review", null);
		act(buyerA, reviewed, "cancel", "Too late").andExpect(status().isConflict());
		act(ergoRfq, reviewed, "cancel", "Duplicate of another RFQ").andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	void onlyErgoDecidesWhichFilesAreSharedAndCustomersSeeOnlyTheirs() throws Exception {
		long id = submitted(buyerA);
		long customerFile = idOf(upload(buyerA, id, "drawing.pdf", "ALL").andExpect(status().isCreated())
			.andExpect(jsonPath("$.visibility").value("CUSTOMER")) // customers cannot share with suppliers
			.andReturn().getResponse().getContentAsString());
		long internalFile = idOf(upload(ergoRfq, id, "costing.xlsx", null)
			.andExpect(jsonPath("$.visibility").value("INTERNAL")).andReturn().getResponse().getContentAsString());
		upload(buyerA, id, "virus.exe", null).andExpect(status().isUnprocessableContent());

		mvc.perform(get("/api/rfqs/{id}/documents", id).header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].fileName").value("drawing.pdf"));
		mvc.perform(get("/api/rfqs/{id}/documents/{doc}/content", id, internalFile)
			.header(HttpHeaders.AUTHORIZATION, buyerA.bearer())).andExpect(status().isNotFound());
		mvc.perform(get("/api/rfqs/{id}/documents/{doc}/content", id, customerFile)
			.header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
			.andExpect(content().bytes("drawing.pdf content".getBytes(StandardCharsets.UTF_8)));
		mvc.perform(get("/api/rfqs/{id}/documents", id).header(HttpHeaders.AUTHORIZATION, buyerB.bearer()))
			.andExpect(status().isNotFound());

		mvc.perform(json(put("/api/rfqs/{id}/documents/{doc}/visibility", id, customerFile),
				"{\"visibility\": \"ALL\"}", buyerA)).andExpect(status().isForbidden());
		// a file shared with suppliers is shared for chosen parts (flow doc: a supplier sees only its own parts)
		mvc.perform(json(put("/api/rfqs/{id}/documents/{doc}/visibility", id, customerFile),
				"{\"visibility\": \"ALL\"}", ergoRfq)).andExpect(status().isUnprocessableContent());
		long partId = com.ergo.connect.TestRfq.itemIds(mvc, ergoRfq, id).getFirst();
		mvc.perform(json(put("/api/rfqs/{id}/documents/{doc}/visibility", id, customerFile),
				"{\"visibility\": \"ALL\", \"itemIds\": [%d]}".formatted(partId), ergoRfq))
			.andExpect(jsonPath("$.visibility").value("ALL"))
			.andExpect(jsonPath("$.itemIds[0]").value(partId));

		mvc.perform(delete("/api/rfqs/{id}/documents/{doc}", id, internalFile)
			.header(HttpHeaders.AUTHORIZATION, buyerA.bearer())).andExpect(status().isNotFound());
		mvc.perform(delete("/api/rfqs/{id}/documents/{doc}", id, customerFile)
			.header(HttpHeaders.AUTHORIZATION, buyerA.bearer())).andExpect(status().isNoContent());
	}

	@Test
	void cloningCopiesTheContentIntoANewDraft() throws Exception {
		String twoParts = completeForm(null, "PN-100").replace("\"items\": [",
				"\"items\": [{\"partNumber\": \"PN-101\", \"description\": \"Cap\", \"materialSpec\": \"PA66\", "
						+ "\"processSteps\": [{\"stepName\": \"Step 1 moulding\"}]}, ");
		long id = create(buyerA, twoParts).andReturn().getResponse().getContentAsString().transform(RfqTests::idOf);
		String original = mvc.perform(get("/api/rfqs/{id}", id).header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andReturn().getResponse().getContentAsString();

		mvc.perform(post("/api/rfqs/{id}/clone", id).header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.number", not(JsonPath.<String>read(original, "$.number"))))
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[*].partNumber", contains("PN-101", "PN-100")))
			.andExpect(jsonPath("$.items[1].processSteps[*].stepName", everyItem(containsString("Step"))))
			.andExpect(jsonPath("$.items[1].processSteps", hasSize(2)));
	}

	// ---------------------------------------------------------------- helpers

	private long submitted(Login customer) throws Exception {
		long id = create(customer, completeForm(null, "PN-100")).andReturn().getResponse().getContentAsString()
			.transform(RfqTests::idOf);
		act(customer, id, "submit", null).andExpect(status().isOk());
		return id;
	}

	private String completeForm(Long customerId, String partNumber) {
		return """
				{"customerCompanyId": %s, "targetQuoteDate": "%s", "currencyId": %d, "customerRemarks": "Urgent",
				 "items": [
				  {"partNumber": "%s", "revision": "A", "description": "Flange, machined", "eau": 5000,
				   "materialId": %d, "targetProductionPiecePrice": 42.50,
				   "processSteps": [{"processId": %d, "stepName": "Step 1 forging"}, {"stepName": "Step 2 machining",
				     "sourcingType": "OUTSOURCED", "outsourcedSupplierName": "Local shop"}]}]}"""
			.formatted(customerId, LocalDate.now().plusDays(14), inr, partNumber, material, process);
	}

	private ResultActions create(Login user, String body) throws Exception {
		return mvc.perform(json(post("/api/rfqs"), body, user));
	}

	private ResultActions act(Login user, long id, String action, String comment) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/rfqs/{id}/" + action, id)
			.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (!List.of("start-review").contains(action)) {
			request.contentType(MediaType.APPLICATION_JSON)
				.content(comment == null ? "{}" : "{\"comment\": \"%s\"}".formatted(comment));
		}
		return mvc.perform(request);
	}

	private ResultActions upload(Login user, long id, String name, String visibility) throws Exception {
		var request = multipart("/api/rfqs/{id}/documents", id)
			.file(new MockMultipartFile("file", name, "application/octet-stream",
					(name + " content").getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (visibility != null) {
			request.param("visibility", visibility);
		}
		return mvc.perform(request);
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body, Login user) {
		return request.header(HttpHeaders.AUTHORIZATION, user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private static long idOf(String body) {
		return JsonPath.<Number>read(body, "$.id").longValue();
	}

}
