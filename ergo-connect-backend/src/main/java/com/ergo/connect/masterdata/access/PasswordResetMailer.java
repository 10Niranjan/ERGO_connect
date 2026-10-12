package com.ergo.connect.masterdata.access;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

/**
 * Sends password-reset-related email (Phase 8f, ARCHITECTURE.md §8). No outbound email channel existed anywhere
 * in the app before this - {@code spring-boot-starter-mail}'s autoconfiguration only creates a {@code MailSender}
 * bean once {@code spring.mail.host} is set.
 *
 * <p><b>Security review finding, fixed</b>: the raw reset link (which contains a live, usable token) is only
 * ever logged under the {@code dev} profile, where no real SMTP exists and a developer needs some way to grab
 * the link - the same console-log convention {@code DevDataSeeder}/{@code AdminBootstrapRunner} already use.
 * Everywhere else (prod, test, or any other profile) a missing {@code MailSender} logs a generic failure with
 * no token, and {@link RequireMailInProd} fails prod startup outright if it's unconfigured there - so this
 * fallback should never actually run in a real deployment.
 */
@Component
class PasswordResetMailer {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);

	private final Optional<MailSender> mailSender;

	private final String fromAddress;

	private final Environment env;

	PasswordResetMailer(Optional<MailSender> mailSender,
			@Value("${ergo.mail.from:no-reply@ergo-connect.local}") String fromAddress, Environment env) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
		this.env = env;
	}

	void sendResetLink(String toEmail, String resetLink, Duration ttl) {
		if (mailSender.isEmpty()) {
			logUnsent(toEmail, "password reset link", resetLink);
			return;
		}
		send(toEmail, "Reset your ERGO Connect password", """
				We received a request to reset your ERGO Connect password.

				%s

				This link expires in %d minutes. If you didn't request this, you can ignore this email.""".formatted(resetLink,
				ttl.toMinutes()));
	}

	/** The second-factor login code (Phase 8g, SRS §12.4.1 "optional two-factor authentication"). */
	void sendLoginCode(String toEmail, String code, Duration ttl) {
		if (mailSender.isEmpty()) {
			logUnsent(toEmail, "login code", code);
			return;
		}
		send(toEmail, "Your ERGO Connect login code", """
				Your ERGO Connect login code is:

				%s

				This code expires in %d minutes. If you didn't try to sign in, you can ignore this email.""".formatted(code,
				ttl.toMinutes()));
	}

	/** Security review finding (M3): the account owner should hear about this from somewhere other than being
	 * signed out everywhere at once. */
	void sendPasswordChangedNotice(String toEmail) {
		if (mailSender.isEmpty()) {
			log.warn("No MailSender configured - could not notify {} that their password changed", toEmail);
			return;
		}
		// wording stays accurate for both callers - changePassword() keeps the current session, resetPassword()
		// signs out every device - so this doesn't claim either one specifically
		send(toEmail, "Your ERGO Connect password was changed", """
				Your ERGO Connect password was just changed.

				If this wasn't you, contact your ERGO Connect administrator immediately.""");
	}

	private void logUnsent(String toEmail, String label, String value) {
		if (env.matchesProfiles("dev")) {
			log.warn("No spring.mail.host configured - {} for {}: {}", label, toEmail, value);
		}
		else {
			log.error("No MailSender configured - could not deliver {} to {}", label, toEmail);
		}
	}

	private void send(String toEmail, String subject, String text) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromAddress);
		message.setTo(toEmail);
		message.setSubject(subject);
		message.setText(text);
		try {
			mailSender.get().send(message);
		}
		catch (MailException e) {
			// the caller's response is already the same generic message either way - nothing to leak by failing
			// quietly, but a delivery failure here means the user simply never gets the email, so it's logged
			log.error("Failed to send email to {}: {}", toEmail, e.toString());
		}
	}

}
