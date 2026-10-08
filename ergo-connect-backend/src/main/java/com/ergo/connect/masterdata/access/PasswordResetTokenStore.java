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

/**
 * Forgot-password tokens (password_reset_tokens, Phase 8f). The email holds the raw token; only its SHA-256 is
 * stored, the same precedent {@link SessionStore} set for login tokens - a database leak does not leak usable
 * reset links. Single-use: {@link #consume} marks it used in the same statement that checks it, so two concurrent
 * uses of the same token can't both succeed.
 */
@Component
class PasswordResetTokenStore {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcTemplate jdbc;

	PasswordResetTokenStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** @return the raw token to put in the email link - only its hash is stored */
	String create(long userId, Instant expiresAt) {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		jdbc.update("INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)", userId,
				hash(token), OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
		return token;
	}

	/** @return the token's user id, only if it was valid and unused - and marks it used either way. */
	Optional<Long> consume(String token) {
		return jdbc.query("""
				UPDATE password_reset_tokens SET consumed_at = now()
				WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > now()
				RETURNING user_id""", (rs, n) -> rs.getLong(1), hash(token)).stream().findFirst();
	}

	/**
	 * Kills every other outstanding link for this user (Phase 8f security review) - called whenever the password
	 * actually changes by any route, the account is deactivated, or its email changes, so an already-emailed
	 * link found later (forwarded, sitting in a shared mailbox, a stale address after an email change) can't
	 * still work.
	 */
	void invalidateAllFor(long userId) {
		jdbc.update("UPDATE password_reset_tokens SET consumed_at = now() WHERE user_id = ? AND consumed_at IS NULL",
				userId);
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
