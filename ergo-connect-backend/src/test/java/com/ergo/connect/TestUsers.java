package com.ergo.connect;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/** Fresh companies and signed-in users for any module's tests (tests share one database). */
public class TestUsers {

	/** A signed-in user; {@code bearer} is the Authorization header value. */
	public record Login(long userId, long companyId, String bearer) {
	}

	private static final AtomicInteger SEQ = new AtomicInteger(100_000);

	private static final String PASSWORD = "Test-password-9";

	private final JdbcTemplate jdbc;

	private final MockMvc mvc;

	public TestUsers(JdbcTemplate jdbc, MockMvc mvc) {
		this.jdbc = jdbc;
		this.mvc = mvc;
	}

	/** An active company of type ERGO, CUSTOMER or SUPPLIER. */
	public long company(String type) {
		int n = SEQ.incrementAndGet();
		return jdbc.queryForObject("""
				INSERT INTO companies (company_code, company_name, company_type, status)
				VALUES (?, ?, ?, 'ACTIVE') RETURNING company_id""", Long.class, "TU" + n, "Test " + type + " " + n, type);
	}

	/** A new user of that company with these roles (the first is the default), signed in. */
	public Login login(long companyId, String... roles) throws Exception {
		int n = SEQ.incrementAndGet();
		String email = "tu" + n + "@test.dev";
		long user = jdbc.queryForObject("""
				INSERT INTO users (company_id, first_name, last_name, email, password_hash, must_change_password,
				                   default_role_code)
				VALUES (?, 'Test', ?, ?, ?, false, ?) RETURNING user_id""", Long.class, companyId, "User " + n, email,
				"{noop}" + PASSWORD, roles[0]);
		for (String role : roles) {
			jdbc.update("INSERT INTO user_role_mappings (user_id, role_code) VALUES (?, ?)", user, role);
		}
		String session = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, PASSWORD)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Login(user, companyId, "Bearer " + JsonPath.read(session, "$.accessToken"));
	}

	/** Customer accounts of an ERGO Key Account Manager. */
	public void assignAccounts(long userId, long... customerIds) {
		for (long customer : customerIds) {
			jdbc.update("INSERT INTO user_account_assignments (user_id, customer_company_id) VALUES (?, ?)", userId,
					customer);
		}
	}

	/** An active entry of a master list, e.g. {@code masterEntry("materials", "material")}. */
	public long masterEntry(String table, String columnPrefix) {
		int n = SEQ.incrementAndGet();
		String extra = table.equals("processes") ? ", process_type" : "";
		String extraValue = table.equals("processes") ? ", 'INHOUSE'" : "";
		return jdbc.queryForObject("INSERT INTO %s (%s_code, %s_name%s) VALUES (?, ?%s) RETURNING %s_id"
			.formatted(table, columnPrefix, columnPrefix, extra, extraValue, columnPrefix), Long.class, "M" + n,
				"Test " + columnPrefix + " " + n);
	}

	public long currency(String code) {
		return jdbc.queryForObject("SELECT currency_id FROM currencies WHERE currency_code = ?", Long.class, code);
	}

}
