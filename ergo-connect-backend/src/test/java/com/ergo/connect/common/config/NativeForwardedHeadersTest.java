package com.ergo.connect.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.ergo.connect.TestcontainersConfiguration;

/**
 * Phase 8c: {@code application-prod.yml} sets {@code server.forward-headers-strategy: native}, which only takes
 * effect on a real embedded Tomcat - MockMvc never runs through the {@code RemoteIpValve}, so
 * {@link SecurityHeadersTest} cannot cover this. A real HTTP call from the loopback peer (trusted by Tomcat's
 * default {@code internal-proxies} range) is required to prove the valve is wired up at all.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "server.forward-headers-strategy=native")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class NativeForwardedHeadersTest {

	@LocalServerPort
	int port;

	@Test
	void trustedLoopbackPeerCanMarkTheRequestAsHttpsViaForwardedProto() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
			.header("X-Forwarded-Proto", "https")
			.GET()
			.build();
		HttpResponse<Void> response =
				HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());

		// The test client connects over loopback, which RemoteIpValve trusts by default - so the valve
		// rewrote isSecure()=true from X-Forwarded-Proto, and Spring Security's HSTS writer fired.
		assertThat(response.headers().firstValue("Strict-Transport-Security")).get()
			.asString()
			.contains("max-age=31536000");
	}

}
