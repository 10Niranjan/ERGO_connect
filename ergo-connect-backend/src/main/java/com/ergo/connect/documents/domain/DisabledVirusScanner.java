package com.ergo.connect.documents.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * DEMO-ONLY: approves every upload without scanning. Active only when {@code ergo.clamav.enabled=false}
 * ({@code ERGO_CLAMAV_ENABLED=false}); the default is the real {@link ClamAvVirusScanner}. It exists so the
 * one-small-VM client demo (deploy/) can run without the ~1.5 GB ClamAV sidecar.
 * <p>
 * Refuses to start unless the active profile is plain {@code dev}, so a stray env var can never switch malware
 * scanning off in production or any other environment (Phase 8i made scanning mandatory). Never active under
 * {@code test} (that has its own fixture). Value must be exactly {@code true} or {@code false}: anything else
 * leaves no scanner bean and startup fails closed.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "ergo.clamav.enabled", havingValue = "false")
class DisabledVirusScanner implements VirusScanner {

	private static final Logger log = LoggerFactory.getLogger(DisabledVirusScanner.class);

	DisabledVirusScanner(Environment env) {
		// Allow-list, not block-list: only a plain dev profile (not dev,prod; not staging/uat/typos) may skip scanning.
		if (!env.acceptsProfiles(Profiles.of("dev & !prod"))) {
			throw new IllegalStateException(
					"ergo.clamav.enabled=false is only allowed under the dev profile: malware scanning is mandatory");
		}
		log.warn("MALWARE SCANNING IS DISABLED (ergo.clamav.enabled=false): uploads are NOT scanned. Demo use only.");
	}

	@Override
	public void scan(byte[] content) {
		// intentionally empty: see class javadoc
	}

}
