package com.ergo.connect.masterdata.access;

import static com.ergo.connect.common.security.CompanyType.CUSTOMER;
import static com.ergo.connect.common.security.CompanyType.ERGO;
import static com.ergo.connect.common.security.CompanyType.SUPPLIER;
import static com.ergo.connect.masterdata.access.Accounts.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.security.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * SRS §10.2/§10.3 with the client's decisions: approval of companies added by ERGO RFQ / KAM, KAMs limited to
 * assigned customers, customers and suppliers maintaining only their own profile.
 */
@IntegrationTest
class CompanyManagementTests {

	private static final AtomicInteger SEQ = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	CompanyRepository companies;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@Autowired
	OtpCodeStore otpCodes;

	@Autowired
	JdbcTemplate jdbc;

	Accounts accounts;

	Company ergo;

	Company customerA;

	Company customerB;

	Company supplierX;

	String asAdmin;

	String asManagement;

	String asRfq;

	@BeforeEach
	void setUp() throws Exception {
		accounts = new Accounts(companies, users, encoder, mvc, otpCodes);
		ergo = accounts.company(ERGO);
		customerA = accounts.company(CUSTOMER);
		customerB = accounts.company(CUSTOMER);
		supplierX = accounts.company(SUPPLIER);
		asAdmin = accounts.bearer(accounts.user(ergo, Role.ERGO_ADMIN));
		asManagement = accounts.bearer(accounts.user(ergo, Role.ERGO_MANAGEMENT));
		asRfq = accounts.bearer(accounts.user(ergo, Role.ERGO_RFQ));
	}

	@Test
	void additionsByErgoRfqWaitForApprovalWhileAdminAdditionsAreActive() throws Exception {
		long pending = create(asRfq, "SUPPLIER").andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
			.andExpect(jsonPath("$.actions", contains("EDIT")))
			.andReturn()
			.getResponse()
			.getContentAsString()
			.transform(body -> JsonPath.<Number>read(body, "$.id").longValue());
		create(asAdmin, "CUSTOMER").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"));

		// no users for a company nobody has approved yet
		mvc.perform(json(post("/api/users"), """
				{"companyId": %d, "firstName": "Too", "lastName": "Early", "email": "too.early%d@test.dev",
				 "roles": ["SUPPLIER_RFQ"]}""".formatted(pending, SEQ.incrementAndGet()), asAdmin))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void approvalStoresApproverRoleOldAndNewStatusAndComment() throws Exception {
		long id = idOf(create(asRfq, "SUPPLIER"));

		mvc.perform(json(post("/api/companies/{id}/approve", id), "{\"comment\": \"Audit passed\"}", asManagement))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asManagement))
			.andExpect(status().isConflict());

		Map<String, Object> row = jdbc.queryForMap("""
				SELECT old_value, new_value, action_reason, changed_by_role_code FROM audit_logs
				WHERE entity_name = 'Company' AND record_id = ? AND field_name = 'status'""", id);
		assertThat(row).containsEntry("old_value", "PENDING_APPROVAL")
			.containsEntry("new_value", "ACTIVE")
			.containsEntry("action_reason", "Audit passed")
			.containsEntry("changed_by_role_code", "ERGO_MANAGEMENT");
	}

	@Test
	void rejectionNeedsACommentAndTheCompanyCanBeResubmitted() throws Exception {
		long id = idOf(create(asRfq, "CUSTOMER"));

		mvc.perform(json(post("/api/companies/{id}/reject", id), "{\"comment\": \" \"}", asAdmin))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(json(post("/api/companies/{id}/reject", id), "{\"comment\": \"GST number missing\"}", asAdmin))
			.andExpect(jsonPath("$.status").value("REJECTED"))
			.andExpect(jsonPath("$.actions", hasItem("RESUBMIT"))) // anyone who may edit it can send it back
			.andExpect(jsonPath("$.actions", not(hasItem("APPROVE"))));

		mvc.perform(post("/api/companies/{id}/resubmit", id).header(HttpHeaders.AUTHORIZATION, asRfq))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
	}

	@Test
	void nobodyApprovesACompanyTheyAddedThemselves() throws Exception {
		User both = accounts.user(ergo, Role.ERGO_RFQ, Role.ERGO_MANAGEMENT);
		MvcResult login = accounts.login(both);
		long id = idOf(create(bearer(login), "SUPPLIER"));
		String asManagementToo = "Bearer " + JsonPath.read(mvc
			.perform(json(post("/api/auth/switch-role"), "{\"role\": \"ERGO_MANAGEMENT\"}", bearer(login)))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.accessToken");

		mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asManagementToo))
			.andExpect(jsonPath("$.actions", empty()));
		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asManagementToo))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void onlyReviewersApproveAndOnlyErgoAdminDeactivates() throws Exception {
		long id = idOf(create(asRfq, "SUPPLIER"));
		String asCustomerKam = accounts.bearer(accounts.user(customerA, Role.CUSTOMER_KAM));

		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asRfq)).andExpect(status().isForbidden());
		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asCustomerKam))
			.andExpect(status().isForbidden());

		mvc.perform(json(put("/api/companies/{id}/active", supplierX.id), "{\"active\": false}", asManagement))
			.andExpect(status().isForbidden());
		mvc.perform(json(put("/api/companies/{id}/active", supplierX.id), "{\"active\": false}", asAdmin))
			.andExpect(jsonPath("$.status").value("INACTIVE"));
		mvc.perform(json(put("/api/companies/{id}/active", supplierX.id), "{\"active\": true}", asAdmin))
			.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void keyAccountManagersSeeAndEditOnlyTheirAssignedCustomers() throws Exception {
		User kam = accounts.user(ergo, Role.ERGO_KAM);
		mvc.perform(json(put("/api/users/{id}/accounts", kam.id), "{\"customerIds\": [%d]}".formatted(customerA.id),
				asAdmin))
			.andExpect(status().isOk());
		String asKam = accounts.bearer(kam);

		mvc.perform(get("/api/companies").header(HttpHeaders.AUTHORIZATION, asKam))
			.andExpect(jsonPath("$[*].id", contains(customerA.id.intValue())));
		for (Company other : new Company[] { customerB, supplierX }) {
			mvc.perform(get("/api/companies/{id}", other.id).header(HttpHeaders.AUTHORIZATION, asKam))
				.andExpect(status().isNotFound());
		}
		mvc.perform(json(put("/api/companies/{id}", customerA.id), profile("Renamed by KAM"), asKam))
			.andExpect(status().isOk());

		create(asKam, "SUPPLIER").andExpect(status().isForbidden());
		long added = idOf(create(asKam, "CUSTOMER"));
		mvc.perform(get("/api/companies").header(HttpHeaders.AUTHORIZATION, asKam))
			.andExpect(jsonPath("$[*].id", containsInAnyOrder(customerA.id.intValue(), (int) added)));
	}

	@Test
	void customersAndSuppliersMaintainOnlyTheirOwnProfile() throws Exception {
		String asCustomerRfq = accounts.bearer(accounts.user(customerA, Role.CUSTOMER_RFQ));
		String asCustomerNpd = accounts.bearer(accounts.user(customerA, Role.CUSTOMER_NPD));
		String asSupplierKam = accounts.bearer(accounts.user(supplierX, Role.SUPPLIER_KAM));

		mvc.perform(get("/api/companies").header(HttpHeaders.AUTHORIZATION, asCustomerRfq))
			.andExpect(jsonPath("$[*].id", contains(customerA.id.intValue())))
			.andExpect(jsonPath("$[0].actions", contains("EDIT")));
		mvc.perform(json(put("/api/companies/{id}", customerA.id), profile("Acme by its buyer"), asCustomerRfq))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.profile.name").value("Acme by its buyer"))
			.andExpect(jsonPath("$.code").value(customerA.code)); // code and type are not part of the profile
		mvc.perform(json(put("/api/companies/{id}", customerB.id), profile("Hijacked"), asCustomerRfq))
			.andExpect(status().isNotFound());
		mvc.perform(json(put("/api/companies/{id}", customerA.id),
				"{\"name\": \"Acme\", \"primaryContactPhone\": \"+91 44 4000 123+91 44 4000 1234\"}", asCustomerRfq))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(containsString("primaryContactPhone")));

		mvc.perform(get("/api/companies/{id}", customerA.id).header(HttpHeaders.AUTHORIZATION, asCustomerNpd))
			.andExpect(jsonPath("$.actions", empty())); // view only (SRS §10.2 RV)
		mvc.perform(json(put("/api/companies/{id}", customerA.id), profile("Not allowed"), asCustomerNpd))
			.andExpect(status().isForbidden());

		mvc.perform(json(put("/api/companies/{id}", supplierX.id), profile("Supplier X Ltd"), asSupplierKam))
			.andExpect(status().isOk());
		mvc.perform(get("/api/companies/{id}", customerA.id).header(HttpHeaders.AUTHORIZATION, asSupplierKam))
			.andExpect(status().isNotFound());
		create(asSupplierKam, "SUPPLIER").andExpect(status().isForbidden());
	}

	@Test
	void ergoFunctionalRolesHaveNoCompanyListUntilTheirModulesExist() throws Exception {
		String asNpd = accounts.bearer(accounts.user(ergo, Role.ERGO_NPD));

		mvc.perform(get("/api/companies").header(HttpHeaders.AUTHORIZATION, asNpd)).andExpect(jsonPath("$", empty()));
		mvc.perform(get("/api/companies/{id}", customerA.id).header(HttpHeaders.AUTHORIZATION, asNpd))
			.andExpect(status().isNotFound());
	}

	@Test
	void accountAssignmentsLinkErgoUsersToCustomersOnly() throws Exception {
		User kam = accounts.user(ergo, Role.ERGO_KAM);
		User customerUser = accounts.user(customerA, Role.CUSTOMER_KAM);

		mvc.perform(json(put("/api/users/{id}/accounts", kam.id), "{\"customerIds\": [%d]}".formatted(supplierX.id),
				asAdmin))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(json(put("/api/users/{id}/accounts", customerUser.id),
				"{\"customerIds\": [%d]}".formatted(customerA.id), asAdmin))
			.andExpect(status().isUnprocessableContent());
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO user_account_assignments (user_id, customer_company_id) VALUES (?, ?)", kam.id,
				supplierX.id))
			.hasMessageContaining("link an ERGO user to a customer");
	}

	@Test
	void everySupplierIsApprovedByErgoManagementAlone() throws Exception {
		long byAdmin = idOf(create(asAdmin, "SUPPLIER"));
		long byRfq = idOf(create(asRfq, "SUPPLIER"));
		String otherAdmin = accounts.bearer(accounts.user(ergo, Role.ERGO_ADMIN));

		for (long id : new long[] { byAdmin, byRfq }) {
			mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asAdmin))
				.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
			// no Admin (not the author, not another one) and not ERGO RFQ may decide on a supplier
			for (String token : new String[] { asAdmin, otherAdmin }) {
				mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, token))
					.andExpect(jsonPath("$.actions", not(hasItem("APPROVE"))))
					.andExpect(jsonPath("$.actions", not(hasItem("REJECT"))));
				mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", token))
					.andExpect(status().isUnprocessableContent());
				mvc.perform(json(post("/api/companies/{id}/reject", id), "{\"comment\": \"no\"}", token))
					.andExpect(status().isUnprocessableContent());
			}
			mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asRfq)).andExpect(status().isForbidden());
			mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asManagement))
				.andExpect(jsonPath("$.actions", hasItem("APPROVE")));
			mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asManagement))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		}
	}

	@Test
	void aCustomerAddedByRfqCanStillBeApprovedByAdminOrManagement() throws Exception {
		long byRfq = idOf(create(asRfq, "CUSTOMER"));
		String otherAdmin = accounts.bearer(accounts.user(ergo, Role.ERGO_ADMIN));
		mvc.perform(json(post("/api/companies/{id}/approve", byRfq), "{}", otherAdmin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void aRejectedSupplierIsResubmittedAndStaysOffTheApprovedListUntilManagementApproves() throws Exception {
		long id = idOf(create(asAdmin, "SUPPLIER"));
		mvc.perform(json(post("/api/companies/{id}/reject", id), "{\"comment\": \"Certificates missing\"}",
				asManagement))
			.andExpect(jsonPath("$.status").value("REJECTED"));
		mvc.perform(post("/api/companies/{id}/resubmit", id).header(HttpHeaders.AUTHORIZATION, asAdmin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

		approvedSuppliers(asManagement).andExpect(jsonPath("$[*].id", not(hasItem((int) id))));
		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asManagement)).andExpect(status().isOk());
		approvedSuppliers(asManagement).andExpect(jsonPath("$[*].id", hasItem((int) id)));
	}

	@Test
	void theCompanyListCanBeNarrowedToApprovedSuppliers() throws Exception {
		long pending = idOf(create(asRfq, "SUPPLIER"));
		long rejected = idOf(create(asRfq, "SUPPLIER"));
		mvc.perform(json(post("/api/companies/{id}/reject", rejected), "{\"comment\": \"No\"}", asManagement));

		approvedSuppliers(asRfq).andExpect(jsonPath("$[*].id", hasItem(supplierX.id.intValue())))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) pending))))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) rejected))))
			.andExpect(jsonPath("$[*].type", not(hasItem("CUSTOMER"))))
			.andExpect(jsonPath("$[*].status", not(hasItem("PENDING_APPROVAL"))));
		mvc.perform(get("/api/companies").param("type", "SUPPLIER").param("status", "PENDING_APPROVAL")
			.header(HttpHeaders.AUTHORIZATION, asManagement))
			.andExpect(jsonPath("$[*].id", hasItem((int) pending)))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) rejected))));
		// narrowing never widens record scope: a supplier sees only itself
		String asSupplier = accounts.bearer(accounts.user(supplierX, Role.SUPPLIER_RFQ));
		mvc.perform(get("/api/companies").param("type", "CUSTOMER").header(HttpHeaders.AUTHORIZATION, asSupplier))
			.andExpect(jsonPath("$", empty()));
	}

	@Test
	void addUserIsOfferedOnlyOnApprovedCompaniesTheCallerMayAddAccountsTo() throws Exception {
		long pending = idOf(create(asRfq, "SUPPLIER"));
		for (long id : new long[] { supplierX.id, customerA.id, ergo.id, pending }) {
			boolean adminGets = id != pending;
			mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asAdmin))
				.andExpect(jsonPath("$.actions", adminGets ? hasItem("ADD_USER") : not(hasItem("ADD_USER"))));
			boolean rfqGets = id == supplierX.id;
			mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asRfq))
				.andExpect(jsonPath("$.actions", rfqGets ? hasItem("ADD_USER") : not(hasItem("ADD_USER"))));
			mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asManagement))
				.andExpect(jsonPath("$.actions", not(hasItem("ADD_USER"))));
		}
	}

	@Test
	void managementIsToldAboutPendingCompaniesAndTheAuthorAboutTheDecision() throws Exception {
		User rfq = accounts.user(ergo, Role.ERGO_RFQ);
		User admin = accounts.user(ergo, Role.ERGO_ADMIN);
		User management = accounts.user(ergo, Role.ERGO_MANAGEMENT);
		String rfqToken = accounts.bearer(rfq);

		long viaRfq = idOf(create(rfqToken, "SUPPLIER"));
		// a supplier is reviewed by Management alone: Admin is not told
		assertThat(recipients("COMPANY_APPROVAL_REQUESTED", viaRfq)).contains(management.id).doesNotContain(admin.id, rfq.id);

		long customerViaRfq = idOf(create(rfqToken, "CUSTOMER")); // a customer can be reviewed by Admin too
		assertThat(recipients("COMPANY_APPROVAL_REQUESTED", customerViaRfq)).contains(management.id, admin.id);

		mvc.perform(json(post("/api/companies/{id}/reject", viaRfq), "{\"comment\": \"No\"}", accounts.bearer(management)))
			.andExpect(status().isOk());
		assertThat(recipients("COMPANY_APPROVAL_DECIDED", viaRfq)).containsExactly(rfq.id);
		mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, rfqToken))
			.andExpect(jsonPath("$.items[0].title").value("Company approval decision recorded"))
			.andExpect(jsonPath("$.items[0].actionUrl").value("/companies"));
	}

	@Test
	void ergoQualityDoesNotSeePendingOrRejectedCompanies() throws Exception {
		long pending = idOf(create(asRfq, "SUPPLIER"));
		long rejected = idOf(create(asRfq, "SUPPLIER"));
		mvc.perform(json(post("/api/companies/{id}/reject", rejected), "{\"comment\": \"No\"}", asManagement));
		String asQuality = accounts.bearer(accounts.user(ergo, Role.ERGO_QUALITY));

		mvc.perform(get("/api/companies").header(HttpHeaders.AUTHORIZATION, asQuality))
			.andExpect(jsonPath("$[*].id", hasItem(supplierX.id.intValue())))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) pending))))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) rejected))));
		mvc.perform(get("/api/companies/{id}", pending).header(HttpHeaders.AUTHORIZATION, asQuality))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/companies/{id}", rejected).header(HttpHeaders.AUTHORIZATION, asQuality))
			.andExpect(status().isNotFound());
	}

	@Test
	void theFiltersNeverWidenARecordScope() throws Exception {
		long pending = idOf(create(asRfq, "SUPPLIER"));
		User kam = accounts.user(ergo, Role.ERGO_KAM);
		mvc.perform(json(put("/api/users/{id}/accounts", kam.id), "{\"customerIds\": [%d]}".formatted(customerA.id),
				asAdmin));
		String asKam = accounts.bearer(kam);
		String asSupplier = accounts.bearer(accounts.user(supplierX, Role.SUPPLIER_RFQ));
		String asCustomer = accounts.bearer(accounts.user(customerA, Role.CUSTOMER_RFQ));

		// a KAM asking for suppliers gets none; a supplier / customer only ever gets their own company
		mvc.perform(get("/api/companies").param("type", "SUPPLIER").header(HttpHeaders.AUTHORIZATION, asKam))
			.andExpect(jsonPath("$", empty()));
		mvc.perform(get("/api/companies").param("type", "SUPPLIER").param("status", "PENDING_APPROVAL")
			.header(HttpHeaders.AUTHORIZATION, asSupplier)).andExpect(jsonPath("$", empty()));
		mvc.perform(get("/api/companies").param("type", "SUPPLIER").param("status", "ACTIVE")
			.header(HttpHeaders.AUTHORIZATION, asSupplier))
			.andExpect(jsonPath("$[*].id", contains(supplierX.id.intValue())));
		mvc.perform(get("/api/companies").param("type", "SUPPLIER").param("status", "ACTIVE")
			.header(HttpHeaders.AUTHORIZATION, asCustomer)).andExpect(jsonPath("$", empty()));
		mvc.perform(get("/api/companies").param("status", "PENDING_APPROVAL").header(HttpHeaders.AUTHORIZATION, asCustomer))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) pending))));
	}

	@Test
	void companyNotificationsReachErgoStaffOnly() throws Exception {
		User supplierUser = accounts.user(supplierX, Role.SUPPLIER_RFQ);
		User customerUser = accounts.user(customerA, Role.CUSTOMER_RFQ);
		long id = idOf(create(asRfq, "SUPPLIER"));
		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asManagement)).andExpect(status().isOk());

		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM notifications n JOIN users u ON u.user_id = n.recipient_user_id
				JOIN companies c ON c.company_id = u.company_id
				WHERE n.linked_module = 'company' AND c.company_type <> 'ERGO'""", Long.class)).isZero();
		assertThat(recipients("COMPANY_APPROVAL_REQUESTED", id)).doesNotContain(supplierUser.id, customerUser.id);
		assertThat(recipients("COMPANY_APPROVAL_DECIDED", id)).doesNotContain(supplierUser.id, customerUser.id);
	}

	@Test
	void theSuppliersMenuIsShownToAdminManagementAndRfqOnly() throws Exception {
		for (Role role : Role.values()) {
			if (role == Role.CUSTOMER_MANAGEMENT) {
				continue; // inactive in MVP: cannot sign in
			}
			boolean shown = role == Role.ERGO_ADMIN || role == Role.ERGO_MANAGEMENT || role == Role.ERGO_RFQ;
			Company company = role.companyType() == ERGO ? ergo : role.companyType() == CUSTOMER ? customerA : supplierX;
			mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, accounts.bearer(accounts.user(company, role))))
				.andExpect(jsonPath("$.menu[*].items[*].key", shown ? hasItem("suppliers") : not(hasItem("suppliers"))));
		}
	}

	private java.util.List<Long> recipients(String kind, long companyId) {
		return jdbc.queryForList("""
				SELECT recipient_user_id FROM notifications
				WHERE event_kind = ? AND linked_module = 'company' AND linked_record_id = ?""", Long.class, kind,
				companyId);
	}

	private ResultActions approvedSuppliers(String token) throws Exception {
		return mvc.perform(get("/api/companies").param("type", "SUPPLIER")
			.param("status", "ACTIVE")
			.header(HttpHeaders.AUTHORIZATION, token));
	}

	private ResultActions create(String token, String type) throws Exception {
		int n = SEQ.incrementAndGet();
		return mvc.perform(json(post("/api/companies"), """
				{"code": "NEW-%d", "type": "%s", "profile": {"name": "New company %d", "taxId": "29ABCDE1234F1Z5"}}"""
			.formatted(n, type, n), token));
	}

	private static long idOf(ResultActions created) throws Exception {
		return JsonPath.<Number>read(created.andReturn().getResponse().getContentAsString(), "$.id").longValue();
	}

	private static String profile(String name) {
		return "{\"name\": \"%s\", \"city\": \"Pune\", \"primaryContactPhone\": \"+91 20 5555 0000\"}".formatted(name);
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(body);
	}

}
