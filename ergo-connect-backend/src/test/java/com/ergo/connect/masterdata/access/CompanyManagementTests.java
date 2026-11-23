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
import static org.hamcrest.Matchers.matchesPattern;
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
	void everyNewCompanyStartsAsADraftWithAnAutomaticNumber() throws Exception {
		long draft = create(asRfq, "SUPPLIER").andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.code").value(matchesPattern("SUP-\\d{5}")))
			.andExpect(jsonPath("$.actions", containsInAnyOrder("EDIT", "SEND_FOR_APPROVAL")))
			.andReturn()
			.getResponse()
			.getContentAsString()
			.transform(body -> JsonPath.<Number>read(body, "$.id").longValue());
		// also a customer the ERGO Admin adds (client question 10); a typed-in code is ignored
		long byAdmin = idOf(mvc.perform(json(post("/api/companies"), """
				{"code": "MY-OWN", "type": "CUSTOMER", "profile": {"name": "Admin customer"}}""", asAdmin))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.code").value(matchesPattern("CUS-\\d{5}"))));
		assertThat(recipients("COMPANY_APPROVAL_REQUESTED", draft)).isEmpty(); // nobody is told about a draft
		assertThat(recipients("COMPANY_APPROVAL_REQUESTED", byAdmin)).isEmpty();

		// no users for a company nobody has approved yet
		mvc.perform(json(post("/api/users"), """
				{"companyId": %d, "firstName": "Too", "lastName": "Early", "email": "too.early%d@test.dev",
				 "roles": ["SUPPLIER_RFQ"]}""".formatted(draft, SEQ.incrementAndGet()), asAdmin))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void automaticNumbersCountUpAndSkipACodeAlreadyInUse() throws Exception {
		String first = codeOf(create(asRfq, "SUPPLIER"));
		int n = Integer.parseInt(first.substring(4));
		jdbc.update("INSERT INTO companies (company_code, company_name, company_type, status) VALUES (?, ?, 'SUPPLIER', 'ACTIVE')",
				"SUP-%05d".formatted(n + 1), "Typed-in code " + n);
		assertThat(codeOf(create(asRfq, "SUPPLIER"))).isEqualTo("SUP-%05d".formatted(n + 2));
	}

	@Test
	void sendingForApprovalNeedsTheMinimumInformation() throws Exception {
		long id = idOf(mvc.perform(json(post("/api/companies"), """
				{"type": "SUPPLIER", "profile": {"name": "Bare supplier"}}""", asRfq)));
		mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asRfq))
			.andExpect(jsonPath("$.missing", contains("country", "address", "registration or tax number",
					"main contact name and email")));
		mvc.perform(post("/api/companies/{id}/send-for-approval", id).header(HttpHeaders.AUTHORIZATION, asRfq))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value(containsString("registration or tax number")));

		mvc.perform(json(put("/api/companies/{id}", id), FULL_PROFILE.formatted("Bare supplier"), asRfq))
			.andExpect(jsonPath("$.missing", empty()));
		mvc.perform(post("/api/companies/{id}/send-for-approval", id).header(HttpHeaders.AUTHORIZATION, asRfq))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
			.andExpect(jsonPath("$.actions", not(hasItem("SEND_FOR_APPROVAL"))));
		// already pending: not again
		mvc.perform(post("/api/companies/{id}/send-for-approval", id).header(HttpHeaders.AUTHORIZATION, asRfq))
			.andExpect(status().isConflict());
	}

	@Test
	void onlyErgoPeopleWhoMayEditTheCompanySendItForApproval() throws Exception {
		long supplier = idOf(create(asRfq, "SUPPLIER"));
		User kam = accounts.user(ergo, Role.ERGO_KAM);
		String asKam = accounts.bearer(kam);
		long kamCustomer = idOf(create(asKam, "CUSTOMER"));
		long otherCustomer = idOf(create(asRfq, "CUSTOMER"));

		for (String token : new String[] { asManagement, accounts.bearer(accounts.user(ergo, Role.ERGO_QUALITY)),
				accounts.bearer(accounts.user(supplierX, Role.SUPPLIER_RFQ)) }) {
			mvc.perform(post("/api/companies/{id}/send-for-approval", supplier).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isForbidden());
		}
		mvc.perform(post("/api/companies/{id}/send-for-approval", otherCustomer).header(HttpHeaders.AUTHORIZATION, asKam))
			.andExpect(status().isNotFound()); // not one of the KAM's accounts
		mvc.perform(post("/api/companies/{id}/send-for-approval", kamCustomer).header(HttpHeaders.AUTHORIZATION, asKam))
			.andExpect(status().isOk());
		mvc.perform(post("/api/companies/{id}/send-for-approval", supplier).header(HttpHeaders.AUTHORIZATION, asAdmin))
			.andExpect(status().isOk());
	}

	@Test
	void managementCanAskForMoreInformationAndTheCompanyIsSentAgain() throws Exception {
		User rfq = accounts.user(ergo, Role.ERGO_RFQ);
		String rfqToken = accounts.bearer(rfq);
		long id = pending(rfqToken, "SUPPLIER");

		mvc.perform(json(post("/api/companies/{id}/request-info", id), "{\"comment\": \" \"}", asManagement))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(json(post("/api/companies/{id}/request-info", id), "{\"comment\": \"Which plant?\"}", asAdmin))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asManagement))
			.andExpect(jsonPath("$.actions", containsInAnyOrder("APPROVE", "REJECT", "REQUEST_INFO")));
		mvc.perform(json(post("/api/companies/{id}/request-info", id),
				"{\"comment\": \"Which plant makes the castings?\"}", asManagement))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("INFO_REQUESTED"))
			.andExpect(jsonPath("$.reviewComment").value("Which plant makes the castings?"));
		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asManagement)).andExpect(status().isConflict());
		mvc.perform(json(post("/api/companies/{id}/request-info", id), "{\"comment\": \"Again\"}", asManagement))
			.andExpect(status().isConflict());
		assertThat(jdbc.queryForObject("""
				SELECT message FROM notifications WHERE event_kind = 'COMPANY_APPROVAL_DECIDED' AND linked_record_id = ?
				AND recipient_user_id = ?""", String.class, id, rfq.id)).contains("sent back for more information");

		mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, rfqToken))
			.andExpect(jsonPath("$.reviewComment").value("Which plant makes the castings?"))
			.andExpect(jsonPath("$.actions", hasItem("SEND_FOR_APPROVAL")));
		mvc.perform(post("/api/companies/{id}/send-for-approval", id).header(HttpHeaders.AUTHORIZATION, rfqToken))
			.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asManagement))
			.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void aCompanyNeverSeesErgosReviewCommentOrMissingList() throws Exception {
		long id = pending(asRfq, "CUSTOMER");
		mvc.perform(json(post("/api/companies/{id}/approve", id),
				"{\"comment\": \"Credit risk high, watch payments\"}", asManagement))
			.andExpect(jsonPath("$.reviewComment").value("Credit risk high, watch payments"));
		String asItsBuyer = accounts.bearer(accounts.user(companies.findById(id).orElseThrow(), Role.CUSTOMER_RFQ));

		for (String url : new String[] { "/api/companies/" + id, "/api/companies" }) {
			String body = mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, asItsBuyer))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
			assertThat(body).contains("\"id\":" + id).doesNotContain("reviewComment", "Credit risk", "missing");
		}
	}

	@Test
	void approvalStoresApproverRoleOldAndNewStatusAndComment() throws Exception {
		long id = pending(asRfq, "SUPPLIER");

		mvc.perform(json(post("/api/companies/{id}/approve", id), "{\"comment\": \"Audit passed\"}", asManagement))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asManagement))
			.andExpect(status().isConflict());

		Map<String, Object> row = jdbc.queryForMap("""
				SELECT old_value, new_value, action_reason, changed_by_role_code FROM audit_logs
				WHERE entity_name = 'Company' AND record_id = ? AND field_name = 'status' AND new_value = 'ACTIVE'""", id);
		assertThat(row).containsEntry("old_value", "PENDING_APPROVAL")
			.containsEntry("new_value", "ACTIVE")
			.containsEntry("action_reason", "Audit passed")
			.containsEntry("changed_by_role_code", "ERGO_MANAGEMENT");
	}

	@Test
	void rejectionNeedsACommentAndTheCompanyCanBeResubmitted() throws Exception {
		long id = pending(asRfq, "CUSTOMER");

		mvc.perform(json(post("/api/companies/{id}/reject", id), "{\"comment\": \" \"}", asManagement))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(json(post("/api/companies/{id}/reject", id), "{\"comment\": \"GST number missing\"}", asManagement))
			.andExpect(jsonPath("$.status").value("REJECTED"))
			.andExpect(jsonPath("$.actions", not(hasItem("APPROVE"))));
		mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asAdmin))
			.andExpect(jsonPath("$.actions", hasItem("SEND_FOR_APPROVAL"))); // anyone in ERGO who may edit it

		mvc.perform(post("/api/companies/{id}/send-for-approval", id).header(HttpHeaders.AUTHORIZATION, asRfq))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
	}

	@Test
	void nobodyApprovesACompanyTheyAddedThemselves() throws Exception {
		User both = accounts.user(ergo, Role.ERGO_RFQ, Role.ERGO_MANAGEMENT);
		MvcResult login = accounts.login(both);
		long id = pending(bearer(login), "SUPPLIER");
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
	void whoeverSentTheCompanyForApprovalCannotReviewItEither() throws Exception {
		long id = idOf(create(asRfq, "SUPPLIER")); // registered by someone else
		User both = accounts.user(ergo, Role.ERGO_RFQ, Role.ERGO_MANAGEMENT);
		MvcResult login = accounts.login(both);
		mvc.perform(post("/api/companies/{id}/send-for-approval", id).header(HttpHeaders.AUTHORIZATION, bearer(login)))
			.andExpect(status().isOk());
		String asManagementToo = "Bearer " + JsonPath.read(mvc
			.perform(json(post("/api/auth/switch-role"), "{\"role\": \"ERGO_MANAGEMENT\"}", bearer(login)))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.accessToken");

		mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asManagementToo))
			.andExpect(jsonPath("$.actions", empty()));
		for (String decision : new String[] { "approve", "reject", "request-info" }) {
			mvc.perform(json(post("/api/companies/{id}/" + decision, id), "{\"comment\": \"mine\"}", asManagementToo))
				.andExpect(status().isUnprocessableContent());
		}
		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asManagement)).andExpect(status().isOk());
	}

	@Test
	void aCompanyCannotBeChangedWhileManagementDecides() throws Exception {
		long id = pending(asRfq, "CUSTOMER");
		mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asRfq))
			.andExpect(jsonPath("$.actions", not(hasItem("EDIT"))));
		for (String token : new String[] { asRfq, asAdmin }) {
			mvc.perform(json(put("/api/companies/{id}", id), FULL_PROFILE.formatted("Changed meanwhile"), token))
				.andExpect(status().isConflict());
		}
		mvc.perform(json(post("/api/companies/{id}/request-info", id), "{\"comment\": \"GST?\"}", asManagement))
			.andExpect(status().isOk());
		mvc.perform(json(put("/api/companies/{id}", id), FULL_PROFILE.formatted("Corrected"), asRfq))
			.andExpect(status().isOk()); // sent back: editable again
	}

	@Test
	void managementsCommentIsShownOnlyToManagementAndToWhoMustActOnIt() throws Exception {
		User kam = accounts.user(ergo, Role.ERGO_KAM);
		String asKam = accounts.bearer(kam);
		long customer = pending(asKam, "CUSTOMER");
		String asQuality = accounts.bearer(accounts.user(ergo, Role.ERGO_QUALITY));

		mvc.perform(json(post("/api/companies/{id}/request-info", customer), "{\"comment\": \"Which plant?\"}", asManagement));
		mvc.perform(get("/api/companies/{id}", customer).header(HttpHeaders.AUTHORIZATION, asKam))
			.andExpect(jsonPath("$.reviewComment").value("Which plant?"));
		mvc.perform(post("/api/companies/{id}/send-for-approval", customer).header(HttpHeaders.AUTHORIZATION, asKam));
		mvc.perform(json(post("/api/companies/{id}/approve", customer), "{\"comment\": \"Credit risk high\"}", asManagement))
			.andExpect(jsonPath("$.reviewComment").value("Credit risk high"));

		// once approved, the comment is Management's judgement: not for the KAM, Quality or ERGO RFQ
		for (String token : new String[] { asKam, asQuality, asRfq, asAdmin }) {
			mvc.perform(get("/api/companies/{id}", customer).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(jsonPath("$.reviewComment").doesNotExist());
		}
		mvc.perform(get("/api/companies/{id}", customer).header(HttpHeaders.AUTHORIZATION, asManagement))
			.andExpect(jsonPath("$.reviewComment").value("Credit risk high"));
	}

	@Test
	void customersAndSuppliersCannotSendOrReview() throws Exception {
		long id = pending(asRfq, "SUPPLIER");
		for (Role role : new Role[] { Role.CUSTOMER_RFQ, Role.CUSTOMER_MANAGEMENT, Role.SUPPLIER_RFQ, Role.SUPPLIER_KAM }) {
			Company own = role.companyType() == CUSTOMER ? customerA : supplierX;
			if (role == Role.CUSTOMER_MANAGEMENT) {
				continue; // inactive in MVP: cannot sign in
			}
			String token = accounts.bearer(accounts.user(own, role));
			for (String decision : new String[] { "approve", "reject", "request-info" }) {
				mvc.perform(json(post("/api/companies/{id}/" + decision, id), "{\"comment\": \"x\"}", token))
					.andExpect(status().isForbidden());
			}
			mvc.perform(post("/api/companies/{id}/send-for-approval", own.id).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isForbidden());
		}
	}

	@Test
	void onlyReviewersApproveAndOnlyErgoAdminDeactivates() throws Exception {
		long id = pending(asRfq, "SUPPLIER");
		String asCustomerKam = accounts.bearer(accounts.user(customerA, Role.CUSTOMER_KAM));

		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asRfq)).andExpect(status().isForbidden());
		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asAdmin)).andExpect(status().isForbidden());
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
			.andExpect(jsonPath("$[0].actions", contains("EDIT")))
			.andExpect(jsonPath("$[0].missing").doesNotExist());
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
	void everyCompanyIsApprovedByErgoManagementAlone() throws Exception {
		String otherAdmin = accounts.bearer(accounts.user(ergo, Role.ERGO_ADMIN));
		long[] ids = { pending(asAdmin, "SUPPLIER"), pending(asRfq, "SUPPLIER"), pending(asAdmin, "CUSTOMER"),
				pending(asRfq, "CUSTOMER") };

		for (long id : ids) {
			mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asAdmin))
				.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
			// no Admin (not the author, not another one) and not ERGO RFQ may decide (client question 11)
			for (String token : new String[] { asAdmin, otherAdmin, asRfq }) {
				mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, token))
					.andExpect(jsonPath("$.actions", not(hasItem("APPROVE"))))
					.andExpect(jsonPath("$.actions", not(hasItem("REJECT"))))
					.andExpect(jsonPath("$.actions", not(hasItem("REQUEST_INFO"))));
				mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", token)).andExpect(status().isForbidden());
				mvc.perform(json(post("/api/companies/{id}/reject", id), "{\"comment\": \"no\"}", token))
					.andExpect(status().isForbidden());
			}
			mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asManagement))
				.andExpect(jsonPath("$.actions", hasItem("APPROVE")));
			mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asManagement))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		}
	}

	@Test
	void aDraftCannotBeReviewed() throws Exception {
		long id = idOf(create(asRfq, "CUSTOMER"));
		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asManagement)).andExpect(status().isConflict());
		mvc.perform(json(post("/api/companies/{id}/reject", id), "{\"comment\": \"no\"}", asManagement))
			.andExpect(status().isConflict());
		mvc.perform(json(post("/api/companies/{id}/request-info", id), "{\"comment\": \"no\"}", asManagement))
			.andExpect(status().isConflict());
		mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asManagement))
			.andExpect(jsonPath("$.actions", empty()));
	}

	@Test
	void aRejectedSupplierIsResubmittedAndStaysOffTheApprovedListUntilManagementApproves() throws Exception {
		long id = pending(asAdmin, "SUPPLIER");
		mvc.perform(json(post("/api/companies/{id}/reject", id), "{\"comment\": \"Certificates missing\"}",
				asManagement))
			.andExpect(jsonPath("$.status").value("REJECTED"));
		mvc.perform(post("/api/companies/{id}/send-for-approval", id).header(HttpHeaders.AUTHORIZATION, asAdmin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

		approvedSuppliers(asManagement).andExpect(jsonPath("$[*].id", not(hasItem((int) id))));
		mvc.perform(json(post("/api/companies/{id}/approve", id), "{}", asManagement)).andExpect(status().isOk());
		approvedSuppliers(asManagement).andExpect(jsonPath("$[*].id", hasItem((int) id)));
	}

	@Test
	void theCompanyListCanBeNarrowedToApprovedSuppliers() throws Exception {
		long pending = pending(asRfq, "SUPPLIER");
		long rejected = pending(asRfq, "SUPPLIER");
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

		// Management alone reviews, so only Management is told, for suppliers and customers (client question 31)
		long viaRfq = pending(rfqToken, "SUPPLIER");
		assertThat(recipients("COMPANY_APPROVAL_REQUESTED", viaRfq)).contains(management.id).doesNotContain(admin.id, rfq.id);
		long customerViaRfq = pending(rfqToken, "CUSTOMER");
		assertThat(recipients("COMPANY_APPROVAL_REQUESTED", customerViaRfq)).contains(management.id)
			.doesNotContain(admin.id, rfq.id);

		mvc.perform(json(post("/api/companies/{id}/reject", viaRfq), "{\"comment\": \"No\"}", accounts.bearer(management)))
			.andExpect(status().isOk());
		assertThat(recipients("COMPANY_APPROVAL_DECIDED", viaRfq)).containsExactly(rfq.id);
		mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, rfqToken))
			.andExpect(jsonPath("$.items[0].title").value("Company approval decision recorded"))
			.andExpect(jsonPath("$.items[0].actionUrl").value("/companies"));
	}

	@Test
	void ergoQualityDoesNotSeeCompaniesStillBeingRegisteredOrReviewed() throws Exception {
		long draft = idOf(create(asRfq, "SUPPLIER"));
		long pending = pending(asRfq, "SUPPLIER");
		long rejected = pending(asRfq, "SUPPLIER");
		mvc.perform(json(post("/api/companies/{id}/reject", rejected), "{\"comment\": \"No\"}", asManagement));
		long sentBack = pending(asRfq, "SUPPLIER");
		mvc.perform(json(post("/api/companies/{id}/request-info", sentBack), "{\"comment\": \"Plant?\"}", asManagement));
		String asQuality = accounts.bearer(accounts.user(ergo, Role.ERGO_QUALITY));

		mvc.perform(get("/api/companies").header(HttpHeaders.AUTHORIZATION, asQuality))
			.andExpect(jsonPath("$[*].id", hasItem(supplierX.id.intValue())))
			.andExpect(jsonPath("$[*].status", not(hasItem("DRAFT"))))
			.andExpect(jsonPath("$[*].status", not(hasItem("PENDING_APPROVAL"))))
			.andExpect(jsonPath("$[*].status", not(hasItem("REJECTED"))))
			.andExpect(jsonPath("$[*].status", not(hasItem("INFO_REQUESTED"))));
		for (long id : new long[] { draft, pending, rejected, sentBack }) {
			mvc.perform(get("/api/companies/{id}", id).header(HttpHeaders.AUTHORIZATION, asQuality))
				.andExpect(status().isNotFound());
		}
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
		long id = pending(asRfq, "SUPPLIER");
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

	/** Everything the client's minimum list (question 15) asks for, so the company can be sent for approval. */
	private static final String FULL_PROFILE = """
			{"name": "%s", "addressLine1": "Plot 4, MIDC", "city": "Pune", "country": "India", "taxId": "29ABCDE1234F1Z5",
			 "primaryContactName": "Asha Rao", "primaryContactEmail": "asha@example.dev"}""";

	private ResultActions create(String token, String type) throws Exception {
		int n = SEQ.incrementAndGet();
		return mvc.perform(json(post("/api/companies"),
				"{\"type\": \"%s\", \"profile\": %s}".formatted(type, FULL_PROFILE.formatted("New company " + n)), token));
	}

	/** Created and sent for approval by the same caller. */
	private long pending(String token, String type) throws Exception {
		long id = idOf(create(token, type));
		mvc.perform(post("/api/companies/{id}/send-for-approval", id).header(HttpHeaders.AUTHORIZATION, token))
			.andExpect(status().isOk());
		return id;
	}

	private static long idOf(ResultActions created) throws Exception {
		return JsonPath.<Number>read(created.andReturn().getResponse().getContentAsString(), "$.id").longValue();
	}

	private static String codeOf(ResultActions created) throws Exception {
		return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.code");
	}

	private static String profile(String name) {
		return "{\"name\": \"%s\", \"city\": \"Pune\", \"primaryContactPhone\": \"+91 20 5555 0000\"}".formatted(name);
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(body);
	}

}
