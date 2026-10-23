package com.ergo.connect.rfq;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 8d-bis (ARCHITECTURE.md §8): proves {@code UploadRateLimitInterceptor} is actually wired into a real
 * multipart endpoint. The interceptor itself is generic (content-type based, not path based - common/config), so
 * this one RFQ-document upload stands in for every {@code MultipartFile} endpoint in the app.
 */
@IntegrationTest
@TestPropertySource(properties = "ergo.security.upload-rate-limit.max-attempts=3")
class UploadRateLimitTest {

	private static final int LIMIT = 3;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long rfqId;

	Login buyer;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long customer = users.company("CUSTOMER");
		long material = users.masterEntry("materials", "material");
		long process = users.masterEntry("processes", "process");
		long inr = users.currency("INR");
		buyer = users.login(customer, "CUSTOMER_RFQ");

		String body = """
				{"targetQuoteDate": "%2$s", "currencyId": %3$d, "customerRemarks": "n/a",
				 "items": [{"partNumber": "PN-UP", "revision": "A", "description": "Flange, machined", "eau": 5000,
				  "materialId": %1$d, "targetProductionPiecePrice": 42.50,
				  "processSteps": [{"processId": %4$d, "stepName": "Step 1"}]}]}"""
			.formatted(material, LocalDate.now().plusDays(14), inr, process);
		rfqId = mvc
			.perform(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, buyer.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString()
			.transform(UploadRateLimitTest::idOf);
	}

	@Test
	void blocksAfterTooManyUploadsFromTheSameUser() throws Exception {
		for (int i = 0; i < LIMIT; i++) {
			upload("file" + i + ".pdf").andExpect(status().isCreated());
		}
		upload("onemore.pdf").andExpect(status().isTooManyRequests());
	}

	@Test
	void nonMultipartRequestsAreNotCountedAgainstTheUploadLimit() throws Exception {
		for (int i = 0; i < LIMIT + 5; i++) {
			mvc.perform(get("/api/rfqs/{id}", rfqId).header(HttpHeaders.AUTHORIZATION, buyer.bearer()))
				.andExpect(status().isOk());
		}
		for (int i = 0; i < LIMIT; i++) {
			upload("stillok" + i + ".pdf").andExpect(status().isCreated());
		}
	}

	private ResultActions upload(String name) throws Exception {
		var request = multipart("/api/rfqs/{id}/documents", rfqId)
			.file(new MockMultipartFile("file", name, "application/octet-stream",
					(name + " content").getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, buyer.bearer());
		return mvc.perform(request);
	}

	private static long idOf(String body) {
		return JsonPath.<Number>read(body, "$.id").longValue();
	}

}
