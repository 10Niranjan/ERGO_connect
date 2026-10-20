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

	/**
	 * The workflow module that owns the record this document hangs off (RFQ, Quotation, OFP, Quality; the comment's record's
	 * module for a comment attachment), or empty if the document does not exist or belongs to no switchable module. No
	 * scope check: used to file a notification under the module whose switch governs it (ARCHITECTURE.md §8, module
	 * switches).
	 */
	java.util.Optional<com.ergo.connect.common.security.ModuleAccess.Module> owningModule(long documentId);

}
