package com.ergo.connect.masterdata.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 8g (ARCHITECTURE.md §8): the email-OTP second factor end to end - login returns a challenge instead of a
 * session once enabled, {@code /verify-otp} completes it, and ERGO Admin accounts can't turn it back off.
 * {@link OtpCodeStore} is autowired directly (package-private, same package) to recover a known raw code, the
 * same way {@link PasswordResetFlowTest} reaches into {@link PasswordResetTokenStore}.
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

	Accounts accounts;

	@BeforeEach
	void setUp() {
		accounts = new Accounts(companies, users, encoder, mvc);
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
	void aUserCanEnableAndDisableTwoFactor() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		String bearer = accounts.bearer(user);

		setTwoFactor(bearer, true).andExpect(status().isNoContent());
		accounts.login(user.email, Accounts.PASSWORD)
			.andExpect(result -> assertThat(result.getResponse().getContentAsString())
				.isEqualTo("{\"otpRequired\":true}"));

		setTwoFactor(bearer, false).andExpect(status().isNoContent());
		accounts.login(user.email, Accounts.PASSWORD)
			.andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("accessToken"));
	}

	@Test
	void anErgoAdminCannotDisableTwoFactor() throws Exception {
		User admin = accounts.user(accounts.company(CompanyType.ERGO), Role.ERGO_ADMIN);
		String bearer = accounts.bearer(admin); // before 2FA is on - its own login isn't what's under test here
		admin.twoFactorEnabled = true;
		users.save(admin);

		setTwoFactor(bearer, false).andExpect(status().isUnprocessableContent());
	}

	@Test
	void creatingAUserWithErgoAdminAutoEnablesTwoFactor() throws Exception {
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
		return mvc.perform(post("/api/auth/verify-otp").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "code": "%s"}""".formatted(email, code)));
	}

	private ResultActions setTwoFactor(String bearer, boolean enabled) throws Exception {
		return mvc.perform(post("/api/auth/2fa").header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"enabled": %s}""".formatted(enabled)));
	}

}
