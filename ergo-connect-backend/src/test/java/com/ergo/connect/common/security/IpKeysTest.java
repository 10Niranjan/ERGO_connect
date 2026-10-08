package com.ergo.connect.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IpKeysTest {

	@Test
	void stripsAnAppendedPort() {
		assertThat(IpKeys.normalize("203.0.113.1:54321")).isEqualTo("203.0.113.1");
	}

	@Test
	void leavesABareIpv4AddressAlone() {
		assertThat(IpKeys.normalize("203.0.113.1")).isEqualTo("203.0.113.1");
	}

	@Test
	void leavesABareBracketlessIpv6AddressAlone() {
		assertThat(IpKeys.normalize("::1")).isEqualTo("::1");
	}

	@Test
	void stripsAPortFromABracketedIpv6Address() {
		assertThat(IpKeys.normalize("[::1]:54321")).isEqualTo("::1");
	}

}
