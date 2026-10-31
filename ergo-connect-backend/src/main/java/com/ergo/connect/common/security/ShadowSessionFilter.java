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

	/**
	 * Approval and decision verbs (SRS §8.1, §11.3: approval stays separate from edit). One Super Admin acting as both the
	 * submitter and the approver would defeat creator-is-not-approver, which compares user ids. Refused by the last path
	 * segment, so a new decision endpoint must use one of these verbs or be added here.
	 */
	private static final Set<String> DECISION_VERBS = Set.of("approve", "reject", "decide", "respond", "accept", "agree",
			"send-back", "return", "pass", "release-with-deviation", "response");

	private static String normalise(String uri) {
		int semi = uri.indexOf(';');
		String p = (semi < 0 ? uri : uri.substring(0, semi)).toLowerCase(java.util.Locale.ROOT);
		while (p.length() > 1 && p.endsWith("/")) {
			p = p.substring(0, p.length() - 1);
		}
		return p;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		boolean impersonated = CurrentUser.find().map(CurrentUser::impersonated).orElse(false);
		String path = normalise(request.getRequestURI());
		boolean write = !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod());
		if (impersonated && (BLOCKED.contains(path) || path.endsWith("/login-as")
				|| (write && DECISION_VERBS.contains(path.substring(path.lastIndexOf('/') + 1))))) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
					+ "\"detail\":\"Not allowed while logged in as another user: the account itself and approval decisions are off limits.\"}");
			return;
		}
		chain.doFilter(request, response);
	}

}
