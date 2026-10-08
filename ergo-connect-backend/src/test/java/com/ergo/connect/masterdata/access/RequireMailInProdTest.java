package com.ergo.connect.masterdata.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSender;

/** Phase 8f (ARCHITECTURE.md §8): no Spring context needed - @Profile only gates whether Spring instantiates it. */
class RequireMailInProdTest {

	@Test
	void failsStartupWhenNoMailSenderIsConfigured() {
		assertThatThrownBy(() -> new RequireMailInProd(Optional.empty())).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void doesNothingWhenAMailSenderIsConfigured() {
		assertThatCode(() -> new RequireMailInProd(Optional.of(mock(MailSender.class)))).doesNotThrowAnyException();
	}

}
