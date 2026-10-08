package com.ergo.connect.common.security;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window Redis counter shared by every per-key rate limiter that's added after the login one
 * (ARCHITECTURE.md §8, Phase 8d-bis: change-password, uploads). One atomic Lua script (INCR + conditional EXPIRE,
 * one round trip) so a key is never left without a TTL - the exact bug {@code LoginRateLimiter}'s own two-call
 * version had before Phase 8d's security review fixed it; new callers get that fix for free instead of
 * re-introducing it. Fails open (logs, doesn't throw) if Redis is unreachable: callers treat a failed increment
 * as "under the limit", preferring availability over this one extra layer, same choice {@code LoginRateLimiter}
 * made.
 */
@Component
public class RedisFixedWindowLimiter {

	private static final RedisScript<Long> INCREMENT_WITH_TTL = RedisScript.of("""
			local count = redis.call('INCR', KEYS[1])
			if redis.call('TTL', KEYS[1]) < 0 then
			  redis.call('EXPIRE', KEYS[1], ARGV[1])
			end
			return count
			""", Long.class);

	private static final Logger log = LoggerFactory.getLogger(RedisFixedWindowLimiter.class);

	private final StringRedisTemplate redis;

	RedisFixedWindowLimiter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** @return the count after this increment, or 0 (never over any limit) if Redis couldn't be reached. */
	public long increment(String key, int windowSeconds) {
		try {
			Long count = redis.execute(INCREMENT_WITH_TTL, List.of(key), String.valueOf(windowSeconds));
			return count == null ? 0 : count;
		}
		catch (DataAccessException e) {
			log.warn("Redis unavailable for rate limiting on {} ({}); allowing the request through", key, e.toString());
			return 0;
		}
	}

	/** Gives a slot back (e.g. after a successful attempt), so a legitimate retry isn't held against the next one. */
	public void release(String key) {
		try {
			redis.opsForValue().decrement(key);
		}
		catch (DataAccessException e) {
			log.warn("Redis unavailable to release a rate-limit slot for {} ({})", key, e.toString());
		}
	}

}
