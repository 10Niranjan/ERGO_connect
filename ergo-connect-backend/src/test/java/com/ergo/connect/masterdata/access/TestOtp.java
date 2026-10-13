package com.ergo.connect.masterdata.access;

import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The one public, test-only door into {@link OtpCodeStore} (package-private by design, security review Phase 8h):
 * lets {@code com.ergo.connect.TestUsers} - the shared login helper every module's tests use, outside this package
 * - complete ERGO Admin's mandatory 2FA challenge without going through email, which isn't configured in tests.
 */
public final class TestOtp {

	private TestOtp() {
	}

	/** Mints a fresh, known code for this user, ready to post to {@code /api/auth/verify-otp}. */
	public static String mint(JdbcTemplate jdbc, long userId) {
		return new OtpCodeStore(jdbc).create(userId, Instant.now().plus(Duration.ofMinutes(5)));
	}

}
