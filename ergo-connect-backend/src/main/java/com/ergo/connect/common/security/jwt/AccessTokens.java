package com.ergo.connect.common.security.jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.common.security.SecurityProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/** Issues and reads the short-lived access tokens (HS256). The only place that knows the claim names. */
@Component
public class AccessTokens {

	public static final String ISSUER = "ergo-connect";

	/** Granted to every normal session. Sessions that must change their password get no authorities at all. */
	public static final String SESSION_ACTIVE = "SESSION_ACTIVE";

	private static final String COMPANY = "cid";

	private static final String COMPANY_TYPE = "ctype";

	private static final String ROLE = "role";

	private static final String SESSION = "sid";

	private static final String PASSWORD_CHANGE_REQUIRED = "pwc";

	/** Present only on a "Login as" shadow session: the id of the Super Admin acting as this user. */
	private static final String IMPERSONATOR = "imp";

	private final NimbusJwtEncoder encoder;

	private final Duration ttl;

	AccessTokens(SecurityProperties props) {
		this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(props.jwtKey()));
		this.ttl = props.accessTokenTtl();
	}

	public String issue(CurrentUser user, boolean passwordChangeRequired) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
			.issuer(ISSUER)
			.subject(String.valueOf(user.userId()))
			.issuedAt(now)
			.expiresAt(now.plus(ttl))
			.claim(COMPANY, user.companyId())
			.claim(COMPANY_TYPE, user.companyType().name())
			.claim(ROLE, user.role().name())
			.claim(SESSION, user.sessionId())
			.claim(PASSWORD_CHANGE_REQUIRED, passwordChangeRequired);
		if (user.impersonatorId() != null) {
			builder.claim(IMPERSONATOR, user.impersonatorId());
		}
		JwtClaimsSet claims = builder.build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

	public Duration ttl() {
		return ttl;
	}

	public static CurrentUser toCurrentUser(Jwt jwt) {
		return new CurrentUser(Long.parseLong(jwt.getSubject()), jwt.<Number>getClaim(COMPANY).longValue(),
				CompanyType.valueOf(jwt.getClaimAsString(COMPANY_TYPE)), Role.valueOf(jwt.getClaimAsString(ROLE)),
				jwt.<Number>getClaim(SESSION).longValue(),
				jwt.<Number>getClaim(IMPERSONATOR) == null ? null : jwt.<Number>getClaim(IMPERSONATOR).longValue());
	}

	public static Collection<GrantedAuthority> authorities(Jwt jwt) {
		if (Boolean.TRUE.equals(jwt.getClaimAsBoolean(PASSWORD_CHANGE_REQUIRED))) {
			return List.of();
		}
		return List.of(new SimpleGrantedAuthority(SESSION_ACTIVE),
				new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString(ROLE)));
	}

}
