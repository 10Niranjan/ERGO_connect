package com.ergo.connect.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves {@link AzureBlobDocumentStorage} against a real blob service (Azurite, the Azure Storage emulator) rather
 * than mocking the SDK - the thing worth testing here is the wiring (connection string, container auto-creation,
 * blob naming, transaction-synced put/delete), which a mock would just assume away. Not part of the shared
 * {@code @IntegrationTest} context (Phase 8h): this class only runs under the {@code prod} profile in the app
 * itself, so it's exercised directly, without needing a full Spring context or the {@code prod} profile's other
 * requirements (mail, JWT secret, ...). The transaction-sync tests drive {@link TransactionSynchronizationManager}
 * directly (no real transaction manager needed) to simulate a commit or rollback - security review finding M1.
 */
@Testcontainers
class AzureBlobDocumentStorageTest {

	@Container
	static final AzuriteContainer AZURITE = new AzuriteContainer(
			DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:3.35.0"));

	@AfterEach
	void clearAnyLeftoverSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	private AzureBlobDocumentStorage storage() {
		return new AzureBlobDocumentStorage(AZURITE.getConnectionString(), "documents-test");
	}

	private static void assertMissing(AzureBlobDocumentStorage storage, long documentId) {
		assertThatThrownBy(() -> storage.get(documentId)).isInstanceOfSatisfying(BlobStorageException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(BlobErrorCode.BLOB_NOT_FOUND));
	}

	@Test
	void roundTripsContent() {
		AzureBlobDocumentStorage storage = storage();
		byte[] content = "hello document".getBytes(StandardCharsets.UTF_8);

		storage.put(1L, content);

		assertThat(storage.get(1L)).isEqualTo(content);
	}

	@Test
	void deleteIsIdempotent() {
		AzureBlobDocumentStorage storage = storage();
		storage.put(2L, "bye".getBytes(StandardCharsets.UTF_8));

		storage.delete(2L);
		storage.delete(2L);

		assertMissing(storage, 2L);
	}

	@Test
	void getOnMissingDocumentThrows() {
		assertThatThrownBy(() -> storage().get(999L)).isInstanceOfSatisfying(BlobStorageException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(BlobErrorCode.BLOB_NOT_FOUND));
	}

	@Test
	void putTwiceWithSameIdThrows() {
		AzureBlobDocumentStorage storage = storage();
		storage.put(3L, "first".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> storage.put(3L, "second".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOfSatisfying(BlobStorageException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(BlobErrorCode.BLOB_ALREADY_EXISTS));
	}

	@Test
	void aPutInARolledBackTransactionLeavesNoBlob() {
		AzureBlobDocumentStorage storage = storage();

		TransactionSynchronizationManager.initSynchronization();
		storage.put(4L, "orphan".getBytes(StandardCharsets.UTF_8));
		List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
		TransactionSynchronizationManager.clearSynchronization();
		syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

		assertMissing(storage, 4L);
	}

	@Test
	void aPutInACommittedTransactionKeepsTheBlob() {
		AzureBlobDocumentStorage storage = storage();
		byte[] content = "keep me".getBytes(StandardCharsets.UTF_8);

		TransactionSynchronizationManager.initSynchronization();
		storage.put(5L, content);
		List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
		TransactionSynchronizationManager.clearSynchronization();
		syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

		assertThat(storage.get(5L)).isEqualTo(content);
	}

	@Test
	void aDeleteInARolledBackTransactionKeepsTheBlob() {
		AzureBlobDocumentStorage storage = storage();
		byte[] content = "still here".getBytes(StandardCharsets.UTF_8);
		storage.put(6L, content);

		TransactionSynchronizationManager.initSynchronization();
		storage.delete(6L);
		TransactionSynchronizationManager.clearSynchronization(); // rollback: afterCommit never fires

		assertThat(storage.get(6L)).isEqualTo(content);
	}

	@Test
	void aDeleteInACommittedTransactionRemovesTheBlob() {
		AzureBlobDocumentStorage storage = storage();
		storage.put(7L, "bye".getBytes(StandardCharsets.UTF_8));

		TransactionSynchronizationManager.initSynchronization();
		storage.delete(7L);
		List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
		TransactionSynchronizationManager.clearSynchronization();
		syncs.forEach(TransactionSynchronization::afterCommit);

		assertMissing(storage, 7L);
	}

}
