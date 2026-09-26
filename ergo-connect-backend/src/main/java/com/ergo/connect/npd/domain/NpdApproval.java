package com.ergo.connect.npd.domain;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;
import com.ergo.connect.common.security.Role;

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
 * A stage-gate/quality-gate/customer approval request and its decision (SRS §13.4.7). {@code approverRole} is
 * computed server-side from {@code approvalType} ({@link ApprovalType#approverRole()}) and never accepted from the
 * client (ARCHITECTURE.md §8 2026-09-26): letting the requester choose it would let a customer-facing approval be
 * routed to itself, bypassing SRS §10.6. {@code stageName} is the project's own stage at request time. Field
 * changes are audited automatically.
 */
@Entity
@Table(name = "npd_approvals")
class NpdApproval {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "npd_approval_id")
	Long id;

	@Column(updatable = false)
	Long npdProjectId;

	@Column(updatable = false)
	Long linkedActivityId;

	@Column(updatable = false)
	@Enumerated(EnumType.STRING)
	NpdStage stageName;

	@Column(updatable = false)
	@Enumerated(EnumType.STRING)
	ApprovalType approvalType;

	@Column(updatable = false)
	Long requestedByUserId;

	@CreationTimestamp
	@Column(updatable = false)
	Instant requestedAt;

	@Column(updatable = false)
	@Enumerated(EnumType.STRING)
	Role approverRole;

	Long approvedByUserId;

	@Enumerated(EnumType.STRING)
	ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

	String approvalComments;

	Instant approvedAt;

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
