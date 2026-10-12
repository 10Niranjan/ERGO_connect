package com.ergo.connect.masterdata.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;

/**
 * Phase 8d (ARCHITECTURE.md §8): proves the limiter is actually wired into {@code /api/auth/login}, against a
 * real Redis (Testcontainers) - {@link LoginRateLimiterTest} covers the branches this can't force (Redis down).
 *
 * <p>Overrides the limit down to 3 (application-test.yml raises the shared-context default to avoid tripping
 * on the rest of the suite's own login traffic) - this gets its own Spring context as a result, but proving the
 * real block without 20+ HTTP round trips is worth the extra startup.
 */
@IntegrationTest
@TestPropertySource(properties = "ergo.security.login-rate-limit.max-attempts=3")
class LoginRateLimiterEndpointTest {

	private static final int LIMIT = 3;

	private static final AtomicInteger SEQ = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	StringRedisTemplate redis;

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
	void blocksAfterTooManyAttemptsFromTheSameIp() throws Exception {
		String ip = uniqueIp();
		for (int i = 0; i < LIMIT; i++) {
			loginAttempt(ip, "nobody@test.dev", "wrong").andExpect(status().isUnauthorized());
		}
		loginAttempt(ip, "nobody@test.dev", "wrong").andExpect(status().isTooManyRequests());
	}

	@Test
	void countersAreIndependentPerIp() throws Exception {
		String blocked = uniqueIp();
		for (int i = 0; i < LIMIT; i++) {
			loginAttempt(blocked, "nobody@test.dev", "wrong").andExpect(status().isUnauthorized());
		}
		loginAttempt(blocked, "nobody@test.dev", "wrong").andExpect(status().isTooManyRequests());

		// a different IP is unaffected
		loginAttempt(uniqueIp(), "nobody@test.dev", "wrong").andExpect(status().isUnauthorized());
	}

	@Test
	void anIpWithATrailingPortSharesTheSameBucketAsWithoutOne() throws Exception {
		String ip = uniqueIp();
		for (int i = 0; i < LIMIT; i++) {
			loginAttempt(ip + ":54321", "nobody@test.dev", "wrong").andExpect(status().isUnauthorized());
		}
		// no port this time, but it's the same bucket - a proxy appending a port to X-Forwarded-For can't
		// hand an attacker a fresh bucket per TCP connection
		loginAttempt(ip, "nobody@test.dev", "wrong").andExpect(status().isTooManyRequests());
	}

	@Test
	void theCounterKeepsItsExpiryAfterARepeatedIncrement() throws Exception {
		String ip = uniqueIp();
		loginAttempt(ip, "nobody@test.dev", "wrong").andExpect(status().isUnauthorized());
		loginAttempt(ip, "nobody@test.dev", "wrong").andExpect(status().isUnauthorized());

		Long ttl = redis.getExpire("login-attempts:" + ip);
		assertThat(ttl).isGreaterThan(0); // never left without a TTL - would otherwise lock that IP out forever
	}

	@Test
	void correctCredentialsPastTheLimitAreStillBlockedAndNoSessionIsCreated() throws Exception {
		Company company = accounts.company(CompanyType.CUSTOMER);
		User user = accounts.user(company, Role.CUSTOMER_RFQ);
		String ip = uniqueIp();
		for (int i = 0; i < LIMIT; i++) {
			loginAttempt(ip, "nobody@test.dev", "wrong").andExpect(status().isUnauthorized());
		}

		loginAttempt(ip, user.email, Accounts.PASSWORD)
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.accessToken").doesNotExist());
	}

	@Test
	void aSuccessfulLoginDoesNotCountAgainstTheLimit() throws Exception {
		Company company = accounts.company(CompanyType.CUSTOMER);
		User user = accounts.user(company, Role.CUSTOMER_RFQ);
		String ip = uniqueIp();

		for (int i = 0; i < LIMIT + 2; i++) { // more successful logins than the limit would allow if counted
			loginAttempt(ip, user.email, Accounts.PASSWORD).andExpect(status().isOk());
		}
	}

	private ResultActions loginAttempt(String ip, String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"email": "%s", "password": "%s"}""".formatted(email, password))
			.with(request -> {
				request.setRemoteAddr(ip);
				return request;
			}));
	}

	/** TEST-NET-2 (RFC 5737), documentation-only - never a real client. */
	private static String uniqueIp() {
		return "198.51.100." + (SEQ.incrementAndGet() % 254 + 1);
	}

}
