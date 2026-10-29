package com.ergo.connect.dashboard;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;

/**
 * Phase 8o load-test fix: a dashboard section keeps only the rows that need attention first and says how many there really
 * are. Capped at 2 here so the effect is visible with a handful of rows. The totals and the KPI counts must stay true, and
 * the cap applies after record scope, never instead of it.
 */
@IntegrationTest
@TestPropertySource(properties = "ergo.dashboard.max-rows=2")
class DashboardCapTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	long customerA;

	long inr;

	Login ergoKam;

	Login ergoAdmin;

	Login buyerA;

	Login buyerB;

	@BeforeEach
	void setUp() throws Exception {
		TestUsers users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		customerA = users.company("CUSTOMER");
		long customerB = users.company("CUSTOMER");
		inr = users.currency("INR");
		ergoAdmin = users.login(ergo, "ERGO_ADMIN");
		ergoKam = users.login(ergo, "ERGO_KAM");
		users.assignAccounts(ergoKam.userId(), customerA);
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		buyerB = users.login(customerB, "CUSTOMER_RFQ");
	}

	private void createRfq(Login buyer, String part, int dueInDays) throws Exception {
		String body = """
				{"targetQuoteDate": "%s", "currencyId": %d, "items": [
				  {"partNumber": "%s", "description": "Bracket", "materialSpec": "EN8",
				   "targetProductionPiecePrice": 99.5, "processSteps": [{"stepName": "Casting"}]}]}"""
			.formatted(LocalDate.now().plusDays(dueInDays), inr, part);
		mvc.perform(post("/api/rfqs").header(HttpHeaders.AUTHORIZATION, buyer.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isCreated());
	}

	@Test
	void aCustomerSeesTheSoonestTwoOfFourAndTheTrueTotal() throws Exception {
		createRfq(buyerA, "CAP-A", 9);
		createRfq(buyerA, "CAP-B", 2);
		createRfq(buyerA, "CAP-C", 14);
		createRfq(buyerA, "CAP-D", 5);
		createRfq(buyerB, "CAP-OTHER", 1); // another customer's: must not count, even though it is the soonest of all

		LocalDate soonest = LocalDate.now().plusDays(2);
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CUSTOMER"))
			.andExpect(jsonPath("$.rfqTotal").value(4))
			.andExpect(jsonPath("$.rfqItems", hasSize(2)))
			.andExpect(jsonPath("$.rfqItems[0].targetQuoteDate").value(soonest.toString()))
			.andExpect(jsonPath("$.rfqItems[1].targetQuoteDate").value(LocalDate.now().plusDays(5).toString()))
			.andExpect(jsonPath("$.rfqItems[*].parts[*]").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("CAP-OTHER"))));
	}

	@Test
	void anAccountManagerStillSeesOnlyTheirAccountsAndTheirTotalIsTrue() throws Exception {
		createRfq(buyerA, "KAM-A", 3);
		createRfq(buyerA, "KAM-B", 4);
		createRfq(buyerA, "KAM-C", 6);
		createRfq(buyerB, "KAM-OTHER", 1); // not the KAM's account

		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, ergoKam.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("FUNCTIONAL"))
			.andExpect(jsonPath("$.rfqTotal").value(3))
			.andExpect(jsonPath("$.rfqItems", hasSize(2)));
	}

	@Test
	void managementKpiCountsAreNotCappedAndEveryListHasATotal() throws Exception {
		for (int i = 0; i < 4; i++) {
			createRfq(buyerA, "MGMT-" + i, 5 + i);
		}
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, ergoAdmin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("MANAGEMENT"))
			.andExpect(jsonPath("$.openRfqCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)))
			.andExpect(jsonPath("$.delayedNpdTotal").isNumber())
			.andExpect(jsonPath("$.highRiskNpdTotal").isNumber())
			.andExpect(jsonPath("$.ordersAtRiskTotal").isNumber())
			.andExpect(jsonPath("$.partTotal").isNumber());
	}

}
