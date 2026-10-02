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

}
