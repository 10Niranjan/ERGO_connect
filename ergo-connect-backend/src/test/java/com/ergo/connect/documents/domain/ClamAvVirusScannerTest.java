package com.ergo.connect.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves {@link ClamAvVirusScanner} against a real clamd (Phase 8i) rather than mocking the socket protocol - the
 * thing worth testing here is the wire format itself (the zINSTREAM command, length-prefixed chunks, the
 * null-terminated reply), which a mock would just assume correct. Not part of the shared {@code @IntegrationTest}
 * context: {@link EicarOnlyVirusScanner} covers that (see {@link VirusScanner}'s javadoc for why) - a real
 * antivirus engine is too slow to boot for every one of ~400 test methods. {@code clamav/clamav:1.4} (non-base)
 * ships with signature databases pre-loaded, so no freshclam download happens on container start. The container's
 * env mirrors {@code docker/docker-compose.local.yml} exactly, including the {@code Alert*}/{@code MaxScanTime}
 * settings {@link #aNestedArchiveIsFlaggedEvenThoughItIsNeverFullyUnpacked} guards (security review H1 - clamd's
 * own defaults silently pass a deeply nested or oversized archive instead of flagging it).
 */
@Testcontainers
class ClamAvVirusScannerTest {

	/** The standard EICAR antivirus test string - every real engine is built to flag exactly this. */
	private static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

	@Container
	static final GenericContainer<?> CLAMAV = new GenericContainer<>(DockerImageName.parse("clamav/clamav:1.4"))
			.withExposedPorts(3310)
			.withEnv("CLAMD_CONF_StreamMaxLength", "60M")
			.withEnv("CLAMD_CONF_MaxScanTime", "5000")
			.withEnv("CLAMD_CONF_AlertExceedsMax", "yes")
			.withEnv("CLAMD_CONF_AlertEncryptedArchive", "yes")
			.withEnv("CLAMD_CONF_AlertEncryptedDoc", "yes")
			.withEnv("CLAMD_CONF_AlertOLE2Macros", "yes")
			.withEnv("CLAMAV_NO_FRESHCLAMD", "true")
			.waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));

	private static ClamAvVirusScanner scanner() {
		return new ClamAvVirusScanner(CLAMAV.getHost(), CLAMAV.getMappedPort(3310), 10_000);
	}

	@Test
	void cleanContentPasses() {
		scanner().scan("an ordinary drawing, nothing to see here".getBytes(StandardCharsets.UTF_8));
		// no exception = clean
	}

	@Test
	void eicarContentIsFlaggedAsInfected() {
		assertThatThrownBy(() -> scanner().scan(EICAR.getBytes(StandardCharsets.US_ASCII)))
				.isInstanceOfSatisfying(InfectedFileException.class,
						e -> assertThat(e.signature()).containsIgnoringCase("eicar"));
	}

	@Test
	void anUnreachableScannerFailsClosed() {
		ClamAvVirusScanner unreachable = new ClamAvVirusScanner(CLAMAV.getHost(), 1, 1000); // port 1: nothing listens
		assertThatThrownBy(() -> unreachable.scan("content".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(VirusScanException.class);
	}

	/** Security review H1, regression guard: with clamd's stock defaults, a zip nested far deeper than
	 * {@code MaxRecursion} (17) comes back {@code stream: OK} - not actually unpacked that deep, but never
	 * flagged either. {@code AlertExceedsMax} (set on {@link #CLAMAV} above, same as {@code
	 * docker-compose.local.yml}) turns that into an explicit FOUND instead of a silent pass. Builds the archive
	 * with plain {@code java.util.zip} - no password/encryption support there, so this is the lazy half of H1's
	 * regression coverage; the encrypted-archive half was proven manually against the exact image/config during
	 * the review (see ARCHITECTURE.md §8) rather than automated, since the JDK has no built-in way to write an
	 * encrypted zip. */
	@Test
	void aNestedArchiveIsFlaggedEvenThoughItIsNeverFullyUnpacked() throws IOException {
		byte[] nested = nestedZip(20, EICAR.getBytes(StandardCharsets.US_ASCII));
		assertThatThrownBy(() -> scanner().scan(nested)).isInstanceOfSatisfying(InfectedFileException.class,
				e -> assertThat(e.signature()).containsIgnoringCase("exceeded"));
	}

	private static byte[] nestedZip(int depth, byte[] innermost) throws IOException {
		byte[] current = innermost;
		for (int i = 0; i < depth; i++) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ZipOutputStream zos = new ZipOutputStream(baos)) {
				zos.putNextEntry(new ZipEntry("layer-" + i));
				zos.write(current);
				zos.closeEntry();
			}
			current = baos.toByteArray();
		}
		return current;
	}

}
