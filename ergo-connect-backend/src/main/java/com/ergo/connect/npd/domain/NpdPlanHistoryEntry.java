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
 * A permanent record of every step of the plan's negotiation (flow doc step 8): released, submitted, sent back with
 * ERGO NPD's comment, agreed. Append-only at the DB level (V36); NpdService only ever inserts a row.
 */
@Entity
@Table(name = "npd_plan_history")
class NpdPlanHistoryEntry {

	enum Event {
		RELEASED, SUBMITTED, SENT_BACK, AGREED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "npd_plan_history_id")
	Long id;

	Long npdProjectId;

	@Enumerated(EnumType.STRING)
	Event event;

	/** For SENT_BACK it is ERGO NPD's reason for the supplier; otherwise an optional note. */
	String comment;

	Long byUserId;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

}
