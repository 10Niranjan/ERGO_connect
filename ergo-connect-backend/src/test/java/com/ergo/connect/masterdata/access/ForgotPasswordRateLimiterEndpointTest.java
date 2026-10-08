package com.ergo.connect.masterdata.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ergo.connect.IntegrationTest;

/**
 * Phase 8f (ARCHITECTURE.md §8): proves {@link ForgotPasswordRateLimiter} is actually wired into
 * {@code /api/auth/forgot-password}, against a real Redis (Testcontainers) - keyed by the submitted email, so
 * every test's own distinct address is already its own isolated bucket and the shared-context default doesn't
 * need relaxing.
 */
@IntegrationTest
@TestPropertySource(properties = { "ergo.security.forgot-password-rate-limit.max-attempts=3",
		// isolates this class from the IP dimension - ForgotPasswordIpRateLimiterEndpointTest covers that one
		"ergo.security.forgot-password-rate-limit.max-ip-attempts=1000" })
class ForgotPasswordRateLimiterEndpointTest {

	private static final int LIMIT = 3;

	@Autowired
	MockMvc mvc;

	@Test
	void blocksAfterTooManyRequestsForTheSameEmail() throws Exception {
		for (int i = 0; i < LIMIT; i++) {
			forgotPassword("target@test.dev").andExpect(status().isNoContent());
		}
		forgotPassword("target@test.dev").andExpect(status().isTooManyRequests());
	}

	@Test
	void differentEmailsHaveIndependentCounters() throws Exception {
		for (int i = 0; i < LIMIT; i++) {
			forgotPassword("blocked@test.dev").andExpect(status().isNoContent());
		}
		forgotPassword("blocked@test.dev").andExpect(status().isTooManyRequests());

		forgotPassword("unaffected@test.dev").andExpect(status().isNoContent());
	}

	private ResultActions forgotPassword(String email) throws Exception {
		return mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"email": "%s"}""".formatted(email)));
	}

}
