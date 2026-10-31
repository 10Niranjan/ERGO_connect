package com.ergo.connect.masterdata.access;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.RedisFixedWindowLimiter;

/** Caps "Login as" starts per Super Admin (a stolen admin session must not sweep every user). Fails open without Redis. */
@Component
class LoginAsRateLimiter {

	private static final int WINDOW_SECONDS = 900;

	private final RedisFixedWindowLimiter limiter;

	private final int maxStarts;

	LoginAsRateLimiter(RedisFixedWindowLimiter limiter,
			@Value("${ergo.security.login-as-rate-limit.max-starts:20}") int maxStarts) {
		this.limiter = limiter;
		this.maxStarts = maxStarts;
	}

	void checkAndRecord(long adminId) {
		if (limiter.increment("login-as-starts:" + adminId, WINDOW_SECONDS) > maxStarts) {
			throw ApiException.tooManyRequests("Too many Login as attempts. Please try again later.");
		}
	}

}
