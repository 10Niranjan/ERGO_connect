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
import java.util.List;
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

	/** {@code impersonatedBy} is the Super Admin's user id for a "Login as" shadow session, else null. */
	record Session(long id, long userId, Role role, Instant expiresAt, Long impersonatedBy, Long parentSessionId) {
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

	/**
	 * A "Login as" shadow session of {@code userId}, started by the Super Admin {@code adminId} from their own session
	 * {@code parentSessionId}. Any shadow session that admin still has open is ended first: one at a time.
	 */
	NewSession createShadow(long userId, Role role, Instant expiresAt, String ip, long adminId, long parentSessionId,
			String reason) {
		revokeShadowsOf(adminId); // AuthService has already ended and audited any open view; this keeps the unique index safe
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Long id = jdbc.queryForObject("""
				INSERT INTO user_sessions (user_id, token_hash, active_role_code, expires_at, ip_address,
				                           impersonated_by_user_id, parent_session_id, impersonation_reason)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING session_id""", Long.class, userId, hash(token), role.name(),
				OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC), ip, adminId, parentSessionId, reason);
		return new NewSession(id, token);
	}

	/** Ends every open shadow session the Super Admin has started; returns the users who were being viewed. */
	List<Long> revokeShadowsOf(long adminId) {
		return jdbc.queryForList("""
				UPDATE user_sessions SET revoked_at = now()
				WHERE impersonated_by_user_id = ? AND revoked_at IS NULL RETURNING user_id""", Long.class, adminId);
	}

	/** A session that has not been revoked or expired, by id (for the caller's own session). */
	Optional<Session> findLiveById(long sessionId) {
		return jdbc.query("""
				SELECT session_id, user_id, active_role_code, expires_at, impersonated_by_user_id,
				       parent_session_id FROM user_sessions
				WHERE session_id = ? AND revoked_at IS NULL AND expires_at > now()""",
				(rs, n) -> new Session(rs.getLong(1), rs.getLong(2), Role.valueOf(rs.getString(3)),
						rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getObject(5, Long.class),
						rs.getObject(6, Long.class)),
				sessionId)
			.stream()
			.findFirst();
	}

	Optional<Instant> expiryOf(long sessionId) {
		return jdbc.query("SELECT expires_at FROM user_sessions WHERE session_id = ?",
				(rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant(), sessionId).stream().findFirst();
	}

	/** Ends one session by id (the caller must already own it). */
	void revokeById(long sessionId) {
		jdbc.update("UPDATE user_sessions SET revoked_at = now() WHERE session_id = ? AND revoked_at IS NULL", sessionId);
	}

	Optional<Session> findActive(String token) {
		return jdbc.query("""
				SELECT session_id, user_id, active_role_code, expires_at, impersonated_by_user_id,
				       parent_session_id FROM user_sessions
				WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > now()""",
				(rs, n) -> new Session(rs.getLong(1), rs.getLong(2), Role.valueOf(rs.getString(3)),
						rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getObject(5, Long.class),
						rs.getObject(6, Long.class)),
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
