package com.ergo.connect.npd.domain;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One template question of an activity's Prompt Checklist (SRS §13.4.4). Authored only by ERGO Admin/NPD
 * ({@code MANAGE_PROMPT_CHECKLIST}); every audience may answer it via {@link NpdPromptResponse}. Field changes are
 * audited automatically.
 */
@Entity
@Table(name = "npd_prompt_checklists")
class NpdPromptChecklist {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "npd_prompt_checklist_id")
	Long id;

	@Column(updatable = false)
	Long npdActivityId;

	String questionText;

	@Enumerated(EnumType.STRING)
	PromptResponseType responseType;

	boolean mandatoryFlag = true;

	int sequenceNo;

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
