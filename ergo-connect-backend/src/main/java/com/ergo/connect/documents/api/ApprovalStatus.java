package com.ergo.connect.documents.api;

/** SRS §13.7.1 / §10.9 (Phase 6a, ARCHITECTURE.md §8 2026-10-02): ERGO Admin only. Approving also locks the document
 * ({@link LockStatus#LOCKED}); rejecting does not. Unlocking an {@code APPROVED} document resets it to {@code
 * PENDING} - there is no "locked but still shows approved" state. */
public enum ApprovalStatus {
	PENDING, APPROVED, REJECTED
}
