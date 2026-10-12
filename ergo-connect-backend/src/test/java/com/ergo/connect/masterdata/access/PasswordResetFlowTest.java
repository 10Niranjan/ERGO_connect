package com.ergo.connect.masterdata.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;

import jakarta.servlet.http.Cookie;

/**
 * Phase 8f (ARCHITECTURE.md §8): the forgot-password + reset-password flow end to end, against the real
 * password_reset_tokens table. {@link PasswordResetTokenStore} is autowired directly (package-private, same
 * package) to mint a known raw token for most tests, the same way {@link Accounts} and
 * {@code DevDataSeeder.PASSWORD} are already reached into from this package's other tests - there's no other
 * way to recover a raw token once {@link AuthService#forgotPassword} only ever hands it to the (log-only, in
 * this test profile) mailer. {@link PasswordResetRequestHandlerTest} covers the skip-this-user branching with
 * mocks; the tests here that go through the real {@code /forgot-password} endpoint prove the wiring, and poll
 * briefly since that work now happens off the request thread (security review finding "H2").
 */
@IntegrationTest
class PasswordResetFlowTest {

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

	@Autowired
	PasswordResetTokenStore resetTokens;

	Accounts accounts;

	@BeforeEach
	void setUp() {
		accounts = new Accounts(companies, users, encoder, mvc, otpCodes);
	}

	@Test
	void forgotPasswordRespondsTheSameWhetherOrNotTheEmailIsRegistered() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);

		forgotPassword(user.email).andExpect(status().isNoContent());
		forgotPassword("nobody-registered@test.dev").andExpect(status().isNoContent());

		awaitTokenCount(user.id, 1); // only the real user actually got a token issued, once the async work runs
	}

	@Test
	void anInactiveUserGetsNoTokenEvenThoughTheResponseIsIdentical() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		user.active = false;
		users.save(user);

		forgotPassword(user.email).andExpect(status().isNoContent());

		Thread.sleep(300); // can't "await" an absence - a short wait is enough for a local DB round trip
		assertThat(countTokensFor(user.id)).isZero();
	}

	@Test
	void aFreshTokenActuallyResetsThePasswordAndSignsOutOtherSessions() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		Cookie oldSession = Accounts.sessionCookie(accounts.login(user));

		String token = resetTokens.create(user.id, Instant.now().plus(Duration.ofMinutes(30)));
		resetPassword(token, "A-brand-new-pass-1").andExpect(status().isNoContent());

		mvc.perform(post("/api/auth/refresh").cookie(oldSession)).andExpect(status().isUnauthorized());
		accounts.login(user.email, Accounts.PASSWORD).andExpect(status().isUnauthorized());
		accounts.login(user.email, "A-brand-new-pass-1").andExpect(status().isOk());
	}

	@Test
	void changePasswordAlsoInvalidatesAnyOutstandingResetToken() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String bearer = accounts.bearer(user);
		String token = resetTokens.create(user.id, Instant.now().plus(Duration.ofMinutes(30)));

		mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"currentPassword": "%s", "newPassword": "A-brand-new-pass-1"}""".formatted(Accounts.PASSWORD)))
			.andExpect(status().isOk());

		// the link mailed earlier must not still work once the password was changed through the normal flow
		resetPassword(token, "Another-new-pass-2").andExpect(status().isUnprocessableContent());
	}

	@Test
	void aTokenCanOnlyBeUsedOnce() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String token = resetTokens.create(user.id, Instant.now().plus(Duration.ofMinutes(30)));

		resetPassword(token, "A-brand-new-pass-1").andExpect(status().isNoContent());
		resetPassword(token, "Another-new-pass-2").andExpect(status().isUnprocessableContent());
	}

	@Test
	void aSuccessfulResetInvalidatesOtherOutstandingTokens() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String used = resetTokens.create(user.id, Instant.now().plus(Duration.ofMinutes(30)));
		String stillOutstanding = resetTokens.create(user.id, Instant.now().plus(Duration.ofMinutes(30)));

		resetPassword(used, "A-brand-new-pass-1").andExpect(status().isNoContent());

		// the other link, mailed earlier and never used, must not still work after the reset
		resetPassword(stillOutstanding, "Another-new-pass-2").andExpect(status().isUnprocessableContent());
	}

	@Test
	void anOverlyLongPasswordDoesNotBurnTheToken() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String token = resetTokens.create(user.id, Instant.now().plus(Duration.ofMinutes(30)));
		String tooLong = "€".repeat(25); // 25 chars (within the 10-64 DTO bound) but 75 UTF-8 bytes (€ = 3 bytes)

		resetPassword(token, tooLong).andExpect(status().isUnprocessableContent());
		// the token is still good - rejecting the password didn't consume it
		resetPassword(token, "A-brand-new-pass-1").andExpect(status().isNoContent());
	}

	@Test
	void anExpiredTokenIsRejected() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String token = resetTokens.create(user.id, Instant.now().minus(Duration.ofMinutes(1)));

		resetPassword(token, "A-brand-new-pass-1").andExpect(status().isUnprocessableContent());
	}

	@Test
	void anUnknownTokenIsRejected() throws Exception {
		resetPassword("not-a-real-token", "A-brand-new-pass-1").andExpect(status().isUnprocessableContent());
	}

	@Test
	void aSuccessfulResetAlsoUnlocksAnAccountThatWasLockedOut() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		for (int i = 0; i < 5; i++) {
			accounts.login(user.email, "wrong-password").andExpect(status().isUnauthorized());
		}
		accounts.login(user.email, Accounts.PASSWORD).andExpect(status().isUnauthorized()); // locked now

		String token = resetTokens.create(user.id, Instant.now().plus(Duration.ofMinutes(30)));
		resetPassword(token, "A-brand-new-pass-1").andExpect(status().isNoContent());

		accounts.login(user.email, "A-brand-new-pass-1").andExpect(status().isOk());
	}

	@Test
	void aSuccessfulResetWritesAnAuditRecord() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String token = resetTokens.create(user.id, Instant.now().plus(Duration.ofMinutes(30)));

		resetPassword(token, "A-brand-new-pass-1").andExpect(status().isNoContent());

		List<String> events = jdbc.queryForList(
				"SELECT event_type FROM audit_logs WHERE entity_name = 'User' AND record_id = ?", String.class,
				user.id);
		assertThat(events).contains("PASSWORD_RESET_BY_EMAIL");
	}

	private ResultActions forgotPassword(String email) throws Exception {
		return mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"email": "%s"}""".formatted(email))
			.with(request -> {
				request.setRemoteAddr(uniqueIp()); // each call its own IP bucket - this class isn't testing that dimension
				return request;
			}));
	}

	private ResultActions resetPassword(String token, String newPassword) throws Exception {
		return mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"token": "%s", "newPassword": "%s"}""".formatted(token, newPassword)));
	}

	private int countTokensFor(long userId) {
		return jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE user_id = ?", Integer.class,
				userId);
	}

	private void awaitTokenCount(long userId, int expected) throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			if (countTokensFor(userId) == expected) {
				return;
			}
			Thread.sleep(20);
		}
		assertThat(countTokensFor(userId)).isEqualTo(expected);
	}

	/** TEST-NET-2 (RFC 5737), documentation-only - never a real client. */
	private static String uniqueIp() {
		return "198.51.100." + (SEQ.incrementAndGet() % 254 + 1);
	}

}
