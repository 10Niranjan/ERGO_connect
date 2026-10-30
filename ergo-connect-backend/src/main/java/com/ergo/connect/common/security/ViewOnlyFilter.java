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
 * Layer one of the "Login as" rule: a Super Admin's shadow session is view-only, so under one every request that is
 * not a plain read is refused before it reaches a controller. Runs right after the bearer token is read. This filter is
 * the guarantee. {@code AuditLogService} refuses to write for a shadow session as a backstop, but only for audited
 * writes in services that roll back on an ApiException; it does not stop un-audited writes (read flags, sessions).
 * So a new GET must never change data. No GET in this codebase does.
 */
public class ViewOnlyFilter extends OncePerRequestFilter {

	private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

	/** The only non-reads a shadow session may make: renew itself, change role within the viewed user's roles, leave. */
	private static final Set<String> ALLOWED_POSTS = Set.of("/api/auth/refresh", "/api/auth/logout",
			"/api/auth/switch-role", "/api/auth/impersonation/end");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		boolean viewOnly = CurrentUser.find().map(CurrentUser::viewOnly).orElse(false);
		if (viewOnly && !READS.contains(request.getMethod())
				&& !("POST".equals(request.getMethod()) && ALLOWED_POSTS.contains(request.getRequestURI()))) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
					+ "\"detail\":\"View-only session: nothing can be changed while viewing as another user.\"}");
			return;
		}
		chain.doFilter(request, response);
	}

}
