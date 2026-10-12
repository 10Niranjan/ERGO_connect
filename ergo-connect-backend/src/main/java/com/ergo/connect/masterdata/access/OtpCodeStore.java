package com.ergo.connect.masterdata.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Email-delivered login codes (otp_codes, Phase 8g). Same hash-at-rest precedent as
 * {@link PasswordResetTokenStore}/{@link SessionStore} - a database leak doesn't leak a usable code. Creating a
 * new code invalidates any still-outstanding one for that user, so only the most recently emailed code works.
 */
@Component
class OtpCodeStore {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcTemplate jdbc;

	OtpCodeStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** @return the 6-digit code to email - only its hash is stored */
	String create(long userId, Instant expiresAt) {
		invalidateAllFor(userId);
		String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
		jdbc.update("INSERT INTO otp_codes (user_id, code_hash, expires_at) VALUES (?, ?, ?)", userId, hash(code),
				OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
		return code;
	}

	/** @return true if the code was valid and unused for this user - and marks it used either way. */
	boolean verify(long userId, String code) {
		return jdbc.update("""
				UPDATE otp_codes SET consumed_at = now()
				WHERE user_id = ? AND code_hash = ? AND consumed_at IS NULL AND expires_at > now()""", userId,
				hash(code)) == 1;
	}

	/** Called on a fresh code and whenever 2FA is turned off, so an emailed code never outlives its usefulness. */
	void invalidateAllFor(long userId) {
		jdbc.update("UPDATE otp_codes SET consumed_at = now() WHERE user_id = ? AND consumed_at IS NULL", userId);
	}

	private static String hash(String code) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e); // SHA-256 is mandatory in every JDK
		}
	}

}
