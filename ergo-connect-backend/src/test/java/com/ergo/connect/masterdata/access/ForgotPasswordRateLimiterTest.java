package com.ergo.connect.masterdata.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponse;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.RedisFixedWindowLimiter;

/** Phase 8f (ARCHITECTURE.md §8): no Spring/Redis needed, the shared limiter is mocked. */
class ForgotPasswordRateLimiterTest {

	private final RedisFixedWindowLimiter limiter = mock(RedisFixedWindowLimiter.class);

	@Test
	void allowsAttemptsUpToTheLimit() {
		when(limiter.increment(eq("forgot-password-attempts:nobody@test.dev"), eq(900))).thenReturn(3L);

		assertThatCode(() -> new ForgotPasswordRateLimiter(limiter, 3, 10).checkAndRecord("nobody@test.dev"))
			.doesNotThrowAnyException();
	}

	@Test
	void blocksOnceTheCounterPassesTheLimit() {
		when(limiter.increment(eq("forgot-password-attempts:nobody@test.dev"), eq(900))).thenReturn(4L);

		assertThatThrownBy(() -> new ForgotPasswordRateLimiter(limiter, 3, 10).checkAndRecord("nobody@test.dev"))
			.isInstanceOf(ApiException.class)
			.extracting(t -> ((ErrorResponse) t).getStatusCode())
			.isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	void keyIsCaseAndWhitespaceNormalized() {
		new ForgotPasswordRateLimiter(limiter, 3, 10).checkAndRecord("  Nobody@Test.DEV  ");

		verify(limiter).increment(eq("forgot-password-attempts:nobody@test.dev"), eq(900));
	}

	@Test
	void ipBucketAllowsAttemptsUpToTheLimit() {
		when(limiter.increment(eq("forgot-password-ip-attempts:203.0.113.1"), eq(900))).thenReturn(10L);

		assertThatCode(() -> new ForgotPasswordRateLimiter(limiter, 3, 10).checkAndRecordByIp("203.0.113.1"))
			.doesNotThrowAnyException();
	}

	@Test
	void ipBucketBlocksOnceTheCounterPassesTheLimit() {
		when(limiter.increment(eq("forgot-password-ip-attempts:203.0.113.1"), eq(900))).thenReturn(11L);

		assertThatThrownBy(() -> new ForgotPasswordRateLimiter(limiter, 3, 10).checkAndRecordByIp("203.0.113.1"))
			.isInstanceOf(ApiException.class)
			.extracting(t -> ((ErrorResponse) t).getStatusCode())
			.isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	void ipBucketStripsAnAppendedPort() {
		new ForgotPasswordRateLimiter(limiter, 3, 10).checkAndRecordByIp("203.0.113.1:54321");

		verify(limiter).increment(eq("forgot-password-ip-attempts:203.0.113.1"), eq(900));
	}

}
