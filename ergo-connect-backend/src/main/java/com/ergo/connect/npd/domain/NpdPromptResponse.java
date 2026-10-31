package com.ergo.connect.npd.domain;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The one current answer to a {@link NpdPromptChecklist} question (SRS §13.4.5): re-answering updates this row
 * rather than inserting another (V9's own UNIQUE constraint on {@code checklist_id}), since the schema models one
 * current answer, not a history. Field changes are audited automatically.
 */
@Entity
@Table(name = "npd_prompt_responses")
class NpdPromptResponse {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "npd_prompt_response_id")
	Long id;

	@Column(updatable = false, unique = true)
	Long checklistId;

	String responseValue;

	String remarks;

	Long respondedByUserId;

	/** Set on insert by AuditEntityListener: the Super Admin who acted as this user, else null. */
	Long actingAdminUserId;

	Instant respondedAt;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

	@UpdateTimestamp
	@AuditExclude
	Instant updatedAt;

	/** Optimistic lock: of two concurrent changes the later one fails (409) instead of overwriting the other. */
	@Version
	@AuditExclude
	long rowVersion;

}
