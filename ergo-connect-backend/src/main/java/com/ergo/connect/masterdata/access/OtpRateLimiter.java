package com.ergo.connect.masterdata.access;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.RedisFixedWindowLimiter;

/**
 * OTP verification attempt limiter (ARCHITECTURE.md §8, Phase 8g) - a 6-digit code is only 10^6 possibilities, so
 * guessing it needs its own cap independent of {@link LoginRateLimiter}'s IP bucket. Keyed by email like
 * {@link ForgotPasswordRateLimiter}: whoever reaches this endpoint already knows the email (it's the one they
 * just logged in with), so email is bucket enough.
 */
@Component
class OtpRateLimiter {

	private static final int WINDOW_SECONDS = 600; // 10 min, longer than the code's own TTL

	private final RedisFixedWindowLimiter limiter;

	private final int maxAttempts;

	OtpRateLimiter(RedisFixedWindowLimiter limiter,
			@Value("${ergo.security.otp-rate-limit.max-attempts:5}") int maxAttempts) {
		this.limiter = limiter;
		this.maxAttempts = maxAttempts;
	}

	void checkAndRecord(String email) {
		if (limiter.increment(key(email), WINDOW_SECONDS) > maxAttempts) {
			throw ApiException.tooManyRequests("Too many attempts. Please sign in again to get a new code.");
		}
	}

	private static String key(String email) {
		return "otp-attempts:" + email.trim().toLowerCase(Locale.ROOT);
	}

}
