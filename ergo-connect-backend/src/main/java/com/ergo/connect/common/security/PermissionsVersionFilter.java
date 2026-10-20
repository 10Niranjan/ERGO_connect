package com.ergo.connect.common.security;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Stamps every API response with the current access-policy version, so the client notices a permission change on its
 * next call and refetches its permissions and sidebar (no polling, no re-login). The version is not sensitive.
 */
@Component
class PermissionsVersionFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Permissions-Version";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			AccessPolicy.pin(); // one consistent access sheet for the whole request
			response.setHeader(HEADER, String.valueOf(AccessPolicy.version()));
		}
		catch (IllegalStateException e) {
			// policy not loaded yet (startup): no header, the client simply does not compare
		}
		try {
			chain.doFilter(request, response);
		}
		finally {
			AccessPolicy.unpin();
		}
	}

}
