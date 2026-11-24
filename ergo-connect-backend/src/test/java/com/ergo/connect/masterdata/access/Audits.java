package com.ergo.connect.masterdata.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Test helper: a supplier audit report or customer assessment through the real multipart endpoint. */
final class Audits {

	static final String SUPPLIER_AUDIT = """
			{"assessmentDate": "2026-11-01", "auditType": "ON_SITE", "auditor": "R. Iyer",
			 "findings": "Calibration records complete; one minor finding on gauge labels.", "recommendation": "APPROVE"}""";

	static final String CUSTOMER_ASSESSMENT = """
			{"assessmentDate": "2026-11-01", "findings": "Desk review of financials and references.", "riskRating": "MEDIUM"}""";

	private Audits() {
	}

	static ResultActions add(MockMvc mvc, String token, long companyId, String json, String fileName) throws Exception {
		var request = multipart("/api/companies/{id}/assessments", companyId)
			.file(new MockMultipartFile("assessment", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8)));
		if (fileName != null) {
			request.file(new MockMultipartFile("file", fileName, "application/pdf", "%PDF-1.4 audit".getBytes(StandardCharsets.UTF_8)));
		}
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token));
	}

	/** The supplier audit report every supplier needs before it can be sent for approval (client question 14). */
	static void supplierAudit(MockMvc mvc, String qualityToken, long supplierId) throws Exception {
		add(mvc, qualityToken, supplierId, SUPPLIER_AUDIT, "audit.pdf")
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
	}

}
