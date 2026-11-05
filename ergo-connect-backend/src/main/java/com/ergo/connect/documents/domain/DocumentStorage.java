package com.ergo.connect.documents.domain;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Where a document's bytes live, kept separate from its metadata ({@code documents} table, owned by {@code
 * DocumentStore}/{@code Document}) so the byte store can be swapped later without touching either (client decision,
 * ARCHITECTURE.md §8 2026-10-02: Azure Blob Storage + virus scanning is deferred to Phase 8 hardening; this interface
 * keeps that swap cheap). {@link PostgresDocumentStorage} reproduces today's {@code document_contents} behavior
 * exactly (same table, no size check here - the 50MB cap is enforced upstream by Spring's multipart config,
 * {@code application.yml}, before bytes ever reach this layer). {@link AzureBlobDocumentStorage} is the Azure one
 * (Phase 8h). {@code ergo.storage.type} picks exactly one: {@code postgres} (default; dev, test and the Oracle
 * production VM, ARCHITECTURE.md §8 2026-11-05) or {@code azure} (the prod profile's default, for the infra/ Bicep
 * path). Any other value leaves no bean and startup fails closed.
 */
public interface DocumentStorage {

	void put(long documentId, byte[] content);

	byte[] get(long documentId);

	void delete(long documentId);

}

@Component
@ConditionalOnProperty(name = "ergo.storage.type", havingValue = "postgres")
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
