package com.ergo.connect.documents.domain;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Where a document's bytes live, kept separate from its metadata ({@code documents} table, owned by {@code
 * DocumentStore}/{@code Document}) so the byte store can be swapped later without touching either (client decision,
 * ARCHITECTURE.md §8 2026-10-02: Azure Blob Storage + virus scanning is deferred to Phase 8 hardening; this interface
 * keeps that swap cheap). {@link PostgresDocumentStorage} reproduces today's {@code document_contents} behavior
 * exactly (same table, no size check here - the 50MB cap is enforced upstream by Spring's multipart config,
 * {@code application.yml}, before bytes ever reach this layer). {@link AzureBlobDocumentStorage} is the real thing
 * (Phase 8h); it only runs under the {@code prod} profile, same convention as {@code RequireMailInProd} and
 * {@code SecurityProperties.jwtSecret} - everywhere else (dev, test, CI's Testcontainers suite) keeps using Postgres
 * so no real Azure credentials are ever needed to run {@code ./mvnw verify}.
 */
public interface DocumentStorage {

	void put(long documentId, byte[] content);

	byte[] get(long documentId);

	void delete(long documentId);

}

@Component
@Profile("!prod")
class PostgresDocumentStorage implements DocumentStorage {

	private final JdbcTemplate jdbc;

	PostgresDocumentStorage(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void put(long documentId, byte[] content) {
		jdbc.update("INSERT INTO document_contents (document_id, content) VALUES (?, ?)", documentId, content);
	}

	@Override
	public byte[] get(long documentId) {
		return jdbc.queryForObject("SELECT content FROM document_contents WHERE document_id = ?", byte[].class,
				documentId);
	}

	@Override
	public void delete(long documentId) {
		jdbc.update("DELETE FROM document_contents WHERE document_id = ?", documentId);
	}

}
