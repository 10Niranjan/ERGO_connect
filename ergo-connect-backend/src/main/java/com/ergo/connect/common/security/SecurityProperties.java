package com.ergo.connect.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code ergo.security.*}. The JWT secret comes from the environment in prod and is never committed. */
@ConfigurationProperties("ergo.security")
public record SecurityProperties(String jwtSecret, Duration accessTokenTtl, Duration sessionTtl,
		Duration passwordResetTokenTtl) {

	public SecurityProperties {
		if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("ergo.security.jwt-secret must be set and at least 32 bytes long");
		}
	}

	public SecretKey jwtKey() {
		return new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

}
