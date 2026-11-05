package com.ergo.connect.documents.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Approves every upload without scanning. Active only when {@code ergo.clamav.enabled=false}
 * ({@code ERGO_CLAMAV_ENABLED=false}); the default is the real {@link ClamAvVirusScanner}. It exists so a small VM
 * (the deploy/ demo and the Oracle production VM) can run without the ~1.5 GB ClamAV sidecar.
 * <p>
 * Refuses to start unless the active profile is plain {@code dev} or {@code ergo.clamav.unscanned-uploads-accepted}
 * is also {@code true} (the client accepted the risk for Oracle production, ARCHITECTURE.md §8 2026-11-05), so a
 * single stray env var can never switch malware scanning off (Phase 8i made scanning the default). Never active under
 * {@code test} (that has its own fixture). Value must be exactly {@code true} or {@code false}: anything else
 * leaves no scanner bean and startup fails closed.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "ergo.clamav.enabled", havingValue = "false")
class DisabledVirusScanner implements VirusScanner {

	private static final Logger log = LoggerFactory.getLogger(DisabledVirusScanner.class);

	DisabledVirusScanner(Environment env) {
		// Allow-list, not block-list: a plain dev profile (not dev,prod; not staging/uat/typos), or anywhere else only
		// with the explicit risk-acceptance flag (Oracle production, ARCHITECTURE.md §8 2026-11-05).
		// exactly "true": Spring's Boolean conversion would also take yes/on/1
		boolean accepted = "true".equals(env.getProperty("ergo.clamav.unscanned-uploads-accepted"));
		if (!accepted && !env.acceptsProfiles(Profiles.of("dev & !prod"))) {
			throw new IllegalStateException("ergo.clamav.enabled=false is only allowed under the dev profile or with "
					+ "ergo.clamav.unscanned-uploads-accepted=true: malware scanning is otherwise mandatory");
		}
		log.warn("MALWARE SCANNING IS DISABLED (ergo.clamav.enabled=false): uploads are NOT scanned.");
	}

	@Override
	public void scan(byte[] content) {
		// intentionally empty: see class javadoc
	}

}
