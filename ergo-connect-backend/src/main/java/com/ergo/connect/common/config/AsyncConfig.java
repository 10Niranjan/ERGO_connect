package com.ergo.connect.common.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Backs the forgot-password flow's async dispatch (Phase 8f, ARCHITECTURE.md §8) - a small, explicitly bounded
 * pool, not Boot's unbounded-queue default, since this is reachable pre-authentication and already rate-limited
 * at the entry point ({@code ForgotPasswordRateLimiter}) rather than here.
 */
@Configuration
@EnableAsync
class AsyncConfig {

	@Bean
	Executor passwordResetExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("password-reset-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10); // a redeploy shouldn't silently drop a queued reset email
		// no explicit initialize() call: Spring's own bean lifecycle (InitializingBean.afterPropertiesSet())
		// already calls it once - calling it here too would build a second, never-shut-down thread pool
		return executor;
	}

}
