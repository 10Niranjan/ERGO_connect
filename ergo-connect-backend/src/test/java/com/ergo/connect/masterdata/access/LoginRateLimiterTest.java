package com.ergo.connect.masterdata.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponse;

import com.ergo.connect.common.exception.ApiException;

/** Phase 8d (ARCHITECTURE.md §8): the branches a real-Redis test can't force. No Spring context needed. */
class LoginRateLimiterTest {

	@Test
	void failsOpenWhenRedisIsUnreachable() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), anyList(), any()))
			.thenThrow(new RedisConnectionFailureException("Redis unreachable"));

		assertThatCode(() -> new LoginRateLimiter(redis, 20).checkAndRecord("203.0.113.1")).doesNotThrowAnyException();
	}

	@Test
	void releaseAlsoFailsOpenWhenRedisIsUnreachable() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> ops = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.decrement(any())).thenThrow(new RedisConnectionFailureException("Redis unreachable"));

		assertThatCode(() -> new LoginRateLimiter(redis, 20).release("203.0.113.1")).doesNotThrowAnyException();
	}

	@Test
	void blocksOnceTheCounterPassesTheLimit() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(21L); // past the limit of 20

		assertThatThrownBy(() -> new LoginRateLimiter(redis, 20).checkAndRecord("203.0.113.1"))
			.isInstanceOf(ApiException.class)
			.extracting(t -> ((ErrorResponse) t).getStatusCode())
			.isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

}
