package com.ergo.connect.documents.domain;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test fixture, not a demo of weaker scanning (see {@link VirusScanner}'s javadoc for why this exists). Lives in
 * test sources, not main (security review L1): a stray {@code test} profile active in prod must never silently
 * swap out real scanning - with this class compiled only into the test jar, that misconfiguration instead leaves
 * no {@link VirusScanner} bean at all, which fails the app's startup outright rather than quietly skipping scans.
 * Only ever active under the {@code test} profile; Spring's component scan picks it up from the test classpath
 * exactly as it would from main, since scanning is classpath-based, not source-set-aware.
 */
@Component
@Profile("test")
public class EicarOnlyVirusScanner implements VirusScanner {

	/** The standard EICAR antivirus test string (eicar.org) - every real engine, including ClamAV, is built to
	 * flag exactly this, deliberately harmless otherwise. Public: other test classes use it to prove an upload
	 * is actually rejected end-to-end, through the real {@code DocumentStore}/HTTP path. */
	public static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

	/** A second recognized marker, distinct from {@link #EICAR}: simulates the scanner itself failing (as
	 * opposed to finding malware), so tests can prove the fail-closed-on-outage path (503, nothing persisted)
	 * through the real HTTP stack without needing to take down a real ClamAV container mid-test (security
	 * review L3). */
	public static final String SIMULATE_SCANNER_DOWN = "SIMULATE-CLAMAV-DOWN-8f2e1c";

	@Override
	public void scan(byte[] content) {
		String text = new String(content, StandardCharsets.US_ASCII);
		if (text.contains(SIMULATE_SCANNER_DOWN)) {
			throw new VirusScanException("Simulated scanner outage (test fixture)", null);
		}
		if (text.contains(EICAR)) {
			throw new InfectedFileException("Eicar-Test-Signature");
		}
	}

}
