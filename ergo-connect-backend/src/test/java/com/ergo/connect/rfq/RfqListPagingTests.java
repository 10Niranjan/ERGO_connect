package com.ergo.connect.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 8o: the RFQ list is paged, filtered and searched in the database instead of shipping every RFQ to the browser.
 * The point of these tests is that none of that widens what a caller can see: record scope comes first, status filters
 * use the caller's own status vocabulary, and the customer-name search is for ERGO only.
 */
@IntegrationTest
class RfqListPagingTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long inr;

	long customerA;

	long customerB;

	Login buyerA;

	Login buyerB;

	Login ergoRfq;

	Login kam;

	Login lonelyKam;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		customerA = users.company("CUSTOMER");
		customerB = users.company("CUSTOMER");
		inr = users.currency("INR");
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		buyerB = users.login(customerB, "CUSTOMER_RFQ");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		kam = users.login(ergo, "ERGO_KAM");
		users.assignAccounts(kam.userId(), customerA);
		lonelyKam = users.login(ergo, "ERGO_KAM");
	}

	private long rfq(Login buyer, String... parts) throws Exception {
		return TestRfq.create(mvc, buyer, TestRfq.form(inr, parts));
	}

	private String list(Login who, String query) throws Exception {
		return mvc.perform(get("/api/rfqs" + (query.isEmpty() ? "" : "?" + query)).header(HttpHeaders.AUTHORIZATION, who.bearer()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static List<Number> ids(String json) {
		return JsonPath.read(json, "$.items[*].id");
	}

	private static int total(String json) {
		return JsonPath.<Number>read(json, "$.total").intValue();
	}

	@Test
	void pagesAreNewestFirstDisjointAndCountEverything() throws Exception {
		long[] made = new long[7];
		for (int i = 0; i < made.length; i++) {
			made[i] = rfq(buyerA, "PAGE-" + i);
		}
		String p0 = list(buyerA, "page=0&size=3");
		String p1 = list(buyerA, "page=1&size=3");
		String p2 = list(buyerA, "page=2&size=3");
		String p3 = list(buyerA, "page=3&size=3");

		assertThat(ids(p0)).containsExactly((Number) (int) made[6], (int) made[5], (int) made[4]);
		assertThat(ids(p1)).containsExactly((Number) (int) made[3], (int) made[2], (int) made[1]);
		assertThat(ids(p2)).containsExactly((Number) (int) made[0]);
		assertThat(ids(p3)).isEmpty(); // past the end: empty, not an error
		for (String p : List.of(p0, p1, p2, p3)) {
			assertThat(total(p)).isEqualTo(7); // the total is the whole list, whatever the page
		}
		assertThat(JsonPath.<Number>read(p1, "$.page").intValue()).isEqualTo(1);
		assertThat(JsonPath.<Number>read(p1, "$.size").intValue()).isEqualTo(3);
	}

	@Test
	void pageSizeIsClampedAndNegativePagesAreTreatedAsTheFirst() throws Exception {
		rfq(buyerA, "CLAMP-1");
		assertThat(JsonPath.<Number>read(list(buyerA, "size=100000"), "$.size").intValue()).isEqualTo(100);
		assertThat(JsonPath.<Number>read(list(buyerA, "size=0"), "$.size").intValue()).isEqualTo(1);
		String negative = list(buyerA, "page=-5");
		assertThat(JsonPath.<Number>read(negative, "$.page").intValue()).isZero();
		assertThat(JsonPath.<Number>read(list(buyerA, ""), "$.size").intValue()).isEqualTo(25); // the default
		mvc.perform(get("/api/rfqs?page=abc").header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isBadRequest());
	}

	@Test
	void aCustomerFiltersByTheCollapsedStatusAndNeverLearnsAnInternalOne() throws Exception {
		long draft = rfq(buyerA, "ST-DRAFT");
		long inFloat = rfq(buyerA, "ST-FLOAT");
		long quotesComplete = rfq(buyerA, "ST-COMPLETE");
		jdbc.update("UPDATE rfqs SET status = 'SENT_TO_SUPPLIERS' WHERE rfq_id = ?", inFloat);
		jdbc.update("UPDATE rfqs SET status = 'QUOTES_COMPLETE' WHERE rfq_id = ?", quotesComplete);

		// IN_PROGRESS is how a customer sees both internal statuses
		assertThat(ids(list(buyerA, "status=IN_PROGRESS"))).containsExactlyInAnyOrder((int) inFloat, (int) quotesComplete);
		assertThat(ids(list(buyerA, "status=DRAFT"))).containsExactly((int) draft);
		// an internal name is not accepted from a customer: it would confirm the vocabulary behind IN_PROGRESS
		mvc.perform(get("/api/rfqs?status=SENT_TO_SUPPLIERS").header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(get("/api/rfqs?status=NOPE").header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void ergoFiltersByTheInternalStatus() throws Exception {
		String tag = "ZQX" + Long.toString(System.nanoTime(), 36);
		long inFloat = rfq(buyerA, tag + "-FLOAT");
		long complete = rfq(buyerA, tag + "-COMPLETE");
		jdbc.update("UPDATE rfqs SET status = 'SENT_TO_SUPPLIERS' WHERE rfq_id = ?", inFloat);
		jdbc.update("UPDATE rfqs SET status = 'QUOTES_COMPLETE' WHERE rfq_id = ?", complete);
		String floatOnly = list(ergoRfq, "status=SENT_TO_SUPPLIERS&q=" + tag);
		assertThat(ids(floatOnly)).containsExactly((int) inFloat);
		mvc.perform(get("/api/rfqs?status=IN_PROGRESS").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer()))
			.andExpect(status().isUnprocessableContent()); // the customer's word is not an internal status
	}

	@Test
	void searchMatchesNumberAndPartCaseInsensitivelyAndNeverCrossesCompanies() throws Exception {
		String tag = "Fl" + Long.toString(System.nanoTime(), 36);
		long mine = rfq(buyerA, tag + "-X");
		long other = rfq(buyerB, tag + "-Y");

		assertThat(ids(list(buyerA, "q=" + tag.toLowerCase()))).containsExactly((int) mine); // not B's, though it matches too
		assertThat(ids(list(buyerB, "q=" + tag.toUpperCase()))).containsExactly((int) other);
		assertThat(ids(list(ergoRfq, "q=" + tag))).containsExactlyInAnyOrder((int) mine, (int) other);
		String number = jdbc.queryForObject("SELECT rfq_number FROM rfqs WHERE rfq_id = ?", String.class, mine);
		assertThat(ids(list(buyerA, "q=" + number.toLowerCase()))).containsExactly((int) mine);
		assertThat(total(list(buyerA, "q=" + tag))).isEqualTo(1);
	}

	@Test
	void searchTextIsLiteralNotAPattern() throws Exception {
		rfq(buyerA, "LIT-100%-A");
		rfq(buyerA, "LIT-100X-B");
		rfq(buyerA, "LIT_underscore");
		rfq(buyerA, "LITxunderscore");
		assertThat(total(list(buyerA, "q=100%"))).isEqualTo(1); // '%' is a character, not "anything"
		assertThat(total(list(buyerA, "q=LIT_under"))).isEqualTo(1); // '_' is a character, not "any one character"
		assertThat(total(list(buyerA, "q=%"))).isEqualTo(1);
		assertThat(total(list(buyerA, "q=\\"))).isZero(); // a lone backslash matches nothing, and does not break the query
	}

	@Test
	void customerNameSearchIsForErgoOnly() throws Exception {
		jdbc.update("UPDATE companies SET company_name = ? WHERE company_id = ?", "Zephyrine Castings Pvt", customerA);
		long a = rfq(buyerA, "NAME-A");
		rfq(buyerB, "NAME-B");

		assertThat(ids(list(ergoRfq, "q=zephyrine"))).containsExactly((int) a);
		assertThat(ids(list(kam, "q=zephyrine"))).containsExactly((int) a);
		// a customer cannot use the box to look up other companies, and its own name is not something it searches by
		assertThat(total(list(buyerB, "q=zephyrine"))).isZero();
		assertThat(total(list(buyerA, "q=zephyrine"))).isZero();
	}

	@Test
	void aKamCannotSearchItsWayToACustomerThatIsNotItsAccount() throws Exception {
		jdbc.update("UPDATE companies SET company_name = ? WHERE company_id = ?", "Quillon Forgings Pvt", customerB);
		rfq(buyerB, "ORACLE-B");

		assertThat(total(list(kam, "q=quillon"))).isZero(); // customer B exists but is not the KAM's account
		assertThat(total(list(kam, "q=ORACLE-B"))).isZero();
		assertThat(total(list(lonelyKam, "q=quillon"))).isZero();
	}

	@Test
	void ergoNpdSeesOnlyApprovedRfqsWhateverItFiltersOrSearches() throws Exception {
		Login npd = users.login(users.company("ERGO"), "ERGO_NPD");
		long draft = rfq(buyerA, "NPD-SCOPE"); // never approved

		assertThat(ids(list(npd, "size=100"))).doesNotContain((int) draft);
		assertThat(total(list(npd, "q=NPD-SCOPE"))).isZero();
		assertThat(total(list(npd, "status=DRAFT"))).isZero();
	}

	@Test
	void anOutOfRangePageOrControlCharacterInTheSearchIsHandledNotA500() throws Exception {
		rfq(buyerA, "EDGE-1");
		assertThat(ids(list(buyerA, "page=21474837&size=100"))).isEmpty();
		assertThat(ids(list(buyerA, "page=2147483647&size=1"))).isEmpty();
		String withNul = mvc.perform(get("/api/rfqs").param("q", " EDGE-1").header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isOk()) // a NUL is dropped, not sent to Postgres
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(total(withNul)).isEqualTo(1);
	}

	@Test
	void recordScopeComesBeforeFilterAndSearch() throws Exception {
		rfq(buyerA, "SCOPE-A");
		long b = rfq(buyerB, "SCOPE-B");

		String forKam = list(kam, "q=SCOPE-");
		assertThat(ids(forKam)).doesNotContain((int) b); // customer B is not the KAM's account
		assertThat(total(forKam)).isEqualTo(1);
		assertThat(total(list(lonelyKam, "q=SCOPE-"))).isZero(); // no accounts: nothing, whatever the search
		assertThat(ids(list(lonelyKam, ""))).isEmpty();
	}

	@Test
	void aSupplierStillGetsNothingFromTheRfqModule() throws Exception {
		Login seller = users.login(users.company("SUPPLIER"), "SUPPLIER_RFQ");
		mvc.perform(get("/api/rfqs?q=anything").header(HttpHeaders.AUTHORIZATION, seller.bearer()))
			.andExpect(status().isForbidden());
		mvc.perform(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, seller.bearer())).andExpect(status().is4xxClientError());
	}

}
