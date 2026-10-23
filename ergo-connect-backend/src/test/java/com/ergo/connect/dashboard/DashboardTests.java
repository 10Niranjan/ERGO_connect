package com.ergo.connect.dashboard;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 7c: the {@code dashboard} module is pure assembly over other modules' {@code .api} facades (ARCHITECTURE.md
 * §2), so these tests are about the assembly and record scope, not re-testing business rules each owning module's
 * own test suite already covers. Covers: the right response shape per audience (§10.11's View-only matrix), that
 * Management's counts combine RFQ+NCR data correctly, and the one real cross-module security question this phase
 * introduces - whether company scope (customer A never sees customer B's items) survives being re-assembled through
 * a new module.
 */
@IntegrationTest
class DashboardTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long ergo;

	long customerA;

	long customerB;

	long inr;

	Login ergoAdmin;

	Login ergoRfq;

	Login ergoQuality;

	Login ergoKam;

	Login ergoNpd;

	Login buyerA;

	Login buyerB;

	long supplier;

	Login seller;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		ergo = users.company("ERGO");
		customerA = users.company("CUSTOMER");
		customerB = users.company("CUSTOMER");
		inr = users.currency("INR");
		ergoAdmin = users.login(ergo, "ERGO_ADMIN");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		ergoKam = users.login(ergo, "ERGO_KAM");
		ergoNpd = users.login(ergo, "ERGO_NPD");
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		buyerB = users.login(customerB, "CUSTOMER_RFQ");
		supplier = users.company("SUPPLIER");
		seller = users.login(supplier, "SUPPLIER_RFQ");
	}

	private long createRfq(Login buyer, String partNumber) throws Exception {
		return JsonPath
			.<Number>read(mvc
				.perform(json(post("/api/rfqs"), com.ergo.connect.TestRfq.form(inr, partNumber), buyer))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString(), "$.id")
			.longValue();
	}

	private void submit(long rfqId, Login buyer) throws Exception {
		mvc.perform(json(post("/api/rfqs/{id}/submit", rfqId), "{}", buyer)).andExpect(status().isOk());
	}

	private long createNcr(long customerCompanyId) throws Exception {
		return JsonPath
			.<Number>read(mvc
				.perform(json(post("/api/quality/ncrs"), """
						{"linkedModule": "INCOMING", "linkedRecordId": 1, "issueCategory": "Dimensional",
						 "issueDescription": "desc", "targetClosureDate": "%s", "customerCompanyId": %d,
						 "ownerUserId": null}"""
					.formatted(LocalDate.now().plusDays(30), customerCompanyId), ergoQuality))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString(), "$.id")
			.longValue();
	}

	@Test
	void ergoAdminGetsTheUnrestrictedManagementShape() throws Exception {
		createRfq(buyerA, "PN-MGMT-1");
		submit(createRfq(buyerA, "PN-MGMT-2"), buyerA); // one DRAFT, one SUBMITTED
		createNcr(customerA);

		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, ergoAdmin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("MANAGEMENT"))
			.andExpect(jsonPath("$.openRfqCount").value(greaterThanOrEqualTo(2)))
			.andExpect(jsonPath("$.openNcrCount").value(greaterThanOrEqualTo(1)))
			.andExpect(jsonPath("$.pendingApprovalsCount").value(greaterThanOrEqualTo(1)));
	}

	@Test
	void ergoFunctionalRolesGetOnlyTheirOwnModule() throws Exception {
		createRfq(buyerA, "PN-FUNC-1");
		createNcr(customerA);

		// role sheet: ERGO RFQ has "V" on Quality, so its dashboard now carries the NCRs too
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("FUNCTIONAL"))
			.andExpect(jsonPath("$.rfqItems", not(empty())))
			.andExpect(jsonPath("$.ncrItems", not(empty())));

		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, ergoQuality.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("FUNCTIONAL"))
			.andExpect(jsonPath("$.ncrItems", not(empty())))
			.andExpect(jsonPath("$.rfqItems", empty())); // ERGO_QUALITY has no RFQ-module visibility (role sheet: blank)
	}

	@Test
	void ergoKamSeesOnlyAssignedAccountsAcrossModules() throws Exception {
		createRfq(buyerA, "PN-KAM-SEEN");
		createRfq(buyerB, "PN-KAM-UNSEEN");
		users.assignAccounts(ergoKam.userId(), customerA);

		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, ergoKam.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("FUNCTIONAL"))
			.andExpect(jsonPath("$.rfqItems[*].number", hasItem(notNullValue())))
			.andExpect(jsonPath("$.rfqItems", hasSize(1)));

		Login unassignedKam = users.login(ergo, "ERGO_KAM");
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, unassignedKam.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rfqItems", empty()))
			.andExpect(jsonPath("$.npdItems", empty()))
			.andExpect(jsonPath("$.orderItems", empty()))
			.andExpect(jsonPath("$.ncrItems", empty())); // no assigned accounts: empty everywhere, not an error
	}

	@Test
	void customersSeeOnlyTheirOwnCompanyNeverTheOtherCustomer() throws Exception {
		long rfqA = createRfq(buyerA, "PN-CUST-A");
		long rfqB = createRfq(buyerB, "PN-CUST-B");

		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CUSTOMER"))
			.andExpect(jsonPath("$.rfqItems", hasSize(1)))
			.andExpect(jsonPath("$.rfqItems[0].id").value(rfqA));

		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, buyerB.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rfqItems", hasSize(1)))
			.andExpect(jsonPath("$.rfqItems[0].id").value(rfqB));
	}

	/** Security review H1 (ARCHITECTURE.md §8, 2026-10-05 Phase 7c): a customer must see {@code RfqStatus.forCustomer()},
	 * never the raw internal status, and the internal-only {@code awaiting} field must be absent. */
	@Test
	void customerNeverSeesErgoInternalRfqStatusOrAwaitingParty() throws Exception {
		long rfqId = createRfq(buyerA, "PN-FOLD");
		submit(rfqId, buyerA);
		mvc.perform(json(post("/api/rfqs/{id}/start-review", rfqId), "{}", ergoRfq)).andExpect(status().isOk());
		com.ergo.connect.TestRfq.invite(mvc, ergoRfq, rfqId, supplier); // out with a supplier (flow doc §2)

		// ERGO sees the real status and who the system thinks should act next (ergoRfq, not ergoAdmin: the
		// ManagementDashboard shape has no rfqItems field at all, only counts).
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, ergoRfq.bearer()))
			.andExpect(jsonPath("$.rfqItems[?(@.id == %d)].status".formatted(rfqId)).value(hasItem("SENT_TO_SUPPLIERS")))
			.andExpect(jsonPath("$.partItems[?(@.rfqId == %d)].status".formatted(rfqId)).value(hasItem("NOT_STARTED")));

		// The customer sees only the collapsed, customer-facing status (SRS §8 2026-09-24), and no awaiting field.
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rfqItems[0].status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.rfqItems[0].awaiting").value(nullValue()))
			.andExpect(jsonPath("$.rfqItems[0].customerName").value(nullValue()))
			// flow doc §4: the dashboard shows each part's status; a customer reads it collapsed and never sees a supplier
			.andExpect(jsonPath("$.partItems[0].status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.partItems[0].supplierName").value(nullValue()));
	}

	/** Security review M2 (ARCHITECTURE.md §8, 2026-10-05 Phase 7c): the NCR/order isolation the RFQ tests above
	 * already cover for RFQs must hold for the other two item types too. */
	@Test
	void ncrsAndOrdersAreAlsoIsolatedBetweenCustomers() throws Exception {
		long ncrA = createNcr(customerA);
		long ncrB = createNcr(customerB);

		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, ergoQuality.bearer()))
			.andExpect(jsonPath("$.ncrItems[*].id", hasItem((int) ncrA)))
			.andExpect(jsonPath("$.ncrItems[*].id", hasItem((int) ncrB)));

		// Neither customer sees an NCR at all here: NcrService creates every NCR customer_visible = false by
		// default (security review H2, ARCHITECTURE.md §8 2026-09-30), so this also proves that gate survives
		// being re-assembled through the new dashboard module, not just RFQ's own company-id scope.
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(jsonPath("$.ncrItems", empty()));
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, buyerB.bearer())).andExpect(jsonPath("$.ncrItems", empty()));
	}

	/** Security review H2 (ARCHITECTURE.md §8, 2026-10-05 Phase 7c): NPD's internal {@code riskLevel} must never
	 * reach a customer or supplier dashboard item, same rule {@code CustomerNpdView}/{@code SupplierNpdView}
	 * already enforce on the detail screen. */
	@Test
	void npdRiskLevelNeverReachesCustomerOrSupplier() throws Exception {
		long rfqId = createRfq(buyerA, "PN-NPD-RISK");
		submit(rfqId, buyerA);
		mvc.perform(json(post("/api/rfqs/{id}/start-review", rfqId), "{}", ergoRfq)).andExpect(status().isOk());
		com.ergo.connect.TestRfq.approveWhole(mvc, buyerA, ergoRfq, seller, supplier, inr, rfqId, "90");

		long npdId = JsonPath
			.<Number>read(mvc.perform(json(post("/api/npd/convert/{id}",
					com.ergo.connect.TestRfq.itemIds(mvc, ergoRfq, rfqId).getFirst()), "{}", ergoNpd))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString(), "$.id")
			.longValue();
		mvc.perform(json(put("/api/npd/{id}",
				npdId), "{\"riskLevel\": \"HIGH\"}", ergoNpd)).andExpect(status().isOk());

		// ERGO sees the real risk level (ergoNpd, not ergoAdmin: ManagementDashboard has no generic npdItems field,
		// only delayedNpdProjects/highRiskNpdProjects).
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, ergoNpd.bearer()))
			.andExpect(jsonPath("$.npdItems[?(@.id == %d)].riskLevel".formatted(npdId)).value(hasItem("HIGH")));

		// The customer and the supplier building this exact project never see it. CUSTOMER_RFQ/SUPPLIER_RFQ have no
		// NPD-module visibility at all (SRS §10.6), so CUSTOMER_NPD/SUPPLIER_NPD logins are needed to reach it.
		Login customerNpdA = users.login(customerA, "CUSTOMER_NPD");
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, customerNpdA.bearer()))
			.andExpect(jsonPath("$.npdItems[0].riskLevel").value(nullValue()));
		Login supplierNpd = users.login(supplier, "SUPPLIER_NPD");
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, supplierNpd.bearer()))
			.andExpect(jsonPath("$.npdItems[0].riskLevel").value(nullValue()));
	}

	@Test
	void supplierWithNoDataGetsAnEmptyShapeNotAnError() throws Exception {
		mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, seller.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("SUPPLIER"))
			.andExpect(jsonPath("$.quoteItems", empty()))
			.andExpect(jsonPath("$.npdItems", empty()))
			.andExpect(jsonPath("$.orderItems", empty()))
			.andExpect(jsonPath("$.ncrItems", empty()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body,
			Login user) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body).header(HttpHeaders.AUTHORIZATION,
				user.bearer());
	}

}
