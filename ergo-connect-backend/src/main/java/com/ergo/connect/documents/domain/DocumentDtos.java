package com.ergo.connect.documents.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.ergo.connect.documents.api.ApprovalStatus;
import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.documents.api.LockStatus;

import jakarta.validation.constraints.Size;

/** SRS §12.11 Document Library/Detail DTOs. One shape per screen, not per audience (CLAUDE.md's "audience-specific
 * DTOs" rule exists to keep forbidden fields - target price, margins, internal notes/scores, competitor quotes - off
 * external DTOs entirely; this module carries none of those, so the only per-audience differences are (a) rows the
 * caller must not see at all, dropped before the DTO is ever built, never nulled, and (b) {@code uploadedBy}, which
 * is resolved to a caller-safe label server-side (never a raw company id/name of the *other* external party - SRS's
 * two-way anonymity rule, same as RFQ/NPD/OFP) rather than shaped into a second record type with no other
 * difference. */
public final class DocumentDtos {

	private DocumentDtos() {
	}

	/** SRS §10.9: edit covers category/visibility changes; replace is edit plus "no later version exists yet" (a
	 * document can only ever be superseded from its current latest version, keeping the chain linear). Create/submit
	 * have no distinct meaning here (client decision, ARCHITECTURE.md §8 2026-10-02) and create stays in the 4
	 * owning modules' own upload endpoints - not represented as a button here. */
	public enum DocumentAction {
		EDIT, REPLACE, LOCK, UNLOCK, APPROVE, REJECT
	}

	public record CategoryOption(long id, String name) {
	}

	public record VersionSummary(long id, int versionNo, String fileName, Instant createdAt, boolean current) {
	}

	public record SecondaryLink(String linkedModule, long linkedRecordId) {
	}

	public record LibraryRow(long id, String fileName, String contentType, long fileSize, Long categoryId,
			String category, Visibility visibility, String uploadedBy, Instant createdAt, Instant updatedAt,
			int versionNo, boolean hasLaterVersion, LockStatus lockStatus, ApprovalStatus approvalStatus,
			String linkedModule, long linkedRecordId, Set<DocumentAction> actions) {
	}

	public record LibraryPage(List<LibraryRow> items, int page, int size, long totalElements) {
	}

	public record DocumentDetail(long id, String fileName, String contentType, long fileSize, Long categoryId,
			String category, Visibility visibility, String uploadedBy, Instant createdAt, Instant updatedAt,
			int versionNo, Long supersedesDocumentId, List<VersionSummary> versionHistory, LockStatus lockStatus,
			ApprovalStatus approvalStatus, String linkedModule, long linkedRecordId,
			List<SecondaryLink> secondaryLinks, Set<DocumentAction> actions) {
	}

	/** Both nullable: PATCH only changes the field actually sent (client decision - partial update, not a full
	 * replace of the row). */
	public record DocumentEditForm(Long documentCategoryId, Visibility visibility) {
	}

	/** Reused for approve (optional comment) and reject (mandatory reason) - same shape as {@code
	 * quality.domain.QualityDtos.ReasonForm}; the service decides which requires it. */
	public record ReasonForm(@Size(max = 2000) String reason) {
	}

}
