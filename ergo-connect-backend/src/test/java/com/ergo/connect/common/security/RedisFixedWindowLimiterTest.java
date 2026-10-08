package com.ergo.connect.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/** Phase 8d-bis (ARCHITECTURE.md §8): the branches a real-Redis test can't force. No Spring context needed. */
class RedisFixedWindowLimiterTest {

	@Test
	void incrementFailsOpenWhenRedisIsUnreachable() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), anyList(), any()))
			.thenThrow(new RedisConnectionFailureException("Redis unreachable"));

		assertThat(new RedisFixedWindowLimiter(redis).increment("some-key", 300)).isZero();
	}

	@Test
	void releaseAlsoFailsOpenWhenRedisIsUnreachable() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> ops = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.decrement(any())).thenThrow(new RedisConnectionFailureException("Redis unreachable"));

		new RedisFixedWindowLimiter(redis).release("some-key"); // must not throw
	}

	@Test
	void incrementReturnsTheRedisCount() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(7L);

		assertThat(new RedisFixedWindowLimiter(redis).increment("some-key", 300)).isEqualTo(7L);
	}

}
