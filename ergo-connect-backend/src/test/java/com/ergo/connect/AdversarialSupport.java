package com.ergo.connect;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.TestUsers.Login;
import com.ergo.connect.common.security.AccessPolicy;
import com.ergo.connect.common.security.DefaultAccessSheet;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * Shared fixture for the adversarial RFQ / quotation tests: two customers, three suppliers (X and Y take part, Z is a
 * stranger), the ERGO roles, and the helpers that drive an RFQ part through the flow (flow doc §1-5). Anything that
 * changes the shared role sheet or a module switch is put back after the test (all tests share one database).
 */
@IntegrationTest
public abstract class AdversarialSupport {

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected AccessPolicy policy;

	protected TestUsers users;

	protected long ergoCo;

	protected long customer;

	protected long customerB;

	protected long supplierX;

	protected long supplierY;

	protected long supplierZ;

	protected long inr;

	protected Login ergoRfq;

	protected Login ergoMgmt;

	protected Login buyer;

	protected Login buyerB;

	protected Login sellerX;

	protected Login sellerY;

	protected Login sellerZ;

	private boolean sheetTouched;

	@BeforeEach
	void baseSetUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		ergoCo = users.company("ERGO");
		customer = users.company("CUSTOMER");
		customerB = users.company("CUSTOMER");
		supplierX = users.company("SUPPLIER");
		supplierY = users.company("SUPPLIER");
		supplierZ = users.company("SUPPLIER");
		inr = users.currency("INR");
		ergoRfq = users.login(ergoCo, "ERGO_RFQ");
		ergoMgmt = users.login(ergoCo, "ERGO_MANAGEMENT");
		buyer = users.login(customer, "CUSTOMER_RFQ");
		buyerB = users.login(customerB, "CUSTOMER_RFQ");
		sellerX = users.login(supplierX, "SUPPLIER_RFQ");
		sellerY = users.login(supplierY, "SUPPLIER_KAM");
		sellerZ = users.login(supplierZ, "SUPPLIER_RFQ");
	}

	@AfterEach
	void restoreSheet() {
		if (!sheetTouched) {
			return;
		}
		jdbc.update("UPDATE app_modules SET access_cap = 'O'");
		for (Role role : Role.values()) {
			for (Module module : Module.values()) {
				jdbc.update("UPDATE role_module_permissions SET access_level = ? WHERE role_code = ? AND module_code = ?",
						DefaultAccessSheet.code(role, module.name()), role.name(), module.name());
			}
		}
		jdbc.update("UPDATE access_config SET version = version + 1");
		policy.refresh();
		sheetTouched = false;
	}

	/** Flips one module switch straight in the database, the way another instance's save would. */
	protected void cap(String module, String cap) {
		sheetTouched = true;
		jdbc.update("UPDATE app_modules SET access_cap = ? WHERE module_code = ?", cap, module);
		jdbc.update("UPDATE access_config SET version = version + 1");
		policy.refresh();
	}

	/** Changes one cell of the role sheet. */
	protected void level(Role role, String module, String level) {
		sheetTouched = true;
		jdbc.update("UPDATE role_module_permissions SET access_level = ? WHERE role_code = ? AND module_code = ?", level,
				role.name(), module);
		jdbc.update("UPDATE access_config SET version = version + 1");
		policy.refresh();
	}

	// ---------------------------------------------------------------- requests

	protected ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	/** The HTTP status; an exception that escapes the dispatcher (no handler) counts as 500. */
	protected int send(MockHttpServletRequestBuilder request, Login user, String body) {
		try {
			return call(request, user, body).andReturn().getResponse().getStatus();
		}
		catch (Exception e) {
			Throwable root = e;
			while (root.getCause() != null) {
				root = root.getCause();
			}
			System.err.println("[unhandled 500] " + request.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI() + " -> " + root.getClass().getName() + ": " + String.valueOf(root.getMessage()).replace((char) 10, (char) 32));
			return 500;
		}
	}

	protected String text(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		return call(request, user, body).andReturn().getResponse().getContentAsString();
	}

	protected ResultActions upload(String url, long id, Login user, String name, byte[] bytes, String... params)
			throws Exception {
		var request = multipart(url, id).file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
			.header(HttpHeaders.AUTHORIZATION, user.bearer());
		for (int i = 0; i < params.length; i += 2) {
			request.param(params[i], params[i + 1]);
		}
		return mvc.perform(request);
	}

	protected ResultActions upload(String url, long id, Login user, String name) throws Exception {
		return upload(url, id, user, name, name.getBytes(StandardCharsets.UTF_8));
	}

	protected static long idOf(MvcResult result) throws Exception {
		return JsonPath.<Number>read(result.getResponse().getContentAsString(), "$.id").longValue();
	}

	/** Starts the calls together and returns each one's HTTP status. */
	@SafeVarargs
	protected final List<Integer> race(Callable<Integer>... calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.length);
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> call : calls) {
				futures.add(pool.submit(() -> {
					go.await();
					return call.call();
				}));
			}
			go.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(90, TimeUnit.SECONDS));
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	// ---------------------------------------------------------------- flow

	/** An RFQ of these parts, created and submitted by the customer and taken into review by ERGO. */
	protected long reviewedRfq(String... parts) throws Exception {
		return reviewedRfq(buyer, parts);
	}

	protected long reviewedRfq(Login owner, String... parts) throws Exception {
		long id = TestRfq.create(mvc, owner, TestRfq.form(inr, parts));
		call(post("/api/rfqs/{id}/submit", id), owner, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		return id;
	}

	protected List<Long> parts(long rfq) throws Exception {
		return TestRfq.itemIds(mvc, ergoRfq, rfq);
	}

	/** Sends parts to suppliers: pairs of (supplier id, list of part ids); expects 200. */
	protected void distribute(long rfq, Object... supplierAndParts) throws Exception {
		call(post("/api/rfqs/{id}/quotes", rfq), ergoRfq, TestRfq.distribution(supplierAndParts))
			.andExpect(status().isOk());
	}

	protected long quoteOf(Login seller, long rfq, String part) throws Exception {
		return TestRfq.quoteOf(mvc, seller, TestRfq.numberOf(mvc, ergoRfq, rfq), part);
	}

	protected long rfqOf(long quote) {
		return jdbc.queryForObject("SELECT rfq_id FROM rfq_supplier_assignments WHERE assignment_id = ?", Long.class,
				quote);
	}

	protected String statusOf(long quote) {
		return jdbc.queryForObject("SELECT status FROM rfq_supplier_assignments WHERE assignment_id = ?", String.class,
				quote);
	}

	protected String rfqStatus(long rfq) {
		return jdbc.queryForObject("SELECT status FROM rfqs WHERE rfq_id = ?", String.class, rfq);
	}

	protected int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	protected void saveQuote(long quote, Login seller, String price) throws Exception {
		call(put("/api/quotes/{id}", quote), seller, TestRfq.supplierQuote(inr, price)).andExpect(status().isOk());
	}

	protected void submitQuote(long quote, Login seller, String price) throws Exception {
		saveQuote(quote, seller, price);
		call(post("/api/quotes/{id}/submit", quote), seller, null).andExpect(status().isOk());
	}

	protected void accept(long quote) throws Exception {
		call(post("/api/quotes/{id}/accept", quote), ergoRfq, null).andExpect(status().isOk());
	}

	protected String line(long quote, String price) {
		return """
				{"quoteId": %d, "ergoQuote": {"currencyId": %d, "productionPiecePrice": %s, "validityDate": "%s"}}"""
			.formatted(quote, inr, price, LocalDate.now().plusDays(30));
	}

	protected static String generateBody(String... lines) {
		return "{\"remarks\": \"Incl. inspection\", \"lines\": [%s]}".formatted(String.join(", ", lines));
	}

	/** ERGO generates the quotation for these (accepted) quotes at 120 each; returns the quotation id. */
	protected long generate(long rfq, long... quotes) throws Exception {
		List<String> lines = new ArrayList<>();
		for (long quote : quotes) {
			lines.add(line(quote, "120"));
		}
		call(post("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, generateBody(lines.toArray(String[]::new)))
			.andExpect(status().isOk());
		return latestQuotation(rfq);
	}

	protected long latestQuotation(long rfq) throws Exception {
		return JsonPath.<Number>read(text(get("/api/rfqs/{id}/customer-quotes", rfq), ergoRfq, null),
				"$.quotations[0].id").longValue();
	}

	protected static String answer(String response, String comment) {
		return "{\"response\": \"%s\", \"comment\": \"%s\"}".formatted(response, comment);
	}

	protected void respond(long quotation, String response) throws Exception {
		call(post("/api/customer-quotes/{id}/respond", quotation), buyer, answer(response, "decision comment"))
			.andExpect(status().isOk());
	}

	/** Moves one part's quote to {@code state} through the real endpoints (the part's supplier is {@code seller}). */
	protected void driveTo(long quote, Login seller, String state) throws Exception {
		long rfq = rfqOf(quote);
		switch (state) {
			case "NOT_STARTED" -> {
			}
			case "DRAFT_SAVED" -> saveQuote(quote, seller, "90");
			case "SUBMITTED" -> submitQuote(quote, seller, "90");
			case "REVISION_REQUIRED" -> {
				submitQuote(quote, seller, "90");
				call(post("/api/quotes/{id}/revision", quote), ergoRfq, "{\"comment\": \"fix\"}").andExpect(status().isOk());
			}
			case "RESUBMITTED" -> {
				driveTo(quote, seller, "REVISION_REQUIRED");
				submitQuote(quote, seller, "85");
			}
			case "ACCEPTED" -> {
				submitQuote(quote, seller, "90");
				accept(quote);
			}
			case "WITH_CUSTOMER" -> {
				driveTo(quote, seller, "ACCEPTED");
				generate(rfq, quote);
			}
			case "CUSTOMER_ON_HOLD" -> {
				respond(generateAfter(quote, seller), "ON_HOLD");
			}
			case "CUSTOMER_CORRECTION" -> {
				respond(generateAfter(quote, seller), "REVISION_REQUESTED");
			}
			case "CUSTOMER_APPROVED" -> {
				respond(generateAfter(quote, seller), "APPROVED");
			}
			case "CUSTOMER_REJECTED" -> {
				respond(generateAfter(quote, seller), "DECLINED");
			}
			case "RECALLED" -> {
				call(post("/api/quotes/{id}/recall", quote), ergoRfq, "{\"comment\": \"take back\"}")
					.andExpect(status().isOk());
			}
			default -> throw new IllegalArgumentException(state);
		}
	}

	private long generateAfter(long quote, Login seller) throws Exception {
		driveTo(quote, seller, "ACCEPTED");
		return generate(rfqOf(quote), quote);
	}

	protected static final List<String> ALL_STATES = List.of("NOT_STARTED", "DRAFT_SAVED", "SUBMITTED",
			"REVISION_REQUIRED", "RESUBMITTED", "ACCEPTED", "WITH_CUSTOMER", "CUSTOMER_ON_HOLD", "CUSTOMER_CORRECTION",
			"CUSTOMER_APPROVED", "CUSTOMER_REJECTED", "RECALLED");

	/** A one-part RFQ with the part sent to {@code supplier}; returns the part's quote id. */
	protected long singlePartQuote(Login seller, long supplierId, String part) throws Exception {
		long rfq = reviewedRfq(part);
		distribute(rfq, supplierId, parts(rfq));
		return quoteOf(seller, rfq, part);
	}

}
