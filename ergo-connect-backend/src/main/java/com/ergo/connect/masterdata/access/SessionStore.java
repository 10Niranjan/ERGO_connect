package com.ergo.connect.masterdata.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ergo.connect.common.security.Role;

/**
 * Server-side login sessions (user_sessions). The browser keeps the raw token in an HttpOnly cookie and uses it only
 * to get fresh access tokens; we store its SHA-256, so a database leak does not leak live sessions.
 */
@Component
class SessionStore {

	record Session(long id, long userId, Role role, Instant expiresAt) {
	}

	record NewSession(long id, String token) {
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcTemplate jdbc;

	SessionStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	NewSession create(long userId, Role role, Instant expiresAt, String ip) {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Long id = jdbc.queryForObject("""
				INSERT INTO user_sessions (user_id, token_hash, active_role_code, expires_at, ip_address)
				VALUES (?, ?, ?, ?, ?) RETURNING session_id""", Long.class, userId, hash(token), role.name(),
				OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC), ip);
		return new NewSession(id, token);
	}

	Optional<Session> findActive(String token) {
		return jdbc.query("""
				SELECT session_id, user_id, active_role_code, expires_at FROM user_sessions
				WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > now()""",
				(rs, n) -> new Session(rs.getLong(1), rs.getLong(2), Role.valueOf(rs.getString(3)),
						rs.getObject(4, OffsetDateTime.class).toInstant()),
				hash(token))
			.stream()
			.findFirst();
	}

	/** @return false if the session is no longer active */
	boolean setRole(long sessionId, long userId, Role role) {
		return jdbc.update("""
				UPDATE user_sessions SET active_role_code = ?
				WHERE session_id = ? AND user_id = ? AND revoked_at IS NULL AND expires_at > now()""", role.name(),
				sessionId, userId) == 1;
	}

	void revoke(String token) {
		jdbc.update("UPDATE user_sessions SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL",
				hash(token));
	}

	/** Ends all of a user's sessions, except {@code keepSessionId} if given (the caller's own). */
	void revokeAll(long userId, Long keepSessionId) {
		jdbc.update("""
				UPDATE user_sessions SET revoked_at = now()
				WHERE user_id = ? AND revoked_at IS NULL AND session_id <> ?""", userId,
				keepSessionId == null ? -1L : keepSessionId);
	}

	private static String hash(String token) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e); // SHA-256 is mandatory in every JDK
		}
	}

}
