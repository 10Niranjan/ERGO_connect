package com.ergo.connect.masterdata.access;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.IpKeys;
import com.ergo.connect.common.security.RedisFixedWindowLimiter;

/**
 * Forgot-password request limiter (ARCHITECTURE.md §8, Phase 8f) - two independent buckets, like login's
 * IP-limiter-plus-account-lockout combo. {@link #checkAndRecord} is keyed by the submitted email address itself,
 * not the caller's IP: it stops one attacker mail-bombing one target's inbox (they don't even need to know if
 * the address is registered - the response is identical either way). {@link #checkAndRecordByIp} closes the gap
 * that alone leaves - one source could otherwise sweep a whole address list, each individually under the
 * per-email limit - the same reasoning login's own IP limiter exists for.
 */
@Component
class ForgotPasswordRateLimiter {

	private static final int WINDOW_SECONDS = 900; // 15 min

	private final RedisFixedWindowLimiter limiter;

	private final int maxAttempts;

	private final int maxIpAttempts;

	ForgotPasswordRateLimiter(RedisFixedWindowLimiter limiter,
			@Value("${ergo.security.forgot-password-rate-limit.max-attempts:3}") int maxAttempts,
			@Value("${ergo.security.forgot-password-rate-limit.max-ip-attempts:10}") int maxIpAttempts) {
		this.limiter = limiter;
		this.maxAttempts = maxAttempts;
		this.maxIpAttempts = maxIpAttempts;
	}

	void checkAndRecord(String email) {
		if (limiter.increment(key(email), WINDOW_SECONDS) > maxAttempts) {
			throw ApiException.tooManyRequests("Too many password reset requests. Please try again later.");
		}
	}

	void checkAndRecordByIp(String ip) {
		if (limiter.increment(ipKey(ip), WINDOW_SECONDS) > maxIpAttempts) {
			throw ApiException.tooManyRequests("Too many password reset requests. Please try again later.");
		}
	}

	private static String key(String email) {
		return "forgot-password-attempts:" + email.trim().toLowerCase(Locale.ROOT);
	}

	private static String ipKey(String ip) {
		return "forgot-password-ip-attempts:" + IpKeys.normalize(ip);
	}

}
