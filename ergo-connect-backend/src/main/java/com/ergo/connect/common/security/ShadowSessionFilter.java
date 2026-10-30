package com.ergo.connect.common.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A Super Admin's "Login as" session acts as the viewed user with that user's own role and company scope, so it can do
 * what they can. What it may never do is take over the account itself: change the user's password or two-factor
 * setting, or start another "Login as". Those are refused here, right after the bearer token is read.
 */
public class ShadowSessionFilter extends OncePerRequestFilter {

	/** Account-takeover paths (the other account endpoints, e.g. reset-password, need an ERGO Admin, which no shadow is). */
	private static final Set<String> BLOCKED = Set.of("/api/auth/change-password", "/api/auth/2fa");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		boolean impersonated = CurrentUser.find().map(CurrentUser::impersonated).orElse(false);
		String path = request.getRequestURI();
		if (impersonated && (BLOCKED.contains(path) || path.endsWith("/login-as"))) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
					+ "\"detail\":\"Not allowed while logged in as another user: the account itself cannot be changed.\"}");
			return;
		}
		chain.doFilter(request, response);
	}

}
