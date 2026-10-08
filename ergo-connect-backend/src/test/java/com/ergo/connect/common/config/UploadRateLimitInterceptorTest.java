package com.ergo.connect.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.RedisFixedWindowLimiter;
import com.ergo.connect.common.security.Role;

/** Phase 8d-bis (ARCHITECTURE.md §8): no Spring context, exercises preHandle() directly. */
class UploadRateLimitInterceptorTest {

	private final RedisFixedWindowLimiter limiter = mock(RedisFixedWindowLimiter.class);

	@Test
	void ignoresNonMultipartRequests() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rfqs/1");

		boolean allowed = new UploadRateLimitInterceptor(limiter, 30).preHandle(request, new MockHttpServletResponse(),
				null);

		assertThat(allowed).isTrue();
		verifyNoInteractions(limiter);
	}

	@Test
	void allowsAnUnauthenticatedMultipartRequestThrough() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rfqs/1/documents");
		request.setContentType("multipart/form-data");

		boolean allowed = new UploadRateLimitInterceptor(limiter, 30).preHandle(request, new MockHttpServletResponse(),
				null);

		assertThat(allowed).isTrue(); // security itself rejects this before the interceptor matters; don't double-guess
		verifyNoInteractions(limiter);
	}

	@Test
	void blocksOnceTheAuthenticatedUserPassesTheLimit() {
		CurrentUser.runAs(new CurrentUser(42, 1, CompanyType.CUSTOMER, Role.CUSTOMER_RFQ, 1), () -> {
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rfqs/1/documents");
			request.setContentType("multipart/form-data; boundary=x");
			when(limiter.increment(eq("upload-attempts:42"), eq(300))).thenReturn(31L);

			assertThatThrownBy(() -> new UploadRateLimitInterceptor(limiter, 30).preHandle(request,
					new MockHttpServletResponse(), null)).isInstanceOf(ApiException.class);
			return null;
		});
	}

}
