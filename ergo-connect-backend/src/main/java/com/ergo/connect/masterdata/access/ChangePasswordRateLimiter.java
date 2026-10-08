package com.ergo.connect.masterdata.access;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.RedisFixedWindowLimiter;

/**
 * Change-password attempt limiter (ARCHITECTURE.md §8, Phase 8d-bis). Unlike {@link LoginRateLimiter}, this is
 * keyed by the authenticated user's id, not IP: the attacker here already holds a valid access token for one
 * specific account (e.g. stolen from a device) and is trying to brute-force that account's current password to
 * take it over completely, so the account itself - not whatever IP the token is used from - is what needs the
 * cap. Same fixed-window/fail-open trade-offs as {@code LoginRateLimiter} (ARCHITECTURE.md §8, Phase 8d) - this
 * fails open if Redis is down, but {@link AuthService#changePassword} now also counts a wrong current password
 * against the same DB-backed account lockout {@code login()} uses, so there's still a backstop either way.
 */
@Component
class ChangePasswordRateLimiter {

	private static final int WINDOW_SECONDS = 900; // 15 min, same window as the per-account login lockout

	private final RedisFixedWindowLimiter limiter;

	private final int maxAttempts;

	ChangePasswordRateLimiter(RedisFixedWindowLimiter limiter,
			@Value("${ergo.security.change-password-rate-limit.max-attempts:5}") int maxAttempts) {
		this.limiter = limiter;
		this.maxAttempts = maxAttempts;
	}

	/** Call once per change-password attempt, before touching the database or bcrypt. Throws past the limit. */
	void checkAndRecord(long userId) {
		if (limiter.increment(key(userId), WINDOW_SECONDS) > maxAttempts) {
			throw ApiException.tooManyRequests("Too many password change attempts. Please try again later.");
		}
	}

	/** Gives the attempt back once the password actually changes, so a few early typos don't cost a later retry. */
	void release(long userId) {
		limiter.release(key(userId));
	}

	private static String key(long userId) {
		return "change-password-attempts:" + userId;
	}

}
