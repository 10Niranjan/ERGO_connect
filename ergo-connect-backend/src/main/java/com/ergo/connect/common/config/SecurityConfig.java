package com.ergo.connect.common.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.ergo.connect.common.security.SecurityProperties;
import com.ergo.connect.common.security.jwt.AccessTokens;

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
		var corsSource = new UrlBasedCorsConfigurationSource();
		corsSource.registerCorsConfiguration("/**", cors);

		return http
			.csrf(csrf -> csrf.disable()) // bearer tokens; the session cookie is SameSite=Strict and scoped to /api/auth
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.cors(c -> c.configurationSource(corsSource))
			.authorizeHttpRequests(auth -> auth
				// swagger paths are disabled entirely in prod (application-prod.yml)
				.requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
				.requestMatchers("/error").permitAll() // so real errors aren't masked as 401; bodies carry no stack traces
				.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
						"/api/auth/forgot-password", "/api/auth/reset-password")
					.permitAll()
				// the only thing a session that must change its password can do
				.requestMatchers(HttpMethod.POST, "/api/auth/change-password").authenticated()
				.anyRequest().hasAuthority(AccessTokens.SESSION_ACTIVE))
			.oauth2ResourceServer(o -> o
				.jwt(jwt -> jwt.jwtAuthenticationConverter(
						token -> new JwtAuthenticationToken(token, AccessTokens.authorities(token), token.getSubject())))
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.build();
	}

	@Bean
	JwtDecoder jwtDecoder(SecurityProperties props) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(props.jwtKey()).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(AccessTokens.ISSUER));
		return decoder;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // bcrypt, upgradeable
	}

}
