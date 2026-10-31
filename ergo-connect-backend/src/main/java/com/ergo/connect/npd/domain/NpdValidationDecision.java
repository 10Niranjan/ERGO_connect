package com.ergo.connect.npd.domain;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One approval or rejection in the quality validation / customer final approval loop (flow doc steps 9-10): who decided,
 * in which round, and why. Append-only at the DB level (V37); NpdValidationService only ever inserts.
 */
@Entity
@Table(name = "npd_validation_decisions")
class NpdValidationDecision {

	enum Decision {
		APPROVED, REJECTED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "npd_validation_decision_id")
	Long id;

	Long npdProjectId;

	int roundNo;

	@Enumerated(EnumType.STRING)
	ValidationParty party;

	@Enumerated(EnumType.STRING)
	Decision decision;

	String comment;

	/** Where a rejection sent the project back to; null for an approval. */
	@Enumerated(EnumType.STRING)
	NpdStage returnStage;

	Long decidedByUserId;

	/** Set on insert by AuditEntityListener: the Super Admin who acted as this user, else null. */
	Long actingAdminUserId;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

}
