package com.ergo.connect.documents.domain;

import java.io.ByteArrayInputStream;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.BlobServiceVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Real Azure Blob Storage behind {@link DocumentStorage} (Phase 8h, ARCHITECTURE.md §8 2026-10-02): the interface
 * existed since Phase 6a specifically so this swap would never touch {@code DocumentStore}/{@code DocumentService}
 * or their callers. One blob per document, named by its id. The connection string is a secret (Key Vault in
 * deployment, per {@code application-prod.yml}); the builder throws immediately on a blank one, so a misconfigured
 * prod deployment fails at startup rather than silently losing uploads - same "refuse to start" philosophy as
 * {@code RequireMailInProd}.
 * <p>
 * Security review finding M1: unlike {@link PostgresDocumentStorage}, a blob write/delete does not commit or roll
 * back atomically with the caller's {@code documents} row and audit row (Azure isn't in the DB transaction). Both
 * {@link #put} and {@link #delete} hook the current transaction - when one is active, DB-outer-caller-driven - to
 * keep the two in sync: a put survives only if the transaction commits; a delete only takes effect once it has.
 * {@link DocumentStore} always calls both from inside a {@code @Transactional} method, so this is the normal path,
 * not a fallback; falling back to an immediate action with no transaction is a safety net, not an expected case.
 */
@Component
@Profile("prod")
class AzureBlobDocumentStorage implements DocumentStorage {

	private static final Logger log = LoggerFactory.getLogger(AzureBlobDocumentStorage.class);

	private final BlobContainerClient container;

	AzureBlobDocumentStorage(@Value("${ergo.storage.azure.connection-string}") String connectionString,
			@Value("${ergo.storage.azure.container:documents}") String containerName) {
		// Pinned, not "whatever's latest": the SDK defaults to the newest service API version on every upgrade,
		// which would otherwise change what this app sends to Azure with no code change of its own. Also what
		// AzureBlobDocumentStorageTest's Azurite emulator image supports.
		BlobServiceClient service = new BlobServiceClientBuilder().connectionString(connectionString)
				.serviceVersion(BlobServiceVersion.V2025_11_05).buildClient();
		this.container = service.getBlobContainerClient(containerName);
		container.createIfNotExists();
	}

	@Override
	public void put(long documentId, byte[] content) {
		BlobClient blob = container.getBlobClient(blobName(documentId));
		blob.upload(new ByteArrayInputStream(content), content.length, false);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					// STATUS_UNKNOWN is left alone: the row may have committed anyway, and an orphaned blob is far
					// cheaper than silently losing content a caller believes was saved.
					if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
						deleteQuietly(blob, documentId);
					}
				}
			});
		}
	}

	@Override
	public byte[] get(long documentId) {
		return container.getBlobClient(blobName(documentId)).downloadContent().toBytes();
	}

	@Override
	public void delete(long documentId) {
		BlobClient blob = container.getBlobClient(blobName(documentId));
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					deleteQuietly(blob, documentId);
				}
			});
		}
		else {
			blob.deleteIfExists();
		}
	}

	private static void deleteQuietly(BlobClient blob, long documentId) {
		try {
			blob.deleteIfExists();
		}
		catch (RuntimeException e) {
			log.error("Failed to delete blob for document {} - it is now an orphan, clean up manually: {}", documentId,
					e.toString());
		}
	}

	private static String blobName(long documentId) {
		return "doc-" + documentId;
	}

}
