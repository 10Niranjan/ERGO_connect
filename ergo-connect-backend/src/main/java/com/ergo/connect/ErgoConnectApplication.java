package com.ergo.connect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @EnableScheduling}: Phase 5c's deviation-expiry daily job (quality.domain.DeviationService) - the first
 * scheduled task in this codebase. */
@SpringBootApplication
@EnableScheduling
public class ErgoConnectApplication {

	public static void main(String[] args) {
		SpringApplication.run(ErgoConnectApplication.class, args);
	}

}
