package com.ergo.connect.communication.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.ergo.connect.communication.api.CommentType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** SRS §12.12.1 Communication Center DTOs. One shape for every audience: a comment the caller must not read is never
 * built (dropped before the DTO exists), and the only per-audience difference is the people labels, which are
 * resolved server-side - ERGO staff appear as "ERGO" to an external caller, and the other external party never
 * appears at all (customers and suppliers never share a thread). */
public final class CommentDtos {

	private CommentDtos() {
	}

	/** Buttons the backend allows for this caller and comment right now; the UI renders only these. */
	public enum CommentAction {
		REPLY, EDIT, ATTACH, RESOLVE, REOPEN, ESCALATE, DEESCALATE
	}

	/** {@code commentType} is optional: an external caller always posts to its own side, an ERGO caller defaults to
	 * INTERNAL (the narrowest audience). A reply always takes its parent's type. */
	public record CommentForm(@NotBlank String linkedModule, @NotNull Long linkedRecordId, CommentType commentType,
			@NotBlank @Size(max = 4000) String text, Long parentCommentId, boolean query,
			@Size(max = 20) List<Long> mentionedUserIds) {
	}

	/** Full replace of the editable parts (text, query flag, mentions). */
	public record CommentEditForm(@NotBlank @Size(max = 4000) String text, boolean query,
			@Size(max = 20) List<Long> mentionedUserIds) {
	}

	/** Reused by resolve / escalate (optional) and reopen / de-escalate (mandatory); the service decides which. */
	public record ReasonForm(@Size(max = 2000) String reason) {
	}

	/** {@code userId} is null when an external viewer is looking at an ERGO user (internal ids are never disclosed). */
	public record MentionView(Long userId, String name) {
	}

	public record MentionOption(long userId, String name) {
	}

	public record AttachmentView(long id, String fileName, long size, boolean uploadedByMe) {
	}

	public record CommentView(long id, String linkedModule, long linkedRecordId, CommentType commentType,
			Long parentCommentId, String text, String author, boolean mine, Instant createdAt, Instant updatedAt,
			boolean query, boolean resolved, Instant resolvedAt, String resolvedBy, boolean escalated,
			Instant escalatedAt, String escalatedBy, List<MentionView> mentions, List<AttachmentView> attachments,
			List<CommentView> replies, Set<CommentAction> actions) {
	}

	public record InboxPage(List<CommentView> items, int page, int size, long totalElements) {
	}

}
