package com.ergo.connect.masterdata.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.objenesis.ObjenesisStd;
import org.springframework.test.util.ReflectionTestUtils;

/** ergo.security.skip-otp turns off 2FA, so it must refuse to start anywhere but the dev profile. */
class SkipOtpGuardTests {

	private static AuthService service(boolean skipOtp, String... profiles) {
		AuthService service = new ObjenesisStd().newInstance(AuthService.class); // guard needs only these two fields
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(profiles);
		ReflectionTestUtils.setField(service, "skipOtp", skipOtp);
		ReflectionTestUtils.setField(service, "env", env);
		return service;
	}

	@Test
	void refusedOutsideDev() {
		assertThatThrownBy(() -> service(true, "prod").guardSkipOtp()).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> service(true).guardSkipOtp()).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void allowedInDevAndWhenOff() {
		assertThatCode(() -> service(true, "dev").guardSkipOtp()).doesNotThrowAnyException();
		assertThatCode(() -> service(false, "prod").guardSkipOtp()).doesNotThrowAnyException();
	}

}
