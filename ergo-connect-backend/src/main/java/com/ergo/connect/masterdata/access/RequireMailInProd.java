package com.ergo.connect.masterdata.access;

import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailSender;
import org.springframework.stereotype.Component;

/**
 * Fails prod startup outright if no real mail is configured (Phase 8f security review finding): without this,
 * {@code PasswordResetMailer} would silently fall back to logging reset links instead of emailing them - fine
 * in dev, a serious leak in prod, since anyone who can read prod logs could use the link to take over any
 * account. Same "refuse to start rather than silently misbehave" philosophy as {@code SecurityProperties}'
 * {@code jwtSecret} check.
 */
@Component
@Profile("prod")
class RequireMailInProd {

	RequireMailInProd(Optional<MailSender> mailSender) {
		if (mailSender.isEmpty()) {
			throw new IllegalStateException(
					"spring.mail.host (and username/password) must be set in prod so password resets can be delivered - "
							+ "see README.md's Deploy configuration section");
		}
	}

}
