package com.ergo.connect.common.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.ergo.connect.IntegrationTest;

/**
 * Phase 8c: transport hardening. Spring Security's default header writers are never disabled in
 * {@link SecurityConfig}, so HSTS/clickjacking/MIME-sniffing protection is on by default - this test is the
 * regression guard, not new configuration.
 */
@IntegrationTest
class SecurityHeadersTest {

	@Autowired
	MockMvc mvc;

	@Test
	void hstsAppliesOnlyToRequestsTheServletContainerConsidersSecure() throws Exception {
		// MockMvc's .secure(true) stands in for what a reverse proxy marks via X-Forwarded-Proto once
		// server.forward-headers-strategy is honoured (application-prod.yml) - see HstsHeaderWriter.
		mvc.perform(get("/actuator/health").secure(true))
			.andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")))
			.andExpect(header().string("Strict-Transport-Security", containsString("includeSubDomains")));

		// Never sent over plain HTTP - a bare "Strict-Transport-Security" header on an insecure response
		// would be meaningless and, on some setups, actively misleading.
		mvc.perform(get("/actuator/health"))
			.andExpect(header().doesNotExist("Strict-Transport-Security"));
	}

	@Test
	void clickjackingAndMimeSniffingHeadersAreAlwaysPresent() throws Exception {
		mvc.perform(get("/actuator/health"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().string("Cache-Control", containsString("no-store")));
	}

	@Test
	void apiResponsesSendNoReferrer() throws Exception {
		// 8p F3: the one header SecurityConfig adds on top of the defaults (the web app's nginx sends the same)
		mvc.perform(get("/actuator/health"))
			.andExpect(header().string("Referrer-Policy", "no-referrer"));
	}


}
