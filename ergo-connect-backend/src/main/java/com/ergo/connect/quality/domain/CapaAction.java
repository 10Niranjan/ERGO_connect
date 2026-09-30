package com.ergo.connect.quality.domain;

import java.time.Instant;
import java.time.LocalDate;

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

/** A CAPA action under an {@link Ncr} (SRS §13.6.5). Not append-only (V18 migration comment): an owner edits it in
 * place as it progresses, same as its parent NCR - so, unlike {@link InspectionDefect}, it carries its own {@code
 * status}/{@code closedAt}/{@code evidenceDocumentId} lifecycle rather than being wholesale-replaced on every parent
 * edit. Carries its own {@code rowVersion}/{@code updatedAt} (security review L2), same shape as {@link Ncr}. */
@Entity
@Table(name = "capa_actions")
class CapaAction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "capa_action_id")
	Long id;

	@Column(updatable = false)
	Long ncrId;

	/** Security review L1: {@code editCapaAction} is a legitimate full-replace edit (same as {@link Ncr}'s own
	 * fields) - no {@code updatable = false} here, unlike {@code linkedModule}/{@code linkedRecordId} above which
	 * really are set-once. */
	@Enumerated(EnumType.STRING)
	CapaActionType actionType;

	String actionDescription;

	/** Who executes this specific action - unlike {@link Ncr#ownerUserId}, settable by either ERGO or the NCR's own
	 * supplier editor (the person doing the corrective/preventive work is very plausibly on the supplier's side),
	 * but still dropped from both external views for the same staff-identity privacy reason (see {@code
	 * NcrService}'s class comment) - a judgment call flagged there, not a contradiction. */
	Long ownerUserId;

	LocalDate targetDate;

	@Enumerated(EnumType.STRING)
	CapaActionStatus status;

	/** Real FK into {@code documents} (V4/V18) - set only through {@code NcrService.uploadEvidence}, never a plain
	 * form field, same pattern as {@code Shipment.podDocumentId}. Gates NCR closure (SRS §17.6 hard rule). */
	Long evidenceDocumentId;

	Instant closedAt;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

	@UpdateTimestamp
	@AuditExclude
	Instant updatedAt;

	/** Optimistic lock (security review L2): of two concurrent changes the later one fails (409) instead of
	 * overwriting the other, same as {@link Ncr#rowVersion}. */
	@Version
	@AuditExclude
	long rowVersion;

}
