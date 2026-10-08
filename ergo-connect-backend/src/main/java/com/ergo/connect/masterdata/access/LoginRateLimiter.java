package com.ergo.connect.masterdata.access;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.IpKeys;

/**
 * IP-scoped login attempt limiter (ARCHITECTURE.md §8, Phase 8d), on top of {@link AuthService}'s per-account
 * lockout: that stops one account being guessed, this stops one source spraying many accounts or flooding the
 * deliberately-slow bcrypt check. {@code getRemoteAddr()} is the value Tomcat's {@code RemoteIpValve} resolves
 * (Phase 8c) - trusted-proxy-aware in prod, so this can't be bypassed by faking X-Forwarded-For; the real ingress
 * header format (e.g. whether it includes a port) still needs confirming against Azure in 8k, see ARCHITECTURE.md.
 *
 * <p>Redis being unreachable fails open (logged, not thrown): losing this one extra layer is preferable to an
 * outage of Redis - a component this feature newly depends on - taking down login entirely. {@code
 * spring.data.redis.*-timeout} are set short (application.yml) so a Redis outage stalls a login attempt for at
 * most a couple hundred milliseconds, not Lettuce's 60s default, before falling through to this fail-open path.
 *
 * <p>Fixed window, not sliding: an attacker landing attempts either side of a window boundary can get up to
 * {@code 2 * maxAttempts - 1} through in a short span. Acceptable for a login endpoint already behind
 * per-account lockout; not worth a sliding-window implementation for this.
 */
@Component
class LoginRateLimiter {

	private static final int WINDOW_SECONDS = 300;

	/** INCR the count and, only if the key has no TTL yet, give it one - one round trip, race-free. */
	private static final RedisScript<Long> INCREMENT_WITH_TTL = RedisScript.of("""
			local count = redis.call('INCR', KEYS[1])
			if redis.call('TTL', KEYS[1]) < 0 then
			  redis.call('EXPIRE', KEYS[1], ARGV[1])
			end
			return count
			""", Long.class);

	private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

	private final StringRedisTemplate redis;

	private final int maxAttempts;

	LoginRateLimiter(StringRedisTemplate redis,
			@Value("${ergo.security.login-rate-limit.max-attempts:20}") int maxAttempts) {
		this.redis = redis;
		this.maxAttempts = maxAttempts;
	}

	/** Call once per login attempt, before touching the database or bcrypt. Throws past the limit. */
	void checkAndRecord(String ip) {
		try {
			Long count = redis.execute(INCREMENT_WITH_TTL, List.of(key(ip)), String.valueOf(WINDOW_SECONDS));
			if (count != null && count > maxAttempts) {
				throw ApiException.tooManyRequests("Too many login attempts. Please try again later.");
			}
		} catch (DataAccessException e) {
			log.warn("Redis unavailable for login rate limiting ({}); allowing the request through", e.toString());
		}
	}

	/** Gives the attempt back on a successful login, so one shared office IP isn't throttled by its own traffic. */
	void release(String ip) {
		try {
			redis.opsForValue().decrement(key(ip));
		} catch (DataAccessException e) {
			log.warn("Redis unavailable to release a login attempt ({})", e.toString());
		}
	}

	private static String key(String ip) {
		return "login-attempts:" + IpKeys.normalize(ip);
	}

}
