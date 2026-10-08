package com.ergo.connect.masterdata.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ergo.connect.IntegrationTest;

/**
 * Phase 8f security review finding "M1" (ARCHITECTURE.md §8): keying {@link ForgotPasswordRateLimiter} by email
 * alone leaves one attacker free to sweep many different target addresses from a single source. This proves
 * the IP bucket closes that gap - the per-email limit is raised far above what one test could ever hit, so
 * only the per-IP bucket can be what blocks.
 */
@IntegrationTest
@TestPropertySource(properties = { "ergo.security.forgot-password-rate-limit.max-ip-attempts=3",
		"ergo.security.forgot-password-rate-limit.max-attempts=1000" })
class ForgotPasswordIpRateLimiterEndpointTest {

	private static final int LIMIT = 3;

	private static final AtomicInteger SEQ = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Test
	void blocksOnceOneIpSweepsTooManyDifferentEmails() throws Exception {
		String ip = uniqueIp();
		for (int i = 0; i < LIMIT; i++) {
			forgotPassword(ip, "target" + i + "@test.dev").andExpect(status().isNoContent());
		}
		forgotPassword(ip, "one-more@test.dev").andExpect(status().isTooManyRequests());
	}

	@Test
	void differentIpsHaveIndependentCounters() throws Exception {
		String blocked = uniqueIp();
		for (int i = 0; i < LIMIT; i++) {
			forgotPassword(blocked, "target" + i + "@test.dev").andExpect(status().isNoContent());
		}
		forgotPassword(blocked, "one-more@test.dev").andExpect(status().isTooManyRequests());

		forgotPassword(uniqueIp(), "unaffected@test.dev").andExpect(status().isNoContent());
	}

	private ResultActions forgotPassword(String ip, String email) throws Exception {
		return mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"email": "%s"}""".formatted(email))
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
