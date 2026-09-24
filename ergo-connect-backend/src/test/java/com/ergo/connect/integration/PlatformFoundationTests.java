package com.ergo.connect.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.ergo.connect.IntegrationTest;

/** Phase 0 guarantees: app boots on real Postgres + Flyway, deny-by-default security, strict CORS, append-only audit log. */
@IntegrationTest
class PlatformFoundationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void everythingElseRequiresAuthentication() throws Exception {
		mvc.perform(get("/api/rfq")).andExpect(status().isUnauthorized());
	}

	@Test
	void corsRejectsUnlistedOrigin() throws Exception {
		mvc.perform(options("/api/rfq")
				.header("Origin", "https://evil.example")
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(status().isForbidden());
		mvc.perform(options("/api/rfq")
				.header("Origin", "http://localhost:5173")
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(status().isOk());
	}

	@Test
	void auditLogIsAppendOnly() {
		Long id = jdbc.queryForObject("""
				INSERT INTO audit_logs (module_name, entity_name, record_id, event_type, source_channel)
				VALUES ('test', 'Test', 1, 'CREATE', 'SYSTEM') RETURNING audit_log_id""", Long.class);
		assertThat(id).isNotNull();

		assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET new_value = 'x' WHERE audit_log_id = ?", id))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE audit_log_id = ?", id))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("append-only");
	}

}
