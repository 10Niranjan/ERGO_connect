package com.ergo.connect.common.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Deny-by-default shell. Phase 1 plugs the JWT filter and company-scope context in here;
 * controllers never hand-roll access checks (ARCHITECTURE.md §1.2).
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http,
			@Value("${ergo.cors.allowed-origins}") List<String> allowedOrigins) throws Exception {
		var cors = new CorsConfiguration();
		cors.setAllowedOrigins(allowedOrigins);
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		var corsSource = new UrlBasedCorsConfigurationSource();
		corsSource.registerCorsConfiguration("/**", cors);

		return http
			.csrf(csrf -> csrf.disable()) // stateless bearer-token API: no session cookie to forge
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.cors(c -> c.configurationSource(corsSource))
			.authorizeHttpRequests(auth -> auth
				// swagger paths are disabled entirely in prod (application-prod.yml)
				.requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
				.anyRequest().authenticated())
			.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.build();
	}

}
