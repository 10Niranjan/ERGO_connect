package com.ergo.connect.documents.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** SRS §13.2.13 document categories (V20 migration), a fixed seeded list read-only in Phase 6a - same shape as
 * materials/processes/coatings (V3), no CRUD endpoint of its own yet (ARCHITECTURE.md §8 2026-10-02). */
@Entity
@Table(name = "document_categories")
class DocumentCategory {

	@Id
	@Column(name = "category_id")
	Long id;

	String name;

	boolean activeFlag;

	Instant createdAt;

}
