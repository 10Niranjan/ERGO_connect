package com.ergo.connect.npd.domain;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;
import com.ergo.connect.common.security.CompanyType;

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
 * One item of an NPD project's activity board (SRS §13.4.3). 3a is ERGO-only: owner_organization_type is always
 * ERGO and only ERGO roles create or edit an activity here; 3b adds customer/supplier-owned activities. Field
 * changes are audited automatically.
 */
@Entity
@Table(name = "npd_activities")
class NpdActivity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "npd_activity_id")
	Long id;

	@Column(updatable = false)
	Long npdProjectId;

	/** One of {@link NpdStage}'s values: the project's stage when this activity was created. Not changed by edits. */
	@Column(updatable = false)
	@Enumerated(EnumType.STRING)
	NpdStage phaseName;

	String activityName;

	String activityDescription;

	/** Always ERGO in 3a; server-derived, never client-supplied. */
	@Enumerated(EnumType.STRING)
	CompanyType ownerOrganizationType;

	/** The ERGO company of whoever created the activity; server-derived. */
	Long ownerCompanyId;

	Long responsibleUserId;

	LocalDate plannedDate;

	LocalDate baselineDate;

	LocalDate actualDate;

	Long dependencyActivityId;

	@Enumerated(EnumType.STRING)
	ActivityStatus status = ActivityStatus.NOT_STARTED;

	/** Set only by markBlocked/unblock (a reason is required to set it true), never by the general edit form. */
	boolean blockedFlag;

	boolean riskFlag;

	boolean issueFlag;

	/** Gates Close Stage: every mandatory activity of the current stage must be DONE. Defaults true (V8). */
	boolean mandatoryFlag = true;

	/** Unused by 3a; wired up in 3b. */
	boolean approvalRequiredFlag;

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
