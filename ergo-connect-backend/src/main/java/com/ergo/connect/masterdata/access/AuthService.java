package com.ergo.connect.masterdata.access;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.common.security.SecurityProperties;
import com.ergo.connect.common.security.jwt.AccessTokens;
import com.ergo.connect.masterdata.access.AuthDtos.RoleOption;
import com.ergo.connect.masterdata.access.AuthDtos.SessionResponse;
import com.ergo.connect.masterdata.access.AuthDtos.SessionUser;

/**
 * Login, session refresh, role switching and password change. A session acts in one role at a time; switching
 * re-issues the token (ARCHITECTURE.md §8). ApiExceptions here are expected outcomes, so writes made before them
 * (failed-login counters, revocations) are kept.
 */
@Service
@Transactional(noRollbackFor = ApiException.class)
public class AuthService {

	private static final int MAX_FAILED_LOGINS = 5;

	private static final Duration LOCKOUT = Duration.ofMinutes(15);

	private static final String INVALID_LOGIN = "Invalid email or password";

	private static final String SESSION_EXPIRED = "Your session has expired. Please sign in again.";

	/** A new login: the session payload plus the raw session token for the HttpOnly cookie. */
	public record LoginResult(SessionResponse session, String sessionToken, Duration sessionTtl) {
	}

	private final UserRepository users;

	private final CompanyRepository companies;

	private final SessionStore sessions;

	private final RoleCatalog roleCatalog;

	private final PasswordEncoder passwordEncoder;

	private final AccessTokens accessTokens;

	private final LoginRateLimiter rateLimiter;

	private final Duration sessionTtl;

	/** Checked for unknown emails so they take as long as wrong passwords (no account enumeration by timing). */
	private final String dummyHash;

	AuthService(UserRepository users, CompanyRepository companies, SessionStore sessions, RoleCatalog roleCatalog,
			PasswordEncoder passwordEncoder, AccessTokens accessTokens, LoginRateLimiter rateLimiter,
			SecurityProperties props) {
		this.users = users;
		this.companies = companies;
		this.sessions = sessions;
		this.roleCatalog = roleCatalog;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
		this.rateLimiter = rateLimiter;
		this.sessionTtl = props.sessionTtl();
		this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	public LoginResult login(String email, String password, String ip) {
		rateLimiter.checkAndRecord(ip); // before the DB/bcrypt: also throttles a flood of the deliberately-slow check
		User user = users.findByEmail(email.trim()).orElse(null);
		if (user == null) {
			passwordEncoder.matches(password, dummyHash);
			throw ApiException.unauthorized(INVALID_LOGIN);
		}
		Instant now = Instant.now();
		if (user.lockedUntil != null && user.lockedUntil.isAfter(now)) {
			passwordEncoder.matches(password, dummyHash); // same timing as the unknown-email branch: don't reveal
			// via a fast response that this account exists and is merely locked rather than nonexistent
			throw ApiException.unauthorized(INVALID_LOGIN);
		}
		if (!passwordEncoder.matches(password, user.passwordHash)) {
			if (++user.failedLoginCount >= MAX_FAILED_LOGINS) {
				user.lockedUntil = now.plus(LOCKOUT);
				user.failedLoginCount = 0;
			}
			throw ApiException.unauthorized(INVALID_LOGIN);
		}
		Company company = companyOf(user);
		if (!user.active || company.status != CompanyStatus.ACTIVE) {
			throw ApiException.unauthorized(INVALID_LOGIN);
		}
		Role role = startingRole(user, null);
		rateLimiter.release(ip); // a successful login shouldn't cost this shared IP one of its attempts
		user.failedLoginCount = 0;
		user.lockedUntil = null;
		user.lastLoginAt = now;
		SessionStore.NewSession session = sessions.create(user.id, role, now.plus(sessionTtl), ip);
		return new LoginResult(response(user, company, role, session.id()), session.token(), sessionTtl);
	}

	/** Fresh access token for an existing login. Re-checks everything that may have changed since. */
	public SessionResponse refresh(String sessionToken) {
		SessionStore.Session session = sessions.findActive(sessionToken)
			.orElseThrow(() -> ApiException.unauthorized(SESSION_EXPIRED));
		User user = users.findById(session.userId()).orElseThrow();
		Company company = companyOf(user);
		if (!user.active || company.status != CompanyStatus.ACTIVE) {
			sessions.revoke(sessionToken);
			throw ApiException.unauthorized(SESSION_EXPIRED);
		}
		Role role = startingRole(user, session.role()); // falls back if the role was removed meanwhile
		if (role != session.role()) {
			sessions.setRole(session.id(), user.id, role);
		}
		return response(user, company, role, session.id());
	}

	public SessionResponse switchRole(Role role) {
		CurrentUser me = CurrentUser.get();
		User user = users.findById(me.userId()).orElseThrow();
		if (user.mustChangePassword || !assignableRoles(user).contains(role)) {
			throw ApiException.forbidden("This role is not available to you");
		}
		if (!sessions.setRole(me.sessionId(), me.userId(), role)) {
			throw ApiException.unauthorized(SESSION_EXPIRED);
		}
		return response(user, companyOf(user), role, me.sessionId());
	}

	public SessionResponse changePassword(String currentPassword, String newPassword) {
		CurrentUser me = CurrentUser.get();
		User user = users.findById(me.userId()).orElseThrow();
		if (!passwordEncoder.matches(currentPassword, user.passwordHash)) {
			throw ApiException.unprocessable("Current password is incorrect");
		}
		if (newPassword.equals(currentPassword)) {
			throw ApiException.unprocessable("The new password must be different from the current one");
		}
		if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72) { // bcrypt limit
			throw ApiException.unprocessable("The new password is too long");
		}
		user.passwordHash = passwordEncoder.encode(newPassword);
		user.mustChangePassword = false;
		sessions.revokeAll(user.id, me.sessionId()); // sign out every other device
		return response(user, companyOf(user), startingRole(user, me.role()), me.sessionId());
	}

	public void logout(String sessionToken) {
		sessions.revoke(sessionToken);
	}

	/** Mapped roles that are currently active in the catalogue, in enum order. */
	private Set<Role> assignableRoles(User user) {
		Set<Role> roles = EnumSet.noneOf(Role.class);
		roles.addAll(user.roles);
		roles.retainAll(roleCatalog.activeRoles().keySet());
		return roles;
	}

	private Role startingRole(User user, Role preferred) {
		Set<Role> roles = assignableRoles(user);
		if (preferred != null && roles.contains(preferred)) {
			return preferred;
		}
		if (user.defaultRole != null && roles.contains(user.defaultRole)) {
			return user.defaultRole;
		}
		return roles.stream()
			.findFirst()
			.orElseThrow(() -> ApiException
				.forbidden("No active role is assigned to your account. Contact your ERGO Connect administrator."));
	}

	private Company companyOf(User user) {
		return companies.findById(user.companyId).orElseThrow();
	}

	private SessionResponse response(User user, Company company, Role role, long sessionId) {
		Map<Role, String> catalog = roleCatalog.activeRoles();
		List<RoleOption> roles = user.roles.stream()
			.filter(catalog::containsKey)
			.sorted()
			.map(r -> new RoleOption(r, catalog.get(r), r.companyType()))
			.toList();
		String token = accessTokens.issue(new CurrentUser(user.id, company.id, company.type, role, sessionId),
				user.mustChangePassword);
		return new SessionResponse(token, accessTokens.ttl().toSeconds(),
				new SessionUser(user.id, user.firstName, user.lastName, user.email, company.id, company.name,
						company.type),
				role, roles, user.mustChangePassword);
	}

}
