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
		Number imp = jwt.getClaim("imp");
		// A token's "imp" claim must match the session row exactly, so a shadow token can't pass as a normal one or the
		// reverse. A shadow session also dies with the admin's own: that session live, the admin active, still holding
		// the Super Admin role, with their company active.
		Boolean live = jdbc.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM user_sessions s
				    JOIN users u ON u.user_id = s.user_id
				    JOIN companies c ON c.company_id = u.company_id
				    WHERE s.session_id = ? AND s.user_id = ? AND s.revoked_at IS NULL AND s.expires_at > now()
				      AND u.active AND u.deleted_at IS NULL AND c.status = 'ACTIVE'
				      AND s.impersonated_by_user_id IS NOT DISTINCT FROM ?::bigint
				      AND (s.parent_session_id IS NULL OR EXISTS (
				          SELECT 1 FROM user_sessions p
				          JOIN users a ON a.user_id = p.user_id
				          JOIN companies ac ON ac.company_id = a.company_id
				          WHERE p.session_id = s.parent_session_id AND p.user_id = s.impersonated_by_user_id
				            AND p.revoked_at IS NULL AND p.expires_at > now() AND p.active_role_code = 'ERGO_ADMIN'
				            AND a.active AND a.deleted_at IS NULL AND ac.status = 'ACTIVE'
				            AND EXISTS (SELECT 1 FROM user_role_mappings m
				                        WHERE m.user_id = a.user_id AND m.role_code = 'ERGO_ADMIN'))))""", Boolean.class,
				sessionId.longValue(), Long.parseLong(jwt.getSubject()), imp == null ? null : imp.longValue());
		return Boolean.TRUE.equals(live) ? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(REVOKED);
	}

}
