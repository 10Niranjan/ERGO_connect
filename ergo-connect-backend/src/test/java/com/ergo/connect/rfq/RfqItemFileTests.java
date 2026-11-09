package com.ergo.connect.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Per-part attachments of the client's RFQ table: four slots, each with its own file types, several files per
 * request, visible to ERGO and the customer only, and only while the customer may still change the RFQ.
 */
@IntegrationTest
class RfqItemFileTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long inr;

	Login ergoRfq;

	Login buyer;

	Login otherBuyer;

	long rfq;

	long part;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		inr = users.currency("INR");
		ergoRfq = users.login(users.company("ERGO"), "ERGO_RFQ");
		buyer = users.login(users.company("CUSTOMER"), "CUSTOMER_RFQ");
		otherBuyer = users.login(users.company("CUSTOMER"), "CUSTOMER_RFQ");
		rfq = TestRfq.create(mvc, buyer, TestRfq.form(inr, "PN-1", "PN-2"));
		part = TestRfq.itemIds(mvc, ergoRfq, rfq).getFirst();
	}

	@Test
	void severalFilesGoIntoOneSlotAndShowUnderTheirPartForErgoAndTheCustomerOnly() throws Exception {
		upload(buyer, rfq, part, "DRAWING_2D", "a.pdf", "B.DOCX").andExpect(status().isCreated())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[0].kind").value("DRAWING_2D"))
			.andExpect(jsonPath("$[0].canDelete").value(true));
		upload(buyer, rfq, part, "MODEL_3D", "m.step", "m2.zip").andExpect(status().isCreated());
		upload(buyer, rfq, part, "SPECIFICATION", "s.pdf").andExpect(status().isCreated());
		upload(buyer, rfq, part, "ADDITIONAL", "p.JPG", "x.xlsx").andExpect(status().isCreated());

		for (Login who : List.of(buyer, ergoRfq)) {
			call(get("/api/rfqs/{id}", rfq), who, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].files", hasSize(7)))
				.andExpect(jsonPath("$.items[0].files[?(@.kind == 'MODEL_3D')].fileName", hasItem("m.step")))
				.andExpect(jsonPath("$.items[0].files[?(@.kind == 'ADDITIONAL')].fileName", hasItem("p.JPG")))
				.andExpect(jsonPath("$.items[1].files", hasSize(0)));
		}
		// stored as the customer's files, shared with nobody else
		assertThat(jdbc.queryForList("SELECT DISTINCT visibility FROM documents d JOIN rfq_item_files f USING (document_id)",
				String.class)).containsOnly("CUSTOMER");

		// another customer does not see the RFQ, its files or their content
		call(get("/api/rfqs/{id}", rfq), otherBuyer, null).andExpect(status().isNotFound());
		long doc = jdbc.queryForObject("SELECT document_id FROM rfq_item_files WHERE rfq_item_id = ? LIMIT 1", Long.class, part);
		call(get("/api/rfqs/{id}/documents/{d}/content", rfq, doc), otherBuyer, null).andExpect(status().isNotFound());
		call(get("/api/rfqs/{id}/documents/{d}/content", rfq, doc), buyer, null).andExpect(status().isOk())
			.andExpect(content().bytes("a.pdf content".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void eachSlotAcceptsOnlyItsOwnTypesAndABadFileStoresNothingOfTheBatch() throws Exception {
		long before = fileCount();
		upload(buyer, rfq, part, "DRAWING_2D", "model.step").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail", containsString("PDF, IGS, IGES, DOC, DOCX")));
		upload(buyer, rfq, part, "MODEL_3D", "drawing.pdf").andExpect(status().isUnprocessableContent());
		upload(buyer, rfq, part, "SPECIFICATION", "photo.jpg").andExpect(status().isUnprocessableContent());
		upload(buyer, rfq, part, "ADDITIONAL", "model.step").andExpect(status().isUnprocessableContent());
		upload(buyer, rfq, part, "ADDITIONAL", "noextension").andExpect(status().isUnprocessableContent());
		upload(buyer, rfq, part, "ADDITIONAL", "trailingdot.").andExpect(status().isUnprocessableContent());
		upload(buyer, rfq, part, "ADDITIONAL", "run.exe").andExpect(status().isUnprocessableContent()); // not allowed anywhere
		// one good and one bad file: the good one is not kept either
		upload(buyer, rfq, part, "DRAWING_2D", "good.pdf", "bad.step").andExpect(status().isUnprocessableContent());
		assertThat(fileCount()).isEqualTo(before);
		assertThat(count("SELECT count(*) FROM documents WHERE linked_module = 'rfq' AND linked_record_id = ?", rfq)).isZero();

		upload(buyer, rfq, part, "NOPE", "a.pdf").andExpect(status().isBadRequest()); // not a slot
		// an empty batch, and too many files at once
		mvc.perform(multipart("/api/rfqs/{id}/items/{i}/files", rfq, part).param("kind", "DRAWING_2D")
			.header(HttpHeaders.AUTHORIZATION, buyer.bearer())).andExpect(status().isBadRequest());
		String[] twentyOne = new String[21];
		java.util.Arrays.setAll(twentyOne, i -> "f" + i + ".pdf");
		upload(buyer, rfq, part, "DRAWING_2D", twentyOne).andExpect(status().isUnprocessableContent());
		assertThat(fileCount()).isEqualTo(before);
	}

	@Test
	void aFileGoesOnlyToAPartOfThatRfqAndOnlyForSomeoneWhoCanSeeIt() throws Exception {
		long before = fileCount();
		long otherRfq = TestRfq.create(mvc, otherBuyer, TestRfq.form(inr, "PN-X"));
		long foreignPart = TestRfq.itemIds(mvc, ergoRfq, otherRfq).getFirst();
		upload(buyer, rfq, foreignPart, "DRAWING_2D", "a.pdf").andExpect(status().isNotFound()); // another RFQ's part
		upload(buyer, otherRfq, foreignPart, "DRAWING_2D", "a.pdf").andExpect(status().isNotFound()); // another company's RFQ
		upload(buyer, rfq, 999_999_999L, "DRAWING_2D", "a.pdf").andExpect(status().isNotFound());
		assertThat(fileCount()).isEqualTo(before);
		// the rules endpoint is for any viewer and lists the four slots
		call(get("/api/rfqs/attachment-rules"), otherBuyer, null).andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(4)))
			.andExpect(jsonPath("$[?(@.kind == 'MODEL_3D')].extensions[0]").value("step"))
			.andExpect(jsonPath("$[0].maxBytes").value(50 * 1024 * 1024));
		mvc.perform(get("/api/rfqs/attachment-rules")).andExpect(status().isUnauthorized());
	}

	@Test
	void theCustomerAttachesAndRemovesFilesOnlyInDraftOrClarification() throws Exception {
		long doc = JsonPath.<Number>read(upload(buyer, rfq, part, "DRAWING_2D", "a.pdf").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$[0].id").longValue();
		call(post("/api/rfqs/{id}/submit", rfq), buyer, "{}").andExpect(status().isOk());

		upload(buyer, rfq, part, "DRAWING_2D", "b.pdf").andExpect(status().isConflict());
		call(delete("/api/rfqs/{id}/documents/{d}", rfq, doc), buyer, null).andExpect(status().isConflict());
		call(get("/api/rfqs/{id}", rfq), buyer, null).andExpect(jsonPath("$.items[0].files[0].canDelete").value(false));
		// ERGO may still add a file while reviewing
		call(post("/api/rfqs/{id}/start-review", rfq), ergoRfq, null).andExpect(status().isOk());
		upload(ergoRfq, rfq, part, "SPECIFICATION", "ergo-spec.pdf").andExpect(status().isCreated());
		upload(buyer, rfq, part, "DRAWING_2D", "b.pdf").andExpect(status().isConflict());

		call(post("/api/rfqs/{id}/request-clarification", rfq), ergoRfq, "{\"comment\": \"Newer drawing?\"}").andExpect(status().isOk());
		upload(buyer, rfq, part, "DRAWING_2D", "b.pdf").andExpect(status().isCreated());
		call(delete("/api/rfqs/{id}/documents/{d}", rfq, doc), buyer, null).andExpect(status().isNoContent());
		// the slot link goes with the file
		assertThat(count("SELECT count(*) FROM rfq_item_files WHERE document_id = ?", doc)).isZero();
		// a file ERGO puts in a slot is ERGO's until it shares it: the customer neither sees nor can remove it
		long ergoDoc = jdbc.queryForObject("SELECT d.document_id FROM documents d JOIN rfq_item_files f USING (document_id) "
				+ "WHERE d.file_name = 'ergo-spec.pdf'", Long.class);
		assertThat(jdbc.queryForObject("SELECT visibility FROM documents WHERE document_id = ?", String.class, ergoDoc))
			.isEqualTo("INTERNAL");
		call(get("/api/rfqs/{id}", rfq), buyer, null).andExpect(jsonPath("$.items[0].files[*].fileName", org.hamcrest.Matchers.not(hasItem("ergo-spec.pdf"))));
		call(get("/api/rfqs/{id}", rfq), ergoRfq, null).andExpect(jsonPath("$.items[0].files[*].fileName", hasItem("ergo-spec.pdf")));
		call(delete("/api/rfqs/{id}/documents/{d}", rfq, ergoDoc), buyer, null).andExpect(status().isNotFound());
		call(get("/api/rfqs/{id}/documents/{d}/content", rfq, ergoDoc), buyer, null).andExpect(status().isNotFound());
	}

	@Test
	void removingAPartRemovesItsFilesAndKeepsTheOthers() throws Exception {
		long second = TestRfq.itemIds(mvc, ergoRfq, rfq).get(1);
		upload(buyer, rfq, part, "DRAWING_2D", "one.pdf").andExpect(status().isCreated());
		upload(buyer, rfq, second, "DRAWING_2D", "two.pdf").andExpect(status().isCreated());
		assertThat(count("SELECT count(*) FROM documents WHERE linked_module = 'rfq' AND linked_record_id = ?", rfq)).isEqualTo(2);

		String keepSecond = """
				{"title": "T", "currencyId": %d, "items": [{"id": %d, "partNumber": "PN-2", "eau": 10}]}""".formatted(inr, second);
		call(put("/api/rfqs/{id}", rfq), buyer, keepSecond).andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].files[0].fileName").value("two.pdf"));
		assertThat(count("SELECT count(*) FROM documents WHERE linked_module = 'rfq' AND linked_record_id = ?", rfq)).isEqualTo(1);
		call(get("/api/rfqs/{id}/documents", rfq), buyer, null).andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].fileName").value("two.pdf"));
		// a file in a slot also appears in the RFQ's document list, so ERGO can share it with suppliers per part
		long doc = jdbc.queryForObject("SELECT document_id FROM rfq_item_files WHERE rfq_item_id = ?", Long.class, second);
		call(put("/api/rfqs/{id}/documents/{d}/visibility", rfq, doc), ergoRfq,
				"{\"visibility\": \"ALL\", \"itemIds\": [%d]}".formatted(second)).andExpect(status().isOk());
		call(put("/api/rfqs/{id}/documents/{d}/visibility", rfq, doc), buyer,
				"{\"visibility\": \"ALL\", \"itemIds\": [%d]}".formatted(second)).andExpect(status().isForbidden());
	}

	@Test
	void aNewVersionFromTheLibraryTakesOverTheSlotAndAPartWithLockedFilesCanStillBeRemoved() throws Exception {
		long second = TestRfq.itemIds(mvc, ergoRfq, rfq).get(1);
		long doc = JsonPath.<Number>read(upload(buyer, rfq, part, "DRAWING_2D", "v1.pdf").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$[0].id").longValue();
		upload(buyer, rfq, second, "DRAWING_2D", "keep.pdf").andExpect(status().isCreated());
		long locked = JsonPath.<Number>read(upload(buyer, rfq, second, "SPECIFICATION", "locked.pdf").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$[0].id").longValue();

		// the customer replaces v1 in the Document Library: the slot shows the new version only, and it cannot be erased
		mvc.perform(multipart("/api/documents/{id}/replace", doc)
			.file(new MockMultipartFile("file", "v2.pdf", "application/pdf", "v2".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, buyer.bearer())).andExpect(status().isOk());
		call(get("/api/rfqs/{id}", rfq), buyer, null)
			.andExpect(jsonPath("$.items[0].files", hasSize(1)))
			.andExpect(jsonPath("$.items[0].files[0].fileName").value("v2.pdf"))
			.andExpect(jsonPath("$.items[0].files[0].kind").value("DRAWING_2D"))
			.andExpect(jsonPath("$.items[0].files[0].canDelete").value(false));
		call(get("/api/rfqs/{id}", rfq), ergoRfq, null).andExpect(jsonPath("$.items[0].files", hasSize(1)));

		// a locked file stays in the library when its part is removed; the removal itself succeeds
		call(post("/api/documents/{id}/lock", locked), users.login(users.company("ERGO"), "ERGO_ADMIN"), "{\"comment\": \"final\"}").andExpect(status().isOk());
		call(put("/api/rfqs/{id}", rfq), buyer, "{\"title\": \"T\", \"currencyId\": %d, \"items\": [{\"partNumber\": \"PN-3\", \"eau\": 1}]}".formatted(inr))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)));
		assertThat(count("SELECT count(*) FROM documents WHERE document_id = ?", locked)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM rfq_item_files WHERE document_id = ?", locked)).isZero(); // unlinked with its part
	}

	@Test
	void twoCustomersWithTheSameInitialsGetSeparateNumberSeries() throws Exception {
		long a = users.company("CUSTOMER");
		long b = users.company("CUSTOMER");
		jdbc.update("UPDATE companies SET company_name = 'Zeta Works', rfq_prefix = NULL WHERE company_id = ?", a);
		jdbc.update("UPDATE companies SET company_name = 'Zebra Wheels', rfq_prefix = NULL WHERE company_id = ?", b);
		Login buyerA = users.login(a, "CUSTOMER_RFQ");
		Login buyerB = users.login(b, "CUSTOMER_RFQ");
		String numberA = TestRfq.numberOf(mvc, ergoRfq, TestRfq.create(mvc, buyerA, TestRfq.form(inr, "PN-1")));
		String numberB = TestRfq.numberOf(mvc, ergoRfq, TestRfq.create(mvc, buyerB, TestRfq.form(inr, "PN-1")));
		assertThat(numberA).startsWith("ZW-");
		assertThat(numberB).startsWith("ZW2-"); // same initials, so the second gets its own series
		assertThat(jdbc.queryForObject("SELECT rfq_prefix FROM companies WHERE company_id = ?", String.class, a)).isEqualTo("ZW");
		// the stored prefix is stable
		assertThat(TestRfq.numberOf(mvc, ergoRfq, TestRfq.create(mvc, buyerA, TestRfq.form(inr, "PN-2")))).startsWith("ZW-");
	}

	// ---------------------------------------------------------------- helpers

	private long fileCount() {
		return count("SELECT count(*) FROM rfq_item_files", null);
	}

	private long count(String sql, Long arg) {
		return arg == null ? jdbc.queryForObject(sql, Long.class) : jdbc.queryForObject(sql, Long.class, arg);
	}

	private ResultActions upload(Login user, long rfqId, long itemId, String kind, String... names) throws Exception {
		MockMultipartHttpServletRequestBuilder request = multipart("/api/rfqs/{id}/items/{i}/files", rfqId, itemId)
			.param("kind", kind);
		for (String name : names) {
			request.file(new MockMultipartFile("files", name, "application/octet-stream",
					(name.toLowerCase() + " content").getBytes(StandardCharsets.UTF_8)));
		}
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

}
