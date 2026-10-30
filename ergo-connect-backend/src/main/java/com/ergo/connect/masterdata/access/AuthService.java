package com.ergo.connect.masterdata.access;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.AccessPolicy;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.common.security.SecurityProperties;
import com.ergo.connect.common.security.jwt.AccessTokens;
import com.ergo.connect.masterdata.access.AuthDtos.Impersonation;
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

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private static final int MAX_FAILED_LOGINS = 5;

	private static final Duration LOCKOUT = Duration.ofMinutes(15);

	private static final String INVALID_LOGIN = "Invalid email or password";

	private static final String SESSION_EXPIRED = "Your session has expired. Please sign in again.";

	private static final String INVALID_CODE = "Invalid or expired code. Please sign in again.";

	private static final Duration OTP_TTL = Duration.ofMinutes(5);

	/** A Super Admin's view-only "Login as" lasts this long at most; there is no extension, start a new one. */
	static final Duration SHADOW_TTL = Duration.ofMinutes(30);

	/** A new login: the session payload plus the raw session token for the HttpOnly cookie - unless the account has
	 * 2FA enabled, in which case {@code otpRequired} is true and every other field is null (Phase 8g). */
	public record LoginResult(SessionResponse session, String sessionToken, Duration sessionTtl, boolean otpRequired) {

		static LoginResult otpChallenge() {
			return new LoginResult(null, null, null, true);
		}

		static LoginResult ofSession(SessionResponse session, String sessionToken, Duration sessionTtl) {
			return new LoginResult(session, sessionToken, sessionTtl, false);
		}
	}

	private final UserRepository users;

	private final CompanyRepository companies;

	private final SessionStore sessions;

	private final RoleCatalog roleCatalog;

	private final PasswordEncoder passwordEncoder;

	private final AccessTokens accessTokens;

	private final LoginRateLimiter rateLimiter;

	private final ChangePasswordRateLimiter changePasswordRateLimiter;

	private final PasswordResetTokenStore resetTokens;

	private final ForgotPasswordRateLimiter forgotPasswordRateLimiter;

	private final PasswordResetRequestHandler resetRequestHandler;

	private final PasswordResetMailer mailer;

	private final OtpCodeStore otpCodes;

	private final OtpRateLimiter otpRateLimiter;

	private final AuditLogService audit;

	private final Duration sessionTtl;

	/** Checked for unknown emails so they take as long as wrong passwords (no account enumeration by timing). */
	private final String dummyHash;

	/** Dev profile only (application-dev.yml): skips the 2FA challenge. Never set outside dev. */
	@org.springframework.beans.factory.annotation.Value("${ergo.security.skip-otp:false}")
	private boolean skipOtp;

	@org.springframework.beans.factory.annotation.Autowired
	private org.springframework.core.env.Environment env;

	/** Fail closed: the flag disables mandatory Super Admin 2FA, so it may only ever be on in the dev profile. */
	@jakarta.annotation.PostConstruct
	void guardSkipOtp() {
		if (!skipOtp) {
			return;
		}
		if (!env.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"))) {
			throw new IllegalStateException("ergo.security.skip-otp disables 2FA and is only allowed in the dev profile");
		}
		log.warn("ergo.security.skip-otp is ON: 2FA login codes are skipped. Dev only.");
	}

	AuthService(UserRepository users, CompanyRepository companies, SessionStore sessions, RoleCatalog roleCatalog,
			PasswordEncoder passwordEncoder, AccessTokens accessTokens, LoginRateLimiter rateLimiter,
			ChangePasswordRateLimiter changePasswordRateLimiter, PasswordResetTokenStore resetTokens,
			ForgotPasswordRateLimiter forgotPasswordRateLimiter, PasswordResetRequestHandler resetRequestHandler,
			PasswordResetMailer mailer, OtpCodeStore otpCodes, OtpRateLimiter otpRateLimiter, AuditLogService audit,
			SecurityProperties props) {
		this.users = users;
		this.companies = companies;
		this.sessions = sessions;
		this.roleCatalog = roleCatalog;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
		this.rateLimiter = rateLimiter;
		this.changePasswordRateLimiter = changePasswordRateLimiter;
		this.resetTokens = resetTokens;
		this.forgotPasswordRateLimiter = forgotPasswordRateLimiter;
		this.resetRequestHandler = resetRequestHandler;
		this.mailer = mailer;
		this.otpCodes = otpCodes;
		this.otpRateLimiter = otpRateLimiter;
		this.audit = audit;
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
		user.failedLoginCount = 0;
		user.lockedUntil = null;
		// checked here, not just at grant time (security review finding M1): a flag set only when ERGO_ADMIN is
		// granted misses any row that predates this feature or was granted outside UserAdminService/AdminBootstrapRunner
		if (!user.twoFactorEnabled && user.roles.contains(Role.ERGO_ADMIN)) {
			user.twoFactorEnabled = true;
			audit.record(User.class, user.id, "TWO_FACTOR_ENABLED", null, null, null);
		}
		if (user.twoFactorEnabled && !skipOtp) {
			// no session yet - credentials proved identity, but the login isn't complete until verifyOtp(). The IP
			// slot is deliberately NOT released here (security review finding L4): a password holder could
			// otherwise call /login over and over at no cost, each time emailing the victim's inbox a fresh code.
			String code = otpCodes.create(user.id, now.plus(OTP_TTL));
			mailer.sendLoginCode(user.email, code, OTP_TTL);
			return LoginResult.otpChallenge();
		}
		rateLimiter.release(ip); // a successful login shouldn't cost this shared IP one of its attempts
		return finishLogin(user, company, ip);
	}

	/** Completes a login once the password (and, if enabled, the OTP) are both verified. */
	public LoginResult verifyOtp(String email, String code, String ip) {
		// keyed by IP, not email (security review finding H1): an email-keyed limiter let anyone who merely knows
		// a victim's address burn their attempt budget with junk codes, locking them out of their own correct one
		// with no password needed - exactly the targeted-lockout DoS login's own IP-keyed limiter avoids.
		otpRateLimiter.checkAndRecord(ip);
		User user = users.findByEmail(email.trim()).orElse(null);
		// an unknown email still costs a real UPDATE statement (dummy user id, never matches) - the same
		// equal-cost-either-way precedent login()'s own dummy-hash check sets, so response time can't tell an
		// unregistered email apart from a wrong code (security review finding H1, secondary note)
		boolean valid = otpCodes.verify(user == null ? -1L : user.id, code);
		if (user == null || !valid) {
			log.warn("Rejected OTP verify attempt for {} from {}", email, ip); // leaves a trace of brute-forcing
			throw ApiException.unauthorized(INVALID_CODE);
		}
		Company company = companyOf(user);
		if (!user.active || company.status != CompanyStatus.ACTIVE) {
			throw ApiException.unauthorized(INVALID_CODE);
		}
		otpRateLimiter.release(ip); // a genuine success shouldn't cost a later legitimate attempt from this IP
		return finishLogin(user, company, ip);
	}

	private LoginResult finishLogin(User user, Company company, String ip) {
		Instant now = Instant.now();
		Role role = startingRole(user, null);
		user.lastLoginAt = now;
		SessionStore.NewSession session = sessions.create(user.id, role, now.plus(sessionTtl), ip);
		return LoginResult.ofSession(response(user, company, role, session.id(), null), session.token(), sessionTtl);
	}

	/**
	 * Self-service toggle (SRS §12.4.1). ERGO Admin accounts can't turn it off. Turning it off needs the current
	 * password (security review finding M2) - otherwise a stolen in-memory access token alone would be enough to
	 * permanently drop the second factor on an account the attacker also happens to know the password for, with
	 * the owner never told; {@code currentPassword} is required precisely for the disable path.
	 */
	public void setTwoFactor(boolean enabled, String currentPassword) {
		CurrentUser me = CurrentUser.get();
		User user = users.findById(me.userId()).orElseThrow();
		if (!enabled && user.roles.contains(Role.ERGO_ADMIN)) {
			throw ApiException.unprocessable("ERGO Admin accounts require two-factor authentication");
		}
		if (!enabled) {
			changePasswordRateLimiter.checkAndRecord(me.userId()); // same brute-force cap changePassword() uses
			Instant now = Instant.now();
			if (user.lockedUntil != null && user.lockedUntil.isAfter(now)) {
				throw ApiException.tooManyRequests("Too many failed attempts. Please try again later.");
			}
			if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.passwordHash)) {
				if (++user.failedLoginCount >= MAX_FAILED_LOGINS) {
					user.lockedUntil = now.plus(LOCKOUT);
					user.failedLoginCount = 0;
				}
				throw ApiException.unprocessable("Current password is incorrect");
			}
			changePasswordRateLimiter.release(me.userId());
			user.failedLoginCount = 0;
			user.lockedUntil = null;
		}
		if (user.twoFactorEnabled != enabled) {
			user.twoFactorEnabled = enabled;
			audit.record(User.class, user.id, enabled ? "TWO_FACTOR_ENABLED" : "TWO_FACTOR_DISABLED", null, null, null);
		}
		if (!enabled) {
			otpCodes.invalidateAllFor(user.id); // no outstanding code should still work once the feature is off
			mailer.sendTwoFactorDisabledNotice(user.email); // the owner should hear about this from somewhere else
		}
	}

	/**
	 * Fresh access token for an existing login. Re-checks everything that may have changed since. While the browser
	 * holds a live "Login as" cookie the view-only session wins; once it has ended or can no longer be trusted it is
	 * revoked and the Super Admin's own session answers instead, so Exit and the 30-minute end land back on their own
	 * account without a new login.
	 */
	public SessionResponse refresh(String sessionToken, String shadowToken) {
		if (shadowToken != null) {
			SessionResponse shadow = refreshShadow(shadowToken, sessionToken);
			if (shadow != null) {
				return shadow;
			}
		}
		if (sessionToken == null) {
			throw ApiException.unauthorized("Not signed in");
		}
		SessionStore.Session session = sessions.findActive(sessionToken)
			.filter(s -> s.impersonatedBy() == null) // a shadow token belongs in the shadow cookie only
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
		return response(user, company, role, session.id(), null);
	}

	/** The view-only session, or null (after ending it) when it may no longer stand. */
	private SessionResponse refreshShadow(String shadowToken, String adminToken) {
		SessionStore.Session shadow = sessions.findActive(shadowToken).orElse(null);
		if (shadow == null || shadow.impersonatedBy() == null) {
			return null;
		}
		SessionStore.Session parent = adminToken == null ? null : sessions.findActive(adminToken).orElse(null);
		User admin = parent == null ? null : users.findById(parent.userId()).orElse(null);
		User target = users.findById(shadow.userId()).orElse(null);
		boolean standing = parent != null && admin != null && parent.id() == shadow.parentSessionId()
				&& parent.impersonatedBy() == null && parent.userId() == shadow.impersonatedBy()
				&& parent.role() == Role.ERGO_ADMIN && admin.active && admin.deletedAt == null
				&& admin.roles.contains(Role.ERGO_ADMIN) && companyOf(admin).status == CompanyStatus.ACTIVE
				&& target != null && canShadow(target, companyOf(target));
		Role role = null;
		if (standing) {
			try {
				role = startingRole(target, shadow.role());
			}
			catch (ApiException e) {
				standing = false; // the viewed user lost every active role
			}
		}
		if (!standing) {
			sessions.revokeById(shadow.id());
			users.findById(shadow.impersonatedBy()).ifPresent(a -> auditEnded(a, shadow.userId(), "NO_LONGER_VALID"));
			return null;
		}
		if (role != shadow.role()) {
			sessions.setRole(shadow.id(), target.id, role);
		}
		return response(target, companyOf(target), role, shadow.id(), admin.id);
	}

	/** Who a Super Admin may log in as: an active, undeleted user of an active company who is not a Super Admin. */
	static boolean canShadow(User target, Company company) {
		return target.active && target.deletedAt == null && company.status == CompanyStatus.ACTIVE
				&& !target.roles.contains(Role.ERGO_ADMIN);
	}

	/**
	 * Super Admin "Login as" (docs/PERMISSIONS.md section 13): a view-only session for another user, started from the
	 * admin's own session, for a stated reason, audited. The admin's own session is untouched.
	 */
	public LoginResult startImpersonation(long targetUserId, String reason, String ip) {
		CurrentUser me = CurrentUser.get();
		if (me.viewOnly() || me.role() != Role.ERGO_ADMIN) {
			throw ApiException.forbidden("Only a Super Admin can log in as another user");
		}
		User admin = users.findById(me.userId()).orElseThrow();
		if (!admin.roles.contains(Role.ERGO_ADMIN)) {
			throw ApiException.forbidden("Only a Super Admin can log in as another user");
		}
		String why = reason == null ? "" : reason.trim();
		if (why.length() < 5 || why.length() > 500) {
			throw ApiException.unprocessable("A reason of 5 to 500 characters is required to log in as another user");
		}
		User target = users.findById(targetUserId).filter(u -> u.deletedAt == null)
			.orElseThrow(() -> ApiException.notFound("User"));
		if (target.id == admin.id) {
			throw ApiException.unprocessable("You cannot log in as yourself");
		}
		if (target.roles.contains(Role.ERGO_ADMIN)) {
			throw ApiException.unprocessable("A Super Admin account cannot be viewed this way");
		}
		Company company = companyOf(target);
		if (!canShadow(target, company)) {
			throw ApiException.unprocessable("This user or their company is not active");
		}
		Role role;
		try {
			role = startingRole(target, null);
		}
		catch (ApiException e) {
			throw ApiException.unprocessable("This user has no active role, so there is nothing to view");
		}
		Instant now = Instant.now();
		// the admin's own session must be live and currently acting as Super Admin (not just an older token saying so)
		SessionStore.Session own = sessions.findLiveById(me.sessionId())
			.filter(s -> s.role() == Role.ERGO_ADMIN && s.impersonatedBy() == null)
			.orElseThrow(() -> ApiException.forbidden("Only a Super Admin can log in as another user"));
		Instant parentEnds = own.expiresAt();
		Instant ends = now.plus(SHADOW_TTL).isBefore(parentEnds) ? now.plus(SHADOW_TTL) : parentEnds;
		for (long replaced : sessions.revokeShadowsOf(admin.id)) { // one view at a time: the open one ends
			auditEnded(admin, replaced, "REPLACED");
		}
		SessionStore.NewSession session = sessions.createShadow(target.id, role, ends, ip, admin.id, me.sessionId(), why);
		audit.reason(why);
		audit.record(User.class, target.id, "IMPERSONATION_STARTED", null, null, null); // actor: the admin
		return LoginResult.ofSession(response(target, company, role, session.id(), admin.id), session.token(),
				Duration.between(now, ends));
	}

	/** Leaves a view-only session (called with the shadow token); the admin's own session carries on. */
	public void endImpersonation() {
		CurrentUser me = CurrentUser.get();
		if (!me.viewOnly()) {
			throw ApiException.unprocessable("You are not viewing as another user");
		}
		sessions.revokeById(me.sessionId());
		auditEnded(users.findById(me.impersonatorId()).orElseThrow(), me.userId(), "EXIT");
	}

	/**
	 * Records the end of a view as the admin's own act (a view-only session can't write audit rows, and logout and
	 * refresh have no signed-in caller). {@code cause}: EXIT, REPLACED, LOGOUT or NO_LONGER_VALID. A view that simply
	 * runs out after 30 minutes writes nothing; the session row shows when it stopped.
	 */
	private void auditEnded(User admin, long viewedUserId, String cause) {
		CurrentUser.runAs(new CurrentUser(admin.id, admin.companyId, CompanyType.ERGO, Role.ERGO_ADMIN, 0L), () -> {
			audit.record(User.class, viewedUserId, "IMPERSONATION_ENDED", "cause", null, cause);
			return null;
		});
	}

	public SessionResponse switchRole(Role role) {
		CurrentUser me = CurrentUser.get();
		User user = users.findById(me.userId()).orElseThrow();
		if ((user.mustChangePassword && !me.viewOnly()) || !assignableRoles(user).contains(role)
				|| (me.viewOnly() && role == Role.ERGO_ADMIN)) { // never another Super Admin, even if granted mid-view
			throw ApiException.forbidden("This role is not available to you");
		}
		if (!sessions.setRole(me.sessionId(), me.userId(), role)) {
			throw ApiException.unauthorized(SESSION_EXPIRED);
		}
		return response(user, companyOf(user), role, me.sessionId(), me.impersonatorId());
	}

	public SessionResponse changePassword(String currentPassword, String newPassword) {
		CurrentUser me = CurrentUser.get();
		changePasswordRateLimiter.checkAndRecord(me.userId()); // before bcrypt: a stolen token holder gets no free guesses
		User user = users.findById(me.userId()).orElseThrow();
		Instant now = Instant.now();
		if (user.lockedUntil != null && user.lockedUntil.isAfter(now)) {
			// the same account lockout login() uses (ARCHITECTURE.md §8, Phase 8d-bis) - the one backstop that
			// still works if Redis, changePasswordRateLimiter's only protection, is down
			throw ApiException.tooManyRequests("Too many failed attempts. Please try again later.");
		}
		if (!passwordEncoder.matches(currentPassword, user.passwordHash)) {
			if (++user.failedLoginCount >= MAX_FAILED_LOGINS) {
				user.lockedUntil = now.plus(LOCKOUT);
				user.failedLoginCount = 0;
			}
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
		user.failedLoginCount = 0;
		user.lockedUntil = null;
		changePasswordRateLimiter.release(me.userId()); // a genuine change shouldn't cost a future legitimate attempt
		resetTokens.invalidateAllFor(user.id); // any outstanding emailed link is now stale
		otpCodes.invalidateAllFor(user.id); // a code sent under the old password must not still complete a login
		mailer.sendPasswordChangedNotice(user.email);
		sessions.revokeAll(user.id, me.sessionId()); // sign out every other device
		return response(user, companyOf(user), startingRole(user, me.role()), me.sessionId(), null);
	}

	/**
	 * Always the same outcome whether or not the email is registered (SRS §12.4.1 "Forgot password") - the
	 * caller never learns which. The rate limiters are the only synchronous work: the actual lookup, token and
	 * email only happen in {@link PasswordResetRequestHandler}, off this request thread, so a real user's extra
	 * work (an INSERT and a far more variable SMTP send) can never show up as a timing difference here.
	 */
	public void forgotPassword(String email, String ip, String channel) {
		// the IP check runs first: once an IP is over its own limit, it must never still be able to keep
		// spending a victim's separate per-email budget on each further try (security review finding)
		forgotPasswordRateLimiter.checkAndRecordByIp(ip);
		forgotPasswordRateLimiter.checkAndRecord(email);
		try {
			resetRequestHandler.handle(email, ip, channel);
		}
		catch (TaskRejectedException e) {
			// the bounded queue is full - happens before any lookup, so it's the same for every email (no
			// oracle); best-effort delivery just loses this one request rather than 500ing the caller
			log.warn("Password reset request dropped - executor queue full ({})", e.toString());
		}
	}

	/** The token is 256 random bits - unguessable, so unlike login/change-password this needs no rate limit. */
	public void resetPassword(String token, String newPassword) {
		// bcrypt's 72-byte limit, checked before consuming the token - a bad password shouldn't burn the one link
		if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw ApiException.unprocessable("The new password is too long");
		}
		long userId = resetTokens.consume(token)
			.orElseThrow(() -> ApiException.unprocessable("This reset link is invalid or has expired"));
		User user = users.findById(userId).orElseThrow();
		Company company = companyOf(user);
		// re-checked here, not just when the link was issued (security review finding): closes the race where
		// the account was deactivated, its company deactivated, or - defence in depth - it's an ERGO user,
		// after the token was minted but before it was used. The token is already spent either way.
		if (!user.active || company.status != CompanyStatus.ACTIVE || company.type == CompanyType.ERGO) {
			throw ApiException.unprocessable("This reset link is invalid or has expired");
		}
		user.passwordHash = passwordEncoder.encode(newPassword);
		user.mustChangePassword = false;
		user.failedLoginCount = 0;
		user.lockedUntil = null;
		resetTokens.invalidateAllFor(user.id); // any other outstanding emailed link is now stale too
		otpCodes.invalidateAllFor(user.id); // a code sent under the old password must not still complete a login
		audit.reason("Self-service password reset via emailed link");
		audit.record(User.class, user.id, "PASSWORD_RESET_BY_EMAIL", null, null, null);
		mailer.sendPasswordChangedNotice(user.email);
		sessions.revokeAll(user.id, null); // a recovery flow, not a self-service change: every device signed out
	}

	/** Ends the login, and with it any "Login as" session the admin had open (and the one the browser holds). */
	public void logout(String sessionToken, String shadowToken) {
		if (sessionToken != null) {
			sessions.findActive(sessionToken).ifPresent(s -> {
				List<Long> viewed = sessions.revokeShadowsOf(s.userId());
				if (!viewed.isEmpty()) {
					User admin = users.findById(s.userId()).orElseThrow();
					viewed.forEach(id -> auditEnded(admin, id, "LOGOUT"));
				}
			});
			sessions.revoke(sessionToken);
		}
		if (shadowToken != null) {
			sessions.revoke(shadowToken);
		}
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

	private SessionResponse response(User user, Company company, Role role, long sessionId, Long impersonatorId) {
		Map<Role, String> catalog = roleCatalog.activeRoles();
		List<RoleOption> roles = user.roles.stream()
			.filter(catalog::containsKey)
			.sorted()
			.map(r -> new RoleOption(r, catalog.get(r), r.companyType()))
			.toList();
		// a view-only session never has to change the viewed user's password (it could not anyway)
		boolean mustChange = impersonatorId == null && user.mustChangePassword;
		Impersonation impersonation = null;
		if (impersonatorId != null) {
			User admin = users.findById(impersonatorId).orElseThrow();
			impersonation = new Impersonation(admin.firstName + " " + admin.lastName,
					sessions.expiryOf(sessionId).orElseThrow());
		}
		String token = accessTokens.issue(
				new CurrentUser(user.id, company.id, company.type, role, sessionId, impersonatorId), mustChange);
		return new SessionResponse(token, accessTokens.ttl().toSeconds(),
				new SessionUser(user.id, user.firstName, user.lastName, user.email, company.id, company.name,
						company.type, user.twoFactorEnabled),
				role, roles, mustChange, AccessPolicy.view(role, company.type), impersonation);
	}

}
