package com.ergo.connect.common.config;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.ergo.connect.common.security.SecurityProperties;
import com.ergo.connect.common.security.ShadowSessionFilter;
import com.ergo.connect.common.security.jwt.AccessTokens;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Deny-by-default. Layer 1 (who you are + which role): bearer JWT here and {@code @PreAuthorize} on controllers.
 * Layer 2 (which records): scoped queries in each module's services. Controllers never hand-roll access checks.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http,
			@Value("${ergo.cors.allowed-origins}") List<String> allowedOrigins) throws Exception {
		var cors = new CorsConfiguration();
		cors.setAllowedOrigins(allowedOrigins);
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Ergo-Client"));
		cors.setExposedHeaders(List.of("X-Permissions-Version"));
		var corsSource = new UrlBasedCorsConfigurationSource();
		corsSource.registerCorsConfiguration("/**", cors);

		return http
			.csrf(csrf -> csrf.disable()) // bearer tokens; the session cookie is SameSite=Strict and scoped to /api/auth
			// the defaults stay (HSTS, nosniff, X-Frame-Options, no-store); the web app's nginx already sends this one (8p F3)
			.headers(h -> h.referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.cors(c -> c.configurationSource(corsSource))
			.authorizeHttpRequests(auth -> auth
				// swagger paths are disabled entirely in prod (application-prod.yml)
				.requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
				.requestMatchers("/error").permitAll() // so real errors aren't masked as 401; bodies carry no stack traces
				.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/verify-otp", "/api/auth/refresh",
						"/api/auth/logout", "/api/auth/forgot-password", "/api/auth/reset-password")
					.permitAll()
				// the only thing a session that must change its password can do
				.requestMatchers(HttpMethod.POST, "/api/auth/change-password").authenticated()
				.anyRequest().hasAuthority(AccessTokens.SESSION_ACTIVE))
			.oauth2ResourceServer(o -> o
				.jwt(jwt -> jwt.jwtAuthenticationConverter(
						token -> new JwtAuthenticationToken(token, AccessTokens.authorities(token), token.getSubject())))
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			// right after the bearer token is read: a Super Admin acting as another user may not take over the account
			.addFilterAfter(new ShadowSessionFilter(), BearerTokenAuthenticationFilter.class)
			.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
				.accessDeniedHandler(SecurityConfig::forbidden))
			.build();
	}

	/** A refusal by the filter chain (e.g. a session that must change its password) is an RFC 9457 document too. */
	private static void forbidden(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException e) throws IOException {
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
				+ "\"detail\":\"Your role may not do this.\"}");
	}

	@Bean
	JwtDecoder jwtDecoder(SecurityProperties props, OAuth2TokenValidator<Jwt> sessionStillValid) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(props.jwtKey()).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(AccessTokens.ISSUER),
				sessionStillValid));
		return decoder;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // bcrypt, upgradeable
	}

}
