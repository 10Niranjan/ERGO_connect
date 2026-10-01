package com.ergo.connect.quality.domain;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import com.ergo.connect.common.security.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One required approval gate on a {@link Deviation} (V19 migration): always one {@code ERGO_QUALITY} row, plus one
 * {@code CUSTOMER_QUALITY} row when {@code customerApprovalRequiredFlag} is set. {@code approverRole} reuses the
 * existing {@link Role} enum (same call as {@code NpdApproval.approverRole}) rather than inventing a parallel type -
 * {@code ERGO_QUALITY}/{@code CUSTOMER_QUALITY} already exist there and the V19 CHECK constraint names exactly those
 * two. Unlike {@code NpdApproval} (a fresh row per request), this row is reused across review cycles: {@code
 * DeviationService} resets it back to PENDING whenever the deviation re-enters the status this row decides for, so
 * at most one row per (deviation, approverRole) ever exists. Package-private: only this package touches it.
 */
@Entity
@Table(name = "deviation_approvals")
class DeviationApproval {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "deviation_approval_id")
	Long id;

	@Column(updatable = false)
	Long deviationId;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	Role approverRole;

	Long approverUserId;

	@Enumerated(EnumType.STRING)
	DeviationDecision decision = DeviationDecision.PENDING;

	String comment;

	Instant decidedAt;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

}
