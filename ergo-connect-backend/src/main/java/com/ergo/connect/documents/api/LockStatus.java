package com.ergo.connect.documents.api;

/** SRS §13.7.1 / §14 (Phase 6a versioning decision, ARCHITECTURE.md §8 2026-10-02): independent of {@link
 * ApprovalStatus} - approving a document also locks it, but a manual lock/unlock exists separately for ERGO Admin to
 * freeze a document outside the approval flow. */
public enum LockStatus {
	UNLOCKED, LOCKED
}
