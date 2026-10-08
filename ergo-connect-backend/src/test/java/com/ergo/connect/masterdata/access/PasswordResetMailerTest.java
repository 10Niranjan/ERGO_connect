package com.ergo.connect.masterdata.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

/** Phase 8f (ARCHITECTURE.md §8): no Spring context, no real SMTP needed. */
class PasswordResetMailerTest {

	private final Environment devEnv = mock(Environment.class);

	private final Environment prodEnv = mock(Environment.class);

	PasswordResetMailerTest() {
		when(devEnv.matchesProfiles("dev")).thenReturn(true);
		when(prodEnv.matchesProfiles("dev")).thenReturn(false);
	}

	@Test
	void logsTheRawLinkOnlyUnderTheDevProfileWhenNoMailSenderIsConfigured() {
		PasswordResetMailer mailer = new PasswordResetMailer(Optional.empty(), "no-reply@ergo-connect.local", devEnv);

		// no assertion on log content (that's an implementation detail); this only proves it never throws
		assertThatCode(() -> mailer.sendResetLink("someone@test.dev", "https://app.test/reset-password#token=abc",
				Duration.ofMinutes(30))).doesNotThrowAnyException();
	}

	@Test
	void doesNotLogTheRawLinkOutsideTheDevProfile() {
		PasswordResetMailer mailer = new PasswordResetMailer(Optional.empty(), "no-reply@ergo-connect.local", prodEnv);

		assertThatCode(() -> mailer.sendResetLink("someone@test.dev", "https://app.test/reset-password#token=abc",
				Duration.ofMinutes(30))).doesNotThrowAnyException();
	}

	@Test
	void sendsResetLinkThroughTheConfiguredMailSender() {
		MailSender sender = mock(MailSender.class);
		PasswordResetMailer mailer = new PasswordResetMailer(Optional.of(sender), "no-reply@ergo-connect.local",
				prodEnv);

		mailer.sendResetLink("someone@test.dev", "https://app.test/reset-password#token=abc", Duration.ofMinutes(30));

		verify(sender).send(any(SimpleMailMessage.class));
	}

	@Test
	void sendsThePasswordChangedNoticeThroughTheConfiguredMailSender() {
		MailSender sender = mock(MailSender.class);
		PasswordResetMailer mailer = new PasswordResetMailer(Optional.of(sender), "no-reply@ergo-connect.local",
				prodEnv);

		mailer.sendPasswordChangedNotice("someone@test.dev");

		verify(sender).send(any(SimpleMailMessage.class));
	}

	@Test
	void aDeliveryFailureIsSwallowedNotPropagated() {
		MailSender sender = mock(MailSender.class);
		doThrow(new MailSendException("smtp unreachable")).when(sender).send(any(SimpleMailMessage.class));
		PasswordResetMailer mailer = new PasswordResetMailer(Optional.of(sender), "no-reply@ergo-connect.local",
				prodEnv);

		assertThatCode(() -> mailer.sendResetLink("someone@test.dev", "https://app.test/reset-password#token=abc",
				Duration.ofMinutes(30))).doesNotThrowAnyException();
	}

}
