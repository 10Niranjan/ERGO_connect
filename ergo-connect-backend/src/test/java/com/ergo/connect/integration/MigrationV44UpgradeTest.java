package com.ergo.connect.integration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V44 on a database that already holds RFQs (what production and the demo have): the old numbers become the suppliers'
 * reference so nothing a supplier already sees changes, new RFQs get a fresh one, and old rows keep working.
 */
@Testcontainers
class MigrationV44UpgradeTest {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

	private Flyway flyway(String target) {
		return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration").target(target).load();
	}

	@Test
	void existingRfqsKeepTheirNumberAsTheSupplierReferenceAndNewOnesGetAFreshOne() {
		flyway("43").migrate();
		DataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		JdbcTemplate jdbc = new JdbcTemplate(ds);

		long customer = jdbc.queryForObject("""
				INSERT INTO companies (company_code, company_name, company_type, status)
				VALUES ('OLD-CUST', 'Old Customer', 'CUSTOMER', 'ACTIVE') RETURNING company_id""", Long.class);
		long user = jdbc.queryForObject("""
				INSERT INTO users (company_id, first_name, last_name, email, password_hash, must_change_password, default_role_code)
				VALUES (?, 'Old', 'User', 'old@example.test', '{noop}x', false, 'CUSTOMER_RFQ') RETURNING user_id""", Long.class, customer);
		long inr = jdbc.queryForObject("SELECT currency_id FROM currencies WHERE currency_code = 'INR'", Long.class);
		// two RFQs numbered the way V4-V43 numbered them, one of them with a part and a file
		for (int i = 1; i <= 2; i++) {
			jdbc.update("""
					INSERT INTO rfqs (rfq_number, source_type, customer_company_id, created_by_user_id, status, priority, currency_id)
					VALUES (?, 'CUSTOMER', ?, ?, 'SUBMITTED', 'NORMAL', ?)""", "RFQ-2026-0000" + i, customer, user, inr);
		}
		long first = jdbc.queryForObject("SELECT rfq_id FROM rfqs WHERE rfq_number = 'RFQ-2026-00001'", Long.class);
		jdbc.update("INSERT INTO rfq_items (rfq_id, line_no, part_number) VALUES (?, 1, 'PN-OLD')", first);

		flyway("44").migrate();

		assertThat(jdbc.queryForList("SELECT supplier_ref FROM rfqs ORDER BY rfq_id", String.class))
			.containsExactly("RFQ-2026-00001", "RFQ-2026-00002");
		// the new columns are empty on old rows, so nothing about them changes; the part is intact
		assertThat(jdbc.queryForObject("SELECT title FROM rfqs WHERE rfq_id = ?", String.class, first)).isNull();
		assertThat(jdbc.queryForObject("SELECT remarks FROM rfq_items WHERE rfq_id = ?", String.class, first)).isNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM rfq_items WHERE part_number = 'PN-OLD'", Integer.class)).isEqualTo(1);

		// an RFQ inserted the old way afterwards (no supplier_ref) gets a unique reference, never a duplicate
		jdbc.update("""
				INSERT INTO rfqs (rfq_number, source_type, customer_company_id, created_by_user_id, status, priority, currency_id)
				VALUES ('AA-2026-001', 'CUSTOMER', ?, ?, 'DRAFT', 'NORMAL', ?)""", customer, user, inr);
		String fresh = jdbc.queryForObject("SELECT supplier_ref FROM rfqs WHERE rfq_number = 'AA-2026-001'", String.class);
		assertThat(fresh).matches("RFQ-\\d{4}-\\d{5}").isNotIn("RFQ-2026-00001", "RFQ-2026-00002");
		assertThat(jdbc.queryForObject("SELECT count(DISTINCT supplier_ref) FROM rfqs", Integer.class)).isEqualTo(3);

		// the new tables and currencies are there
		assertThat(jdbc.queryForList("SELECT currency_code FROM currencies WHERE currency_code IN ('GBP','AUD','CAD')", String.class))
			.hasSize(3);
	}

}
