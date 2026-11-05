package com.ergo.connect.documents.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DisabledVirusScannerTest {

	private static final byte[] EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
		.getBytes();

	private static MockEnvironment withProfiles(String... profiles) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(profiles);
		return env;
	}

	@Test
	void refusesToStartUnlessPlainDevOrAccepted() {
		for (String[] profiles : new String[][] { { "prod" }, { "staging" }, { "dev", "prod" }, {} }) {
			assertThatThrownBy(() -> new DisabledVirusScanner(withProfiles(profiles)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("dev profile");
			// anything but exactly true is no acceptance
			for (String value : new String[] { "false", "", "yes", "1", "on", "TRUE" }) {
				MockEnvironment env = withProfiles(profiles).withProperty("ergo.clamav.unscanned-uploads-accepted",
						value);
				assertThatThrownBy(() -> new DisabledVirusScanner(env)).isInstanceOf(IllegalStateException.class);
			}
		}
	}

	@Test
	void approvesEverythingUnderDev() {
		DisabledVirusScanner scanner = new DisabledVirusScanner(withProfiles("dev"));
		assertThatCode(() -> scanner.scan(EICAR)).doesNotThrowAnyException();
	}

	@Test
	void prodStartsOnlyWithTheRiskAcceptanceFlag() {
		MockEnvironment env = withProfiles("prod").withProperty("ergo.clamav.unscanned-uploads-accepted", "true");
		DisabledVirusScanner scanner = new DisabledVirusScanner(env);
		assertThatCode(() -> scanner.scan(EICAR)).doesNotThrowAnyException();
	}

}
