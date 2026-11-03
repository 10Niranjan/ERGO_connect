package com.ergo.connect.documents.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Real malware scanning via clamd's INSTREAM protocol (ClamAV sidecar, Phase 8i). Plain {@code java.net.Socket},
 * no third-party client library: the protocol itself is a handful of steps (a null-terminated command,
 * length-prefixed chunks, a zero-length chunk to end the stream, a null-terminated reply), and the actively
 * maintained Java clamd clients available at the time of writing had too little adoption to trust on a
 * security-critical path - the JDK's own `Socket` is better-reviewed than any of them.
 * <p>
 * Runs in every profile except {@code test} (see {@link VirusScanner}'s javadoc for why) - that includes
 * {@code dev}, where {@code docker/docker-compose.local.yml} runs the same sidecar image this talks to in prod,
 * configured with a raised {@code StreamMaxLength} so it never rejects a file this app's own 50MB upload cap
 * would otherwise accept, and with the {@code Alert*} settings {@code ClamAvVirusScannerTest}'s regression case
 * exists to guard (security review H1 - defaults silently pass a password-protected or deeply nested archive).
 * <p>
 * Security review M1: every caller ({@code DocumentStore.store()}) runs inside a DB transaction holding one of
 * the (small, default 10) Hikari connections for the scan's whole duration, and the uploader controls how long a
 * crafted file takes to scan. {@link #PERMITS} bounds how many scans can run at once, so a burst of slow uploads
 * can't alone exhaust the pool; {@link #CONNECT_TIMEOUT_MILLIS} is short because a healthy sidecar connects
 * near-instantly, and clamd's own {@code MaxScanTime} (set alongside the {@code Alert*} settings above) keeps a
 * single scan's reply well inside {@code ergo.clamav.timeout-ms}, so the one blocking read in {@link #scan} has
 * a real ceiling even for a maliciously slow payload.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "ergo.clamav.enabled", havingValue = "true", matchIfMissing = true)
class ClamAvVirusScanner implements VirusScanner {

	private static final byte[] COMMAND = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);

	private static final int CHUNK_SIZE = 8192;

	private static final int CONNECT_TIMEOUT_MILLIS = 2000;

	/** Comfortably under Hikari's default pool size (10) - a burst of slow/malicious uploads can occupy at most
	 * this many DB connections on the scan step, leaving the rest free for everything else the app does. */
	private static final int PERMITS = 4;

	/** How long a caller waits for a free scan slot before giving up (fails closed, same as any other scan
	 * failure) rather than queuing indefinitely behind a burst. */
	private static final int ACQUIRE_WAIT_MILLIS = 2000;

	/** The exact clean reply; anything else - including a reply that merely contains "OK" - is not clean. */
	private static final String CLEAN_RESPONSE = "stream: OK";

	/** Caps a reply this client will ever read - a real clamd reply is a short line; anything longer is either a
	 * protocol mismatch or a hostile endpoint, not something to buffer without limit. */
	private static final int MAX_RESPONSE_LENGTH = 1024;

	private final Semaphore slots = new Semaphore(PERMITS);

	private final String host;

	private final int port;

	private final int timeoutMillis;

	ClamAvVirusScanner(@Value("${ergo.clamav.host:localhost}") String host,
			@Value("${ergo.clamav.port:3310}") int port,
			@Value("${ergo.clamav.timeout-ms:10000}") int timeoutMillis) {
		this.host = host;
		this.port = port;
		this.timeoutMillis = timeoutMillis;
	}

	@Override
	public void scan(byte[] content) {
		boolean acquired;
		try {
			acquired = slots.tryAcquire(ACQUIRE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new VirusScanException("Interrupted while waiting for a scan slot", e);
		}
		if (!acquired) {
			throw new VirusScanException("No scan slot became free in time", null);
		}
		try {
			scanNow(content);
		}
		finally {
			slots.release();
		}
	}

	private void scanNow(byte[] content) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
			socket.setSoTimeout(timeoutMillis);
			OutputStream out = socket.getOutputStream();
			out.write(COMMAND);
			for (int offset = 0; offset < content.length; offset += CHUNK_SIZE) {
				int length = Math.min(CHUNK_SIZE, content.length - offset);
				out.write(ByteBuffer.allocate(4).putInt(length).array());
				out.write(content, offset, length);
			}
			out.write(new byte[4]); // zero-length chunk ends the stream
			out.flush();
			String response = readNullTerminated(socket.getInputStream());
			if (response.endsWith(" FOUND")) {
				throw new InfectedFileException(signatureFrom(response));
			}
			if (!response.equals(CLEAN_RESPONSE)) {
				throw new VirusScanException("Unexpected ClamAV response: " + response, null);
			}
		}
		catch (IOException e) {
			throw new VirusScanException("ClamAV scan failed: " + e, e);
		}
	}

	/** {@code "stream: Eicar-Signature FOUND"} -> {@code "Eicar-Signature"}. Falls back to the raw response if
	 * the shape ever changes - this is only for logs, never shown to the uploader. */
	private static String signatureFrom(String response) {
		int colon = response.indexOf(':');
		int found = response.indexOf("FOUND");
		if (colon < 0 || found < 0 || found <= colon) {
			return response.trim();
		}
		return response.substring(colon + 1, found).trim();
	}

	private static String readNullTerminated(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		int b;
		while ((b = in.read()) > 0) {
			if (sb.length() >= MAX_RESPONSE_LENGTH) {
				throw new IOException("ClamAV reply exceeded " + MAX_RESPONSE_LENGTH + " bytes with no terminator");
			}
			sb.append((char) b);
		}
		return sb.toString();
	}

}
