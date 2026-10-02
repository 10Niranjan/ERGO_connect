package com.ergo.connect.common.security;

import java.util.Optional;
import java.util.function.Supplier;

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

	private static final ThreadLocal<CurrentUser> ACTING_AS = new ThreadLocal<>();

	/**
	 * Runs a server-side, read-only check as another user (e.g. "could this person read that record?" - the same
	 * module probes that scope the real caller). Never reachable from a request: only code in this codebase calls it,
	 * and the override is cleared on the way out. Do not use it to write.
	 */
	public static <T> T runAs(CurrentUser user, Supplier<T> action) {
		CurrentUser previous = ACTING_AS.get();
		ACTING_AS.set(user);
		try {
			return action.get();
		}
		finally {
			if (previous == null) {
				ACTING_AS.remove();
			}
			else {
				ACTING_AS.set(previous);
			}
		}
	}

	public static Optional<CurrentUser> find() {
		CurrentUser acting = ACTING_AS.get();
		if (acting != null) {
			return Optional.of(acting);
		}
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
