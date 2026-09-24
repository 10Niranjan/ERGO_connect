package com.ergo.connect.common.security;

import java.util.Optional;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.ergo.connect.common.security.jwt.AccessTokens;

/**
 * The authenticated caller, read from the verified access token. A session works in exactly one role at a time
 * (ARCHITECTURE.md §8), so {@link #role()} is the only role that counts for this request.
 */
public record CurrentUser(long userId, long companyId, CompanyType companyType, Role role, long sessionId) {

	public static CurrentUser get() {
		return find().orElseThrow(() -> new IllegalStateException("No authenticated user"));
	}

	public static Optional<CurrentUser> find() {
		return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
				? Optional.of(AccessTokens.toCurrentUser(token.getToken()))
				: Optional.empty();
	}

	/**
	 * Record scope for company-owned data: {@code null} means every company (ERGO roles), otherwise only this company.
	 * ponytail: company-level only; Phase 2 adds KAM account assignments and supplier RFQ assignments.
	 */
	public Long companyScope() {
		return companyType == CompanyType.ERGO ? null : companyId;
	}

}
