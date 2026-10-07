package com.ergo.connect.admin;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;

/** Phase 7e (ARCHITECTURE.md §8, 2026-10-07): a read-only reference screen, ERGO Admin only. */
@IntegrationTest
class PermissionMatrixTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	Login ergoAdmin;

	Login ergoManagement;

	Login customerRfq;

	Login supplierRfq;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		ergoAdmin = users.login(users.company("ERGO"), "ERGO_ADMIN");
		ergoManagement = users.login(users.company("ERGO"), "ERGO_MANAGEMENT");
		customerRfq = users.login(users.company("CUSTOMER"), "CUSTOMER_RFQ");
		supplierRfq = users.login(users.company("SUPPLIER"), "SUPPLIER_RFQ");
	}

	@Test
	void ergoAdminSeesTheFullMatrix() throws Exception {
		mvc.perform(get("/api/admin/permission-matrix").header(HttpHeaders.AUTHORIZATION, ergoAdmin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.legend.length()", greaterThanOrEqualTo(7)))
			.andExpect(jsonPath("$.modules.length()").value(12))
			.andExpect(jsonPath("$.modules[0].section").value("10.1"))
			.andExpect(jsonPath("$.modules[0].roles[0].role").value("ERGO_ADMIN"))
			.andExpect(jsonPath("$.modules[0].roles[0].view.value").value("V"))
			.andExpect(jsonPath("$.modules[0].roles[0].view.footnote").doesNotExist())
			.andExpect(jsonPath("$.modules[11].section").value("10.12"))
			.andExpect(jsonPath("$.modules[11].notes[0]").value(org.hamcrest.Matchers.containsString("dual control")))
			// §10.12 ERGO Management: approve is footnoted A*, the other three columns are plain X (security review finding).
			.andExpect(jsonPath("$.modules[11].roles[1].role").value("ERGO_MANAGEMENT"))
			.andExpect(jsonPath("$.modules[11].roles[1].approve.value").value("A"))
			.andExpect(jsonPath("$.modules[11].roles[1].approve.footnote").value("A*"))
			.andExpect(jsonPath("$.modules[11].roles[1].edit.footnote").doesNotExist());
	}

	@Test
	void noOtherRoleMayOpenIt() throws Exception {
		mvc.perform(get("/api/admin/permission-matrix").header(HttpHeaders.AUTHORIZATION, ergoManagement.bearer()))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/permission-matrix").header(HttpHeaders.AUTHORIZATION, customerRfq.bearer()))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/permission-matrix").header(HttpHeaders.AUTHORIZATION, supplierRfq.bearer()))
			.andExpect(status().isForbidden());
	}

}
