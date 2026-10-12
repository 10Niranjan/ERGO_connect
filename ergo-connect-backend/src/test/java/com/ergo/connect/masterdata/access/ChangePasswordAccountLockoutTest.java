package com.ergo.connect.masterdata.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;

/**
 * Phase 8d-bis (ARCHITECTURE.md §8): a wrong current password now also counts against the same account lockout
 * {@link AuthService#login} uses, so change-password keeps a DB-backed backstop even if Redis -
 * {@link ChangePasswordRateLimiter}'s only protection - is unavailable (gap the 8d-bis security review found,
 * user chose to merge the two budgets rather than fail closed or accept the gap). The Redis limit is raised far
 * above 5 here so only the DB lockout - not {@code ChangePasswordRateLimiter} - can be the thing that blocks.
 */
@IntegrationTest
@TestPropertySource(properties = "ergo.security.change-password-rate-limit.max-attempts=1000")
class ChangePasswordAccountLockoutTest {

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

	Accounts accounts;

	@BeforeEach
	void setUp() {
		accounts = new Accounts(companies, users, encoder, mvc, otpCodes);
	}

	@Test
	void fiveWrongCurrentPasswordsLockTheAccountOutOfLoginToo() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String token = accounts.bearer(user);

		for (int i = 0; i < 5; i++) {
			changePassword(token, "wrong-current-pw", "A-brand-new-pass-1").andExpect(status().isUnprocessableContent());
		}

		// the two budgets are now merged: correct login credentials are blocked too, not just more change-password tries
		accounts.login(user.email, Accounts.PASSWORD).andExpect(status().isUnauthorized());
	}

	@Test
	void aLockedAccountIsRejectedBeforeCheckingThePassword() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String token = accounts.bearer(user);
		for (int i = 0; i < 5; i++) {
			changePassword(token, "wrong-current-pw", "A-brand-new-pass-1").andExpect(status().isUnprocessableContent());
		}

		changePassword(token, Accounts.PASSWORD, "A-brand-new-pass-1").andExpect(status().isTooManyRequests());
	}

	private ResultActions changePassword(String token, String currentPassword, String newPassword) throws Exception {
		return mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"currentPassword": "%s", "newPassword": "%s"}""".formatted(currentPassword, newPassword)));
	}

}
