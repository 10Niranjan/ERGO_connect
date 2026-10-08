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
 * Phase 8d-bis (ARCHITECTURE.md §8): proves {@link ChangePasswordRateLimiter} is actually wired into
 * {@code /api/auth/change-password}, against a real Redis (Testcontainers) - keyed by user id, so (unlike
 * {@link LoginRateLimiterEndpointTest}) every test's own freshly-created user is already its own isolated bucket
 * and the shared-context default doesn't need relaxing.
 */
@IntegrationTest
@TestPropertySource(properties = "ergo.security.change-password-rate-limit.max-attempts=3")
class ChangePasswordRateLimiterEndpointTest {

	private static final int LIMIT = 3;

	@Autowired
	MockMvc mvc;

	@Autowired
	CompanyRepository companies;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	Accounts accounts;

	@BeforeEach
	void setUp() {
		accounts = new Accounts(companies, users, encoder, mvc);
	}

	@Test
	void blocksAfterTooManyWrongCurrentPasswords() throws Exception {
		String token = accounts.bearer(accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ));

		for (int i = 0; i < LIMIT; i++) {
			changePassword(token, "wrong-current-pw", "A-brand-new-pass-1").andExpect(status().isUnprocessableContent());
		}
		changePassword(token, "wrong-current-pw", "A-brand-new-pass-1").andExpect(status().isTooManyRequests());
	}

	@Test
	void differentUsersHaveIndependentCounters() throws Exception {
		String blocked = accounts.bearer(accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ));
		String other = accounts.bearer(accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ));
		for (int i = 0; i < LIMIT; i++) {
			changePassword(blocked, "wrong-current-pw", "A-brand-new-pass-1").andExpect(status().isUnprocessableContent());
		}
		changePassword(blocked, "wrong-current-pw", "A-brand-new-pass-1").andExpect(status().isTooManyRequests());

		changePassword(other, "wrong-current-pw", "A-brand-new-pass-1").andExpect(status().isUnprocessableContent());
	}

	@Test
	void aSuccessfulChangeGivesBackItsOwnSlot() throws Exception {
		String token = accounts.bearer(accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ));

		for (int i = 0; i < LIMIT - 1; i++) {
			changePassword(token, "wrong-current-pw", "A-brand-new-pass-1").andExpect(status().isUnprocessableContent());
		}
		changePassword(token, Accounts.PASSWORD, "A-brand-new-pass-1").andExpect(status().isOk());
		// the slot the successful change gave back means one more wrong guess is still tolerated, not blocked
		changePassword(token, "wrong-current-pw", "A-brand-new-pass-2").andExpect(status().isUnprocessableContent());
	}

	@Test
	void correctCurrentPasswordPastTheLimitIsStillBlockedAndThePasswordIsUnchanged() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String token = accounts.bearer(user);
		for (int i = 0; i < LIMIT; i++) {
			changePassword(token, "wrong-current-pw", "A-brand-new-pass-1").andExpect(status().isUnprocessableContent());
		}

		changePassword(token, Accounts.PASSWORD, "A-brand-new-pass-1").andExpect(status().isTooManyRequests());

		// never actually changed - the original password still works
		accounts.login(user.email, Accounts.PASSWORD).andExpect(status().isOk());
	}

	private ResultActions changePassword(String token, String currentPassword, String newPassword) throws Exception {
		return mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"currentPassword": "%s", "newPassword": "%s"}""".formatted(currentPassword, newPassword)));
	}

}
