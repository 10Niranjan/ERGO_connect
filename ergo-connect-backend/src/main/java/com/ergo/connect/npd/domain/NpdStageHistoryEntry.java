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
 * A permanent record of every stage move (SRS §13.4.2), separate from the generic audit_logs table. Append-only at
 * the DB level (V8 reuses V5's forbid_update_delete trigger); this module only ever inserts a row, from
 * NpdService's workflow actions (conversion and Close Stage).
 */
@Entity
@Table(name = "npd_stage_history")
class NpdStageHistoryEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "npd_stage_history_id")
	Long id;

	Long npdProjectId;

	@Enumerated(EnumType.STRING)
	NpdStage stageName;

	/** Null for the very first row (the implicit Pending Conversion -> Planning transition at conversion). */
	@Enumerated(EnumType.STRING)
	NpdStage previousStageName;

	Long changedByUserId;

	/** Set on insert by AuditEntityListener: the Super Admin who acted as this user, else null. */
	Long actingAdminUserId;

	@CreationTimestamp
	Instant changedAt;

	String remarks;

}
