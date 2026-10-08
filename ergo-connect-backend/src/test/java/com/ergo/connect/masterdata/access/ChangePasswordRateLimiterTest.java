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

/** Phase 8d-bis (ARCHITECTURE.md §8): no Spring/Redis needed, the shared limiter is mocked. */
class ChangePasswordRateLimiterTest {

	private final RedisFixedWindowLimiter limiter = mock(RedisFixedWindowLimiter.class);

	@Test
	void allowsAttemptsUpToTheLimit() {
		when(limiter.increment(eq("change-password-attempts:42"), eq(900))).thenReturn(5L);

		assertThatCode(() -> new ChangePasswordRateLimiter(limiter, 5).checkAndRecord(42)).doesNotThrowAnyException();
	}

	@Test
	void blocksOnceTheCounterPassesTheLimit() {
		when(limiter.increment(eq("change-password-attempts:42"), eq(900))).thenReturn(6L);

		assertThatThrownBy(() -> new ChangePasswordRateLimiter(limiter, 5).checkAndRecord(42))
			.isInstanceOf(ApiException.class)
			.extracting(t -> ((ErrorResponse) t).getStatusCode())
			.isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	void releaseDelegatesToTheSharedLimiterWithTheSameKey() {
		new ChangePasswordRateLimiter(limiter, 5).release(42);

		verify(limiter).release("change-password-attempts:42");
	}

}
