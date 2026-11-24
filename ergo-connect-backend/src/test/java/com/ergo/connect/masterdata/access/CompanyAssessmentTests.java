package com.ergo.connect.masterdata.access;

import static com.ergo.connect.common.security.CompanyType.CUSTOMER;
import static com.ergo.connect.common.security.CompanyType.ERGO;
import static com.ergo.connect.common.security.CompanyType.SUPPLIER;
import static com.ergo.connect.masterdata.access.Audits.CUSTOMER_ASSESSMENT;
import static com.ergo.connect.masterdata.access.Audits.SUPPLIER_AUDIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.security.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * Registration step 3 (client questions 6, 9, 14, 28, 29): supplier audit reports by ERGO Quality, customer
 * assessments by the ERGO team that registers customers, ERGO-internal only, kept as unchangeable history.
 */
@IntegrationTest
class CompanyAssessmentTests {

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

	Company supplierX;

	String asAdmin;

	String asManagement;

	String asRfq;

	String asQuality;

	@BeforeEach
	void setUp() throws Exception {
		accounts = new Accounts(companies, users, encoder, mvc, otpCodes);
		ergo = accounts.company(ERGO);
		customerA = accounts.company(CUSTOMER);
		supplierX = accounts.company(SUPPLIER);
		asAdmin = accounts.bearer(accounts.user(ergo, Role.ERGO_ADMIN));
		asManagement = accounts.bearer(accounts.user(ergo, Role.ERGO_MANAGEMENT));
		asRfq = accounts.bearer(accounts.user(ergo, Role.ERGO_RFQ));
		asQuality = accounts.bearer(accounts.user(ergo, Role.ERGO_QUALITY));
	}

	@Test
	void qualityAddsASupplierAuditWithItsReportAndErgoSeesIt() throws Exception {
		long supplier = draft("SUPPLIER");
		Audits.add(mvc, asQuality, supplier, SUPPLIER_AUDIT, "plant-audit.pdf")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("SUPPLIER_AUDIT"))
			.andExpect(jsonPath("$.auditType").value("ON_SITE"))
			.andExpect(jsonPath("$.recommendation").value("APPROVE"))
			.andExpect(jsonPath("$.riskRating").doesNotExist())
			.andExpect(jsonPath("$.file.fileName").value("plant-audit.pdf"));

		for (String token : new String[] { asAdmin, asManagement, asRfq, asQuality }) {
			mvc.perform(get("/api/companies/{id}/assessments", supplier).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("SUPPLIER_AUDIT"))
				.andExpect(jsonPath("$.canAdd").value(token.equals(asQuality)))
				.andExpect(jsonPath("$.items", hasSize(1)));
		}
		long assessmentId = JsonPath.<Number>read(mvc
			.perform(get("/api/companies/{id}/assessments", supplier).header(HttpHeaders.AUTHORIZATION, asManagement))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.items[0].id").longValue();
		mvc.perform(get("/api/companies/{id}/assessments/{a}/file", supplier, assessmentId)
			.header(HttpHeaders.AUTHORIZATION, asManagement))
			.andExpect(status().isOk())
			.andExpect(content().string("%PDF-1.4 audit"))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
				.string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")));
		// the file is reachable only through its company: another company id does not find it
		mvc.perform(get("/api/companies/{id}/assessments/{a}/file", supplierX.id, assessmentId)
			.header(HttpHeaders.AUTHORIZATION, asManagement))
			.andExpect(status().isNotFound());
	}

	@Test
	void aSupplierAuditNeedsItsReportFileAndSummary() throws Exception {
		long supplier = draft("SUPPLIER");
		Audits.add(mvc, asQuality, supplier, SUPPLIER_AUDIT, null)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value(containsString("audit report file")));
		Audits.add(mvc, asQuality, supplier, """
				{"assessmentDate": "2026-11-01", "findings": "ok", "recommendation": "APPROVE"}""", "a.pdf")
			.andExpect(status().isUnprocessableContent());
		Audits.add(mvc, asQuality, supplier, """
				{"assessmentDate": "2026-11-01", "auditType": "VIRTUAL", "auditor": "R", "findings": " ",
				 "recommendation": "REJECT"}""", "a.pdf")
			.andExpect(status().isBadRequest()); // findings are required
		Audits.add(mvc, asQuality, supplier, """
				{"assessmentDate": "2999-01-01", "auditType": "VIRTUAL", "auditor": "R", "findings": "x",
				 "recommendation": "REJECT"}""", "a.pdf")
			.andExpect(status().isBadRequest()); // not in the future
		Audits.add(mvc, asQuality, supplier, SUPPLIER_AUDIT, "report.exe").andExpect(status().isUnprocessableContent());
		assertThat(count(supplier)).isZero();
	}

	@Test
	void onlyQualityAddsSupplierAudits() throws Exception {
		long supplier = draft("SUPPLIER");
		for (String token : new String[] { asAdmin, asRfq }) {
			Audits.add(mvc, token, supplier, SUPPLIER_AUDIT, "a.pdf").andExpect(status().isForbidden());
		}
		Audits.add(mvc, asManagement, supplier, SUPPLIER_AUDIT, "a.pdf").andExpect(status().isForbidden());
		assertThat(count(supplier)).isZero();
	}

	@Test
	void theErgoCustomerTeamAddsCustomerAssessmentsAndQualityDoesNotSeeThem() throws Exception {
		long customer = draft("CUSTOMER");
		Audits.add(mvc, asRfq, customer, CUSTOMER_ASSESSMENT, null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("CUSTOMER_ASSESSMENT"))
			.andExpect(jsonPath("$.riskRating").value("MEDIUM"))
			.andExpect(jsonPath("$.file").doesNotExist())
			.andExpect(jsonPath("$.auditType").doesNotExist());
		Audits.add(mvc, asAdmin, customer, CUSTOMER_ASSESSMENT, "financials.pdf").andExpect(status().isCreated());
		Audits.add(mvc, asRfq, customer, """
				{"assessmentDate": "2026-11-01", "findings": "no rating"}""", null)
			.andExpect(status().isUnprocessableContent());
		Audits.add(mvc, asManagement, customer, CUSTOMER_ASSESSMENT, null).andExpect(status().isForbidden());

		// Quality's work is supplier audits: a customer (even an approved one) does not exist for it here
		Audits.add(mvc, asQuality, customer, CUSTOMER_ASSESSMENT, null).andExpect(status().isNotFound());
		for (long id : new long[] { customer, customerA.id }) {
			mvc.perform(get("/api/companies/{id}/assessments", id).header(HttpHeaders.AUTHORIZATION, asQuality))
				.andExpect(status().isNotFound());
		}
		mvc.perform(get("/api/companies/{id}/assessments", customer).header(HttpHeaders.AUTHORIZATION, asManagement))
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.canAdd").value(false));
	}

	@Test
	void keyAccountManagersWorkOnlyOnTheirOwnCustomers() throws Exception {
		User kam = accounts.user(ergo, Role.ERGO_KAM);
		String asKam = accounts.bearer(kam);
		long own = idOf(mvc.perform(json(post("/api/companies"), customerBody(), asKam)).andReturn().getResponse().getContentAsString());
		long other = draft("CUSTOMER");

		Audits.add(mvc, asKam, own, CUSTOMER_ASSESSMENT, null).andExpect(status().isCreated());
		Audits.add(mvc, asKam, other, CUSTOMER_ASSESSMENT, null).andExpect(status().isNotFound());
		mvc.perform(get("/api/companies/{id}/assessments", other).header(HttpHeaders.AUTHORIZATION, asKam))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/companies/{id}/assessments", supplierX.id).header(HttpHeaders.AUTHORIZATION, asKam))
			.andExpect(status().isNotFound());
	}

	@Test
	void customersSuppliersAndOtherErgoRolesNeverReachThem() throws Exception {
		Audits.supplierAudit(mvc, asQuality, supplierX.id);
		Audits.add(mvc, asRfq, customerA.id, CUSTOMER_ASSESSMENT, null).andExpect(status().isCreated());
		long auditId = JsonPath.<Number>read(mvc
			.perform(get("/api/companies/{id}/assessments", supplierX.id).header(HttpHeaders.AUTHORIZATION, asQuality))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.items[0].id").longValue();

		String[] outsiders = { accounts.bearer(accounts.user(supplierX, Role.SUPPLIER_RFQ)),
				accounts.bearer(accounts.user(supplierX, Role.SUPPLIER_KAM)),
				accounts.bearer(accounts.user(customerA, Role.CUSTOMER_RFQ)),
				accounts.bearer(accounts.user(customerA, Role.CUSTOMER_KAM)),
				accounts.bearer(accounts.user(ergo, Role.ERGO_NPD)), accounts.bearer(accounts.user(ergo, Role.ERGO_OFP)) };
		for (String token : outsiders) {
			for (long id : new long[] { supplierX.id, customerA.id }) {
				mvc.perform(get("/api/companies/{id}/assessments", id).header(HttpHeaders.AUTHORIZATION, token))
					.andExpect(status().isForbidden());
				Audits.add(mvc, token, id, id == supplierX.id ? SUPPLIER_AUDIT : CUSTOMER_ASSESSMENT, "a.pdf")
					.andExpect(status().isForbidden());
			}
			mvc.perform(get("/api/companies/{id}/assessments/{a}/file", supplierX.id, auditId)
				.header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isForbidden());
		}
		// the company's own view carries no hint of them (client question 9)
		String own = mvc.perform(get("/api/companies/{id}", supplierX.id).header(HttpHeaders.AUTHORIZATION, outsiders[0]))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(own).doesNotContain("VIEW_ASSESSMENTS", "audit");
	}

	@Test
	void reportFilesNeverAppearInTheDocumentLibrary() throws Exception {
		long supplier = draft("SUPPLIER");
		Audits.add(mvc, asQuality, supplier, SUPPLIER_AUDIT, "secret-audit-" + supplier + ".pdf")
			.andExpect(status().isCreated());
		for (String token : new String[] { asAdmin, asManagement, asQuality }) {
			mvc.perform(get("/api/documents").param("size", "200").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].fileName", not(hasItem("secret-audit-" + supplier + ".pdf"))));
		}
		long documentId = jdbc.queryForObject(
				"SELECT document_id FROM company_assessments WHERE company_id = ?", Long.class, supplier);
		mvc.perform(get("/api/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, asAdmin))
			.andExpect(status().isNotFound());
	}

	@Test
	void theHistoryCannotBeChangedOrMismatchedEvenWithSql() throws Exception {
		long supplier = draft("SUPPLIER");
		Audits.supplierAudit(mvc, asQuality, supplier);
		assertThatThrownBy(() -> jdbc.update("UPDATE company_assessments SET findings = 'changed' WHERE company_id = ?", supplier))
			.hasMessageContaining("cannot be changed or deleted");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM company_assessments WHERE company_id = ?", supplier))
			.hasMessageContaining("cannot be changed or deleted");
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO company_assessments (company_id, kind, assessment_date, findings, risk_rating, created_by_user_id)
				VALUES (?, 'CUSTOMER_ASSESSMENT', current_date, 'x', 'LOW', 1)""", supplier))
			.hasMessageContaining("belongs to a supplier");
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_logs WHERE entity_name = 'CompanyAssessment' AND event_type = 'CREATE'
				AND record_id = (SELECT assessment_id FROM company_assessments WHERE company_id = ?)""", Long.class,
				supplier)).isEqualTo(1);
	}

	@Test
	void anAuditReportLetsTheSupplierBeSentForApproval() throws Exception {
		long supplier = draft("SUPPLIER");
		mvc.perform(post("/api/companies/{id}/send-for-approval", supplier).header(HttpHeaders.AUTHORIZATION, asRfq))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value(containsString("audit report")));
		Audits.supplierAudit(mvc, asQuality, supplier);
		mvc.perform(post("/api/companies/{id}/send-for-approval", supplier).header(HttpHeaders.AUTHORIZATION, asRfq))
			.andExpect(status().isOk());
		// a customer needs no assessment to be sent (question 14: audit reports for suppliers only)
		mvc.perform(post("/api/companies/{id}/send-for-approval", draft("CUSTOMER")).header(HttpHeaders.AUTHORIZATION, asRfq))
			.andExpect(status().isOk());
	}

	@Test
	void aSupplierPendingFromBeforeTheAuditRuleIsNotApprovedWithoutOne() throws Exception {
		Company legacy = accounts.company(SUPPLIER);
		jdbc.update("UPDATE companies SET status = 'PENDING_APPROVAL' WHERE company_id = ?", legacy.id);
		mvc.perform(json(post("/api/companies/{id}/approve", legacy.id), "{}", asManagement))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value(containsString("audit report")));
		Audits.supplierAudit(mvc, asQuality, legacy.id);
		mvc.perform(json(post("/api/companies/{id}/approve", legacy.id), "{}", asManagement)).andExpect(status().isOk());
	}

	@Test
	void theReportFileIsUnreachableThroughEveryDocumentsEndpoint() throws Exception {
		long supplier = draft("SUPPLIER");
		Audits.supplierAudit(mvc, asQuality, supplier);
		long documentId = jdbc.queryForObject(
				"SELECT document_id FROM company_assessments WHERE company_id = ?", Long.class, supplier);
		for (String token : new String[] { asAdmin, asManagement, asQuality }) {
			mvc.perform(get("/api/documents/{id}/content", documentId).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isNotFound());
			for (String verb : new String[] { "lock", "unlock", "approve" }) {
				mvc.perform(json(post("/api/documents/{id}/" + verb, documentId), "{}", token))
					.andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(403, 404));
			}
		}
	}

	// ---------------------------------------------------------------- helpers

	private long draft(String type) throws Exception {
		String body = "{\"type\": \"%s\", \"profile\": %s}".formatted(type, profile());
		return idOf(mvc.perform(json(post("/api/companies"), body, asRfq)).andExpect(status().isCreated()).andReturn()
			.getResponse().getContentAsString());
	}

	private static String customerBody() {
		return "{\"type\": \"CUSTOMER\", \"profile\": %s}".formatted(profile());
	}

	private static String profile() {
		return """
				{"name": "Assessed %d", "addressLine1": "Plot 4", "city": "Pune", "country": "India", "taxId": "29ABCDE1234F1Z5",
				 "primaryContactName": "Asha Rao", "primaryContactEmail": "asha@example.dev"}""".formatted(SEQ.incrementAndGet());
	}

	private long count(long companyId) {
		return jdbc.queryForObject("SELECT count(*) FROM company_assessments WHERE company_id = ?", Long.class, companyId);
	}

	private static long idOf(String body) {
		return JsonPath.<Number>read(body, "$.id").longValue();
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(body);
	}

}
