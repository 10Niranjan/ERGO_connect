package com.ergo.connect.documents.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** SRS §13.7.2 secondary document links (V20 migration): a document can be relevant to more than one record (e.g. an
 * RFQ drawing reused by the NPD project it became) without changing its primary {@code linkedModule}/{@code
 * linkedRecordId} on {@link Document}. Read-only in Phase 6a (no endpoint creates these yet; the column exists for
 * the Detail screen to render alongside the primary link). */
@Entity
@Table(name = "document_links")
class DocumentLink {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "document_link_id")
	Long id;

	@Column(updatable = false)
	Long documentId;

	@Column(updatable = false)
	String linkedModule;

	@Column(updatable = false)
	Long linkedRecordId;

	@Column(updatable = false)
	Instant createdAt;

}
