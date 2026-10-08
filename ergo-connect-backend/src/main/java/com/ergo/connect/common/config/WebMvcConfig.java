package com.ergo.connect.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebMvcConfig implements WebMvcConfigurer {

	private final UploadRateLimitInterceptor uploadRateLimitInterceptor;

	WebMvcConfig(UploadRateLimitInterceptor uploadRateLimitInterceptor) {
		this.uploadRateLimitInterceptor = uploadRateLimitInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(uploadRateLimitInterceptor).addPathPatterns("/api/**");
	}

}
