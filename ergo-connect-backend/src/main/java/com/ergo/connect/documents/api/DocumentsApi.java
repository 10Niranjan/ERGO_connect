package com.ergo.connect.documents.api;

/**
 * What other modules may ask of the documents module. Used by the communication module to check, before a
 * notification about a document is delivered, that the recipient can still open that document (ARCHITECTURE.md §8,
 * 2026-10-03 Phase 6e).
 */
public interface DocumentsApi {

	/** True if the current caller may open this document (its linked record is in scope and its own visibility
	 * allows them). Non-throwing; false if the document doesn't exist. */
	boolean documentVisibleToCaller(long documentId);

}
