package com.ergo.connect.common.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ergo.connect.IntegrationTest;

/**
 * Postgres puts the whole failing row into a constraint-violation message by default. Prices and hidden margins would
 * then reach logs and monitoring, so the driver is told not to (application.yml, Phase 8m security review M3).
 */
@IntegrationTest
class DriverErrorDetailTest {

	@Autowired
	DataSource dataSource;

	@Test
	void constraintErrorKeepsTheConstraintNameButNotTheRowValues() throws SQLException {
		try (var connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
			st.execute("create temp table detail_probe(price numeric not null check (price > 0), margin numeric)");
			assertThatThrownBy(() -> st.execute("insert into detail_probe values (-12345.67, 98.76)"))
					.isInstanceOf(SQLException.class)
					.message()
					.contains("detail_probe_price_check")
					.doesNotContain("12345.67")
					.doesNotContain("98.76")
					.doesNotContain("Failing row");
		}
	}
}
