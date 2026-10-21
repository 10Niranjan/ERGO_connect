package com.ergo.connect.common.security.jwt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Access tokens are short-lived but stateless, so on their own they would outlive a revoked session. This checks on
 * every request that the token's login session is still live and its user may still sign in - session not revoked or
 * expired, user active and not deleted, company active - the same conditions login and refresh apply. So
 * deactivating or deleting a user, deactivating a company, resetting a password or signing out ends API access at
 * once instead of when the token expires. One indexed lookup per request.
 */
@Component
class SessionStillValidValidator implements OAuth2TokenValidator<Jwt> {

	private static final OAuth2Error REVOKED = new OAuth2Error("invalid_token", "The session has ended", null);

	private final JdbcTemplate jdbc;

	SessionStillValidValidator(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		Number sessionId = jwt.getClaim("sid");
		if (sessionId == null) {
			return OAuth2TokenValidatorResult.failure(REVOKED);
		}
		Boolean live = jdbc.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM user_sessions s
				    JOIN users u ON u.user_id = s.user_id
				    JOIN companies c ON c.company_id = u.company_id
				    WHERE s.session_id = ? AND s.user_id = ? AND s.revoked_at IS NULL AND s.expires_at > now()
				      AND u.active AND u.deleted_at IS NULL AND c.status = 'ACTIVE')""", Boolean.class,
				sessionId.longValue(), Long.parseLong(jwt.getSubject()));
		return Boolean.TRUE.equals(live) ? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(REVOKED);
	}

}
