package com.ergo.connect.masterdata.access;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.IpKeys;
import com.ergo.connect.common.security.RedisFixedWindowLimiter;

/**
 * OTP verification attempt limiter (ARCHITECTURE.md §8, Phase 8g) - a 6-digit code is only 10^6 possibilities, so
 * guessing it needs its own cap. Keyed by IP, like {@link LoginRateLimiter}, not by the submitted email: an
 * attacker who merely knows (or guesses) a victim's address but not their password must not be able to spend the
 * victim's own attempt budget on junk codes and lock them out of their own correct one (security review finding
 * H1) - the same reasoning login's own IP-keyed limiter exists for, on top of its separate per-account lockout.
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

	void checkAndRecord(String ip) {
		if (limiter.increment(key(ip), WINDOW_SECONDS) > maxAttempts) {
			throw ApiException.tooManyRequests("Too many attempts from this network. Please wait a few minutes and try again.");
		}
	}

	/** Gives the attempt back on a successful verify, so a few early typos don't cost a later legitimate retry. */
	void release(String ip) {
		limiter.release(key(ip));
	}

	private static String key(String ip) {
		return "otp-attempts:" + IpKeys.normalize(ip);
	}

}
