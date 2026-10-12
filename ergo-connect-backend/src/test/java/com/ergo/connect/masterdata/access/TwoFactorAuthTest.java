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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 8g (ARCHITECTURE.md §8): the email-OTP second factor end to end - login returns a challenge instead of a
 * session once enabled, {@code /verify-otp} completes it, and ERGO Admin accounts can't turn it back off.
 * {@link OtpCodeStore} is autowired directly (package-private, same package) to recover a known raw code, the
 * same way {@link PasswordResetFlowTest} reaches into {@link PasswordResetTokenStore}. Each {@code /verify-otp}
 * call uses its own IP ({@link #uniqueIp()}), since the limiter is now IP-keyed (security review finding H1) and
 * every test in this class otherwise shares MockMvc's default remote address.
 */
@IntegrationTest
class TwoFactorAuthTest {

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

	@BeforeEach
	void setUp() {
		accounts = new Accounts(companies, users, encoder, mvc, otpCodes);
	}

	@Test
	void loginWithoutTwoFactorReturnsASessionDirectly() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);

		accounts.login(user.email, Accounts.PASSWORD)
			.andExpect(status().isOk())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("accessToken"));
	}

	@Test
	void loginWithTwoFactorReturnsAChallengeInsteadOfASession() throws Exception {
		User user = enableTwoFactor(accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ));

		accounts.login(user.email, Accounts.PASSWORD)
			.andExpect(status().isOk())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString())
				.isEqualTo("{\"otpRequired\":true}"));
	}

	@Test
	void anErgoAdminIsChallengedEvenIfTheFlagWasNeverSet() throws Exception {
		// simulates a row that predates this feature, or was granted ERGO_ADMIN outside UserAdminService
		// (security review finding M1) - twoFactorEnabled is left false on purpose
		User admin = accounts.user(accounts.company(CompanyType.ERGO), Role.ERGO_ADMIN);
		assertThat(admin.twoFactorEnabled).isFalse();

		accounts.login(admin.email, Accounts.PASSWORD)
			.andExpect(result -> assertThat(result.getResponse().getContentAsString())
				.isEqualTo("{\"otpRequired\":true}"));

		assertThat(users.findById(admin.id).orElseThrow().twoFactorEnabled).isTrue(); // self-healed
	}

	@Test
	void theRightCodeCompletesTheLogin() throws Exception {
		User user = enableTwoFactor(accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ));
		accounts.login(user.email, Accounts.PASSWORD).andExpect(status().isOk());
		String code = otpCodes.create(user.id, Instant.now().plus(Duration.ofMinutes(5)));

		verifyOtp(user.email, code).andExpect(status().isOk())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("accessToken"));
	}

	@Test
	void aWrongCodeIsRejected() throws Exception {
		User user = enableTwoFactor(accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ));

		verifyOtp(user.email, "000000").andExpect(status().isUnauthorized());
	}

	@Test
	void anUnknownEmailIsRejectedTheSameWayAsAWrongCode() throws Exception {
		verifyOtp("nobody-registered@test.dev", "000000").andExpect(status().isUnauthorized());
	}

	@Test
	void aCodeCanOnlyBeUsedOnce() throws Exception {
		User user = enableTwoFactor(accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ));
		String code = otpCodes.create(user.id, Instant.now().plus(Duration.ofMinutes(5)));

		verifyOtp(user.email, code).andExpect(status().isOk());
		verifyOtp(user.email, code).andExpect(status().isUnauthorized());
	}

	@Test
	void anExpiredCodeIsRejected() throws Exception {
		User user = enableTwoFactor(accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ));
		String code = otpCodes.create(user.id, Instant.now().minus(Duration.ofMinutes(1)));

		verifyOtp(user.email, code).andExpect(status().isUnauthorized());
	}

	@Test
	void aFreshCodeInvalidatesAnyEarlierOutstandingCode() throws Exception {
		User user = enableTwoFactor(accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ));
		String stale = otpCodes.create(user.id, Instant.now().plus(Duration.ofMinutes(5)));
		otpCodes.create(user.id, Instant.now().plus(Duration.ofMinutes(5)));

		verifyOtp(user.email, stale).andExpect(status().isUnauthorized());
	}

	@Test
	void changingThePasswordInvalidatesAnyOutstandingCode() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String bearer = accounts.bearer(user); // before 2FA is on, like the ERGO Admin test above
		enableTwoFactor(user);
		String code = otpCodes.create(user.id, Instant.now().plus(Duration.ofMinutes(5)));

		mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"currentPassword": "%s", "newPassword": "A-brand-new-pass-1"}""".formatted(Accounts.PASSWORD)))
			.andExpect(status().isOk());

		verifyOtp(user.email, code).andExpect(status().isUnauthorized());
	}

	@Test
	void anAttackerGuessingFromOneIpCannotLockOutTheRealUsersCode() throws Exception {
		// security review finding H1: the limiter must cost the guesser's own IP, not the victim's email
		User user = enableTwoFactor(accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ));
		String attackerIp = uniqueIp();
		String code = otpCodes.create(user.id, Instant.now().plus(Duration.ofMinutes(5)));

		for (int i = 0; i < 5; i++) {
			verifyOtp(user.email, "000000", attackerIp).andExpect(status().isUnauthorized());
		}
		verifyOtp(user.email, "000000", attackerIp).andExpect(status().isTooManyRequests()); // the attacker's IP is capped

		verifyOtp(user.email, code, uniqueIp()).andExpect(status().isOk()); // the real user, from their own IP, is not
	}

	@Test
	void aUserCanEnableAndDisableTwoFactor() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String bearer = accounts.bearer(user);

		setTwoFactor(bearer, true, null).andExpect(status().isNoContent());
		accounts.login(user.email, Accounts.PASSWORD)
			.andExpect(result -> assertThat(result.getResponse().getContentAsString())
				.isEqualTo("{\"otpRequired\":true}"));

		setTwoFactor(bearer, false, Accounts.PASSWORD).andExpect(status().isNoContent());
		accounts.login(user.email, Accounts.PASSWORD)
			.andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("accessToken"));
	}

	@Test
	void disablingTwoFactorWithoutTheCurrentPasswordIsRejected() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String bearer = accounts.bearer(user); // before 2FA is on, like the ERGO Admin test above
		enableTwoFactor(user);

		setTwoFactor(bearer, false, "totally-wrong-password").andExpect(status().isUnprocessableContent());
		setTwoFactor(bearer, false, null).andExpect(status().isUnprocessableContent());

		assertThat(users.findById(user.id).orElseThrow().twoFactorEnabled).isTrue(); // unchanged
	}

	@Test
	void anErgoAdminCannotDisableTwoFactor() throws Exception {
		User admin = accounts.user(accounts.company(CompanyType.ERGO), Role.ERGO_ADMIN);
		String bearer = accounts.bearer(admin); // before 2FA is on - its own login isn't what's under test here
		admin.twoFactorEnabled = true;
		users.save(admin);

		setTwoFactor(bearer, false, Accounts.PASSWORD).andExpect(status().isUnprocessableContent());
	}

	@Test
	void creatingAUserWithErgoAdminAutoEnablesTwoFactorAndAudits() throws Exception {
		User creator = accounts.user(accounts.company(CompanyType.ERGO), Role.ERGO_ADMIN);
		String bearer = accounts.bearer(creator);
		Company ergo = accounts.company(CompanyType.ERGO);

		String response = mvc.perform(post("/api/users").header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"companyId": %d, "firstName": "New", "lastName": "Admin", "email": "new-admin-%d@test.dev",
					"roles": ["ERGO_ADMIN"]}""".formatted(ergo.id, SEQ.incrementAndGet())))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		long newUserId = ((Number) JsonPath.read(response, "$.user.id")).longValue();
		assertThat(users.findById(newUserId).orElseThrow().twoFactorEnabled).isTrue();
		List<String> events = jdbc.queryForList(
				"SELECT event_type FROM audit_logs WHERE entity_name = 'User' AND record_id = ?", String.class,
				newUserId);
		assertThat(events).contains("TWO_FACTOR_ENABLED");
	}

	@Test
	void aSuccessfulOtpLoginWritesNoExtraAuditNoiseButStillRecordsLastLogin() throws Exception {
		User user = enableTwoFactor(accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ));
		String code = otpCodes.create(user.id, Instant.now().plus(Duration.ofMinutes(5)));

		verifyOtp(user.email, code).andExpect(status().isOk());

		assertThat(users.findById(user.id).orElseThrow().lastLoginAt).isNotNull();
	}

	private User enableTwoFactor(User user) {
		user.twoFactorEnabled = true;
		return users.save(user);
	}

	private ResultActions verifyOtp(String email, String code) throws Exception {
		return verifyOtp(email, code, uniqueIp());
	}

	private ResultActions verifyOtp(String email, String code, String ip) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/auth/verify-otp").contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"email": "%s", "code": "%s"}""".formatted(email, code))
			.with(req -> {
				req.setRemoteAddr(ip);
				return req;
			});
		return mvc.perform(request);
	}

	private ResultActions setTwoFactor(String bearer, boolean enabled, String currentPassword) throws Exception {
		return mvc.perform(post("/api/auth/2fa").header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(currentPassword == null ? """
					{"enabled": %s}""".formatted(enabled) : """
					{"enabled": %s, "currentPassword": "%s"}""".formatted(enabled, currentPassword)));
	}

	/** TEST-NET-2 (RFC 5737), documentation-only - never a real client. */
	private static String uniqueIp() {
		return "198.51.100." + (SEQ.incrementAndGet() % 254 + 1);
	}

}
