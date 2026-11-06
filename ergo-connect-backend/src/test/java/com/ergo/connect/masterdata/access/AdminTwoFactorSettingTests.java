package com.ergo.connect.masterdata.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.objenesis.ObjenesisStd;
import org.springframework.test.util.ReflectionTestUtils;

/** ergo.security.admin-2fa-required turns off admin 2FA, so only the exact word "false" may do it: a stray value stops the start. */
class AdminTwoFactorSettingTests {

	private static AuthService service(String setting) {
		AuthService service = new ObjenesisStd().newInstance(AuthService.class);
		ReflectionTestUtils.setField(service, "adminTwoFactorSetting", setting);
		return service;
	}

	private static boolean required(AuthService service) {
		return (boolean) ReflectionTestUtils.getField(service, "adminTwoFactorRequired");
	}

	@Test
	void trueAndFalseAreTheOnlyAcceptedValues() {
		AuthService on = service("true");
		on.applyAdminTwoFactorSetting();
		assertThat(required(on)).isTrue();

		AuthService off = service("false");
		off.applyAdminTwoFactorSetting();
		assertThat(required(off)).isFalse();

		AuthService spaced = service(" false ");
		spaced.applyAdminTwoFactorSetting();
		assertThat(required(spaced)).isFalse();
	}

	@Test
	void anythingElseRefusesToStart() {
		for (String stray : new String[] { "off", "no", "0", "FALSE", "False", "", "yes", "1" }) {
			assertThatThrownBy(() -> service(stray).applyAdminTwoFactorSetting())
				.as("value '%s'", stray)
				.isInstanceOf(IllegalStateException.class);
		}
	}

}
