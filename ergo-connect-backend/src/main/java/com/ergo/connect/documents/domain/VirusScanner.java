package com.ergo.connect.documents.domain;

/**
 * Scans a document's bytes before they are persisted (Phase 8i, ARCHITECTURE.md §6/§8: "mandatory given
 * suppliers/customers upload drawings and documents directly"). The only caller is {@code DocumentStore.store()} -
 * the single choke point every upload path (RFQ, quotation, OFP POD, NCR/CAPA evidence, comment attachments,
 * the Documents Library) already goes through, so gating there covers all of them.
 * <p>
 * Deliberately throw-only, no "clean/dirty" boolean return: a scan that didn't run (scanner unreachable, timed
 * out, bad protocol response) must never be mistaken for "came back clean." {@link ClamAvVirusScanner} is the
 * real implementation (every profile except {@code test} - see its own javadoc for why); {@link
 * EicarOnlyVirusScanner} is a test fixture, not a demo of weaker scanning: it only ever runs under the {@code
 * test} profile and only recognizes the industry-standard EICAR test string, so it can prove {@code
 * DocumentStore}'s rejection wiring (no row written, no blob persisted, the right HTTP status) in the shared
 * {@code @IntegrationTest} context without paying for a live ClamAV engine on every one of ~400 test methods -
 * the real engine's own detection is proven separately, against a real container, by {@code ClamAvVirusScannerTest}.
 */
public interface VirusScanner {

	/** @throws InfectedFileException if malware is found; any other failure (unreachable, timeout, bad response)
	 * throws {@link VirusScanException} - never silently treated as clean. */
	void scan(byte[] content);

}
