package com.ergo.connect.common.config;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.RedisFixedWindowLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Throttles file uploads (ARCHITECTURE.md §8, Phase 8d-bis). Every module that accepts a {@code MultipartFile}
 * (documents, communication, ofp, quality, quotation, rfq - six controllers at last count) routes through this
 * one interceptor instead of each rolling its own limiter: detected by content-type, not by path, so it covers
 * every upload endpoint automatically, including ones added later, with zero coupling to any of those modules.
 * Keyed by the authenticated user, not IP - uploads are always authenticated, and one compromised account
 * shouldn't be able to flood storage regardless of how many addresses it's used from.
 */
@Component
class UploadRateLimitInterceptor implements HandlerInterceptor {

	private static final int WINDOW_SECONDS = 300;

	private final RedisFixedWindowLimiter limiter;

	private final int maxUploads;

	UploadRateLimitInterceptor(RedisFixedWindowLimiter limiter,
			@Value("${ergo.security.upload-rate-limit.max-attempts:30}") int maxUploads) {
		this.limiter = limiter;
		this.maxUploads = maxUploads;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		String contentType = request.getContentType();
		if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
			return true; // most requests - no point touching Redis for every GET/PATCH
		}
		CurrentUser.find().ifPresent(user -> {
			if (limiter.increment("upload-attempts:" + user.userId(), WINDOW_SECONDS) > maxUploads) {
				throw ApiException.tooManyRequests("Too many uploads. Please try again later.");
			}
		});
		return true;
	}

}
