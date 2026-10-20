package com.ergo.connect.communication.api;

/**
 * What other modules may ask of the communication module. Used by the documents module for comment attachments
 * ({@code linked_module = "comment"} documents, keyed by comment id) - ARCHITECTURE.md §8, 2026-10-02 Phase 6c.
 */
public interface CommunicationApi {

	/** True if the current caller may read this comment (its record is in the caller's scope and the comment's
	 * audience includes the caller). Non-throwing; false if the comment doesn't exist. */
	boolean commentVisibleToCaller(long commentId);

	/** True if the current caller may currently add to or replace this comment's attachments: they wrote the
	 * comment and it can still be edited (no reply yet, query not resolved). Non-throwing. */
	boolean attachmentWindowOpenForCaller(long commentId);

	/**
	 * The workflow module that owns the record this comment hangs off, if the current caller may read the comment.
	 * Lets the documents module apply that module's switch to the comment's attachments (ARCHITECTURE.md §8, module
	 * switches). Empty if the comment does not exist for the caller.
	 */
	java.util.Optional<com.ergo.connect.common.security.ModuleAccess.Module> recordModuleOfComment(long commentId);

}
