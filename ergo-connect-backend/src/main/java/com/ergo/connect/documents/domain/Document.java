package com.ergo.connect.documents.domain;

import java.time.Instant;

import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;
import com.ergo.connect.documents.api.ApprovalStatus;
import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.documents.api.LockStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * JPA view of the {@code documents} table (Phase 2a {@link com.ergo.connect.documents.api.DocumentStore}, extended by
 * V20 for Phase 6a: category, version chain, lock/approval). Rows are only ever INSERTed through {@code
 * DocumentStore} (raw JDBC, unchanged for the 4 existing callers - ARCHITECTURE.md §8 2026-10-02 "Phase 6a is an
 * extension"); this entity is used to read, and to update the Phase 6a columns (category/visibility/version
 * chain/lock/approval), so those changes get the automatic per-field audit every other JPA entity in this codebase
 * gets. Package-private: only {@code documents.domain} touches it.
 */
@Entity
@Table(name = "documents")
class Document {

	@Id
	@Column(name = "document_id")
	Long id;

	/** Free string, not normalized to an enum/FK (client decision, ARCHITECTURE.md §8 2026-10-02 "minor defaults") -
	 * observed values: {@code rfq}, {@code quotation}, {@code OFP_SHIPMENT}, {@code QUALITY_CAPA}. */
	@Column(updatable = false)
	String linkedModule;

	@Column(updatable = false)
	Long linkedRecordId;

	@Column(updatable = false)
	String fileName;

	@Column(updatable = false)
	String contentType;

	@Column(updatable = false)
	Long fileSize;

	// sha256 (CHAR(64)/bpchar in the DB) is deliberately not mapped here: nothing in this module reads it, and
	// Hibernate's schema validation treats a plain String field as VARCHAR, which fails validation against a CHAR
	// column - DocumentStore (raw JDBC) remains the place that reads/writes it.

	@Enumerated(EnumType.STRING)
	Visibility visibility;

	@Column(updatable = false)
	Long uploadedByUserId;

	@Column(updatable = false)
	Long uploadedByCompanyId;

	@Column(updatable = false)
	Instant createdAt;

	@UpdateTimestamp
	@AuditExclude
	Instant updatedAt;

	Long documentCategoryId;

	Integer versionNo;

	Long supersedesDocumentId;

	@Enumerated(EnumType.STRING)
	LockStatus lockStatus;

	@Enumerated(EnumType.STRING)
	ApprovalStatus approvalStatus;

	/** Optimistic lock (security review H5): of two concurrent changes the later one fails (409) instead of silently
	 * overwriting the other with no audit trace of the reversion - same shape as every other versioned entity in
	 * this codebase ({@code Order}, {@code CapaAction}, {@code Ncr}, {@code Rfq}). */
	@Version
	@AuditExclude
	long rowVersion;

}
