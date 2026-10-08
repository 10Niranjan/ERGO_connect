package com.ergo.connect.masterdata.access;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.SecurityProperties;

/**
 * Does the real forgot-password work (DB lookup, token creation, email) off the request thread (Phase 8f
 * security review finding, "H2"): {@link AuthService#forgotPassword} returns as soon as the rate limiters pass,
 * regardless of whether the email is registered - a SELECT either way is timing-equal, but the INSERT and,
 * far more variably, a synchronous SMTP send only for a real user would otherwise let an attacker tell the two
 * cases apart by response latency, reopening what the login flow's dummy-bcrypt check exists to close.
 *
 * <p>Must be a separate bean, not a private method on {@code AuthService}: Spring's {@code @Async} proxy can't
 * intercept a self-invoked call.
 */
@Component
class PasswordResetRequestHandler {

	private final UserRepository users;

	private final CompanyRepository companies;

	private final PasswordResetTokenStore resetTokens;

	private final PasswordResetMailer mailer;

	private final AuditLogService audit;

	private final Duration ttl;

	private final String frontendBaseUrl;

	PasswordResetRequestHandler(UserRepository users, CompanyRepository companies,
			PasswordResetTokenStore resetTokens, PasswordResetMailer mailer, AuditLogService audit,
			SecurityProperties props, @Value("${ergo.frontend-base-url}") String frontendBaseUrl) {
		this.users = users;
		this.companies = companies;
		this.resetTokens = resetTokens;
		this.mailer = mailer;
		this.audit = audit;
		this.ttl = props.passwordResetTokenTtl();
		this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
				? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1) : frontendBaseUrl;
	}

	/** {@code ip}/{@code channel}: captured by the caller on the request thread, since once this handler runs
	 * async, {@code RequestContextHolder} - and so {@code AuditLogService}'s own ip/channel lookup - is empty. */
	@Async("passwordResetExecutor")
	@Transactional
	public void handle(String email, String ip, String channel) {
		users.findByEmail(email.trim()).ifPresent(user -> {
			Company company = companies.findById(user.companyId).orElseThrow();
			// security review decision (ARCHITECTURE.md §8): ERGO-side access stays gated by another admin's
			// manual reset (SRS §12.13.1), not just one inbox - customers/suppliers still get full self-service
			if (!user.active || company.status != CompanyStatus.ACTIVE || company.type == CompanyType.ERGO) {
				return;
			}
			String token = resetTokens.create(user.id, Instant.now().plus(ttl));
			mailer.sendResetLink(user.email, frontendBaseUrl + "/reset-password#token=" + token, ttl);
			audit.record(User.class, user.id, "PASSWORD_RESET_REQUESTED", null, null, null, ip, channel);
		});
	}

}
