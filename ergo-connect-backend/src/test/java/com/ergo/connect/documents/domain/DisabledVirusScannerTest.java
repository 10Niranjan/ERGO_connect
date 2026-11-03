package com.ergo.connect.documents.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DisabledVirusScannerTest {

	private static MockEnvironment withProfiles(String... profiles) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(profiles);
		return env;
	}

	@Test
	void refusesToStartUnlessPlainDev() {
		for (String[] profiles : new String[][] { { "prod" }, { "staging" }, { "dev", "prod" }, {} }) {
			assertThatThrownBy(() -> new DisabledVirusScanner(withProfiles(profiles)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("dev profile");
		}
	}

	@Test
	void approvesEverythingUnderDev() {
		DisabledVirusScanner scanner = new DisabledVirusScanner(withProfiles("dev"));
		byte[] eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes();
		assertThatCode(() -> scanner.scan(eicar)).doesNotThrowAnyException();
	}

}
