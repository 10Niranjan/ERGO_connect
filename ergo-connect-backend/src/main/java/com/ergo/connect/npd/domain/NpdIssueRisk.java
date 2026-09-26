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
 * One issue or risk raised against an NPD project or one of its activities (SRS §13.4.6). Raising one is ERGO-only
 * ({@code RAISE_ISSUE}); the owning company may only change {@code status} (and the implied {@code closureDate}),
 * never the classification fields, via {@code UPDATE_ISSUE}. {@code ownerOrganizationType}/{@code ownerCompanyId}
 * are beyond §13.4.6's literal field list (ARCHITECTURE.md §8 2026-09-26): they give a company-scope basis for the
 * two-way anonymity rule even when {@code linkedActivityId} is null. {@code ownerUserId} is unused by 3b: the SRS
 * names no specific-user assignment beyond company scope. Field changes are audited automatically.
 */
@Entity
@Table(name = "npd_issues_risks")
class NpdIssueRisk {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "npd_issue_risk_id")
	Long id;

	@Column(updatable = false)
	Long npdProjectId;

	@Column(updatable = false)
	Long linkedActivityId;

	@Column(updatable = false)
	@Enumerated(EnumType.STRING)
	IssueRiskType type;

	@Column(updatable = false)
	String title;

	@Column(updatable = false)
	String description;

	@Column(updatable = false)
	@Enumerated(EnumType.STRING)
	RiskLevel severity;

	/** Server-derived, never client-supplied beyond the project's own customer/supplier (or ERGO's own company). */
	@Column(updatable = false)
	@Enumerated(EnumType.STRING)
	CompanyType ownerOrganizationType;

	@Column(updatable = false)
	Long ownerCompanyId;

	Long ownerUserId;

	@Column(updatable = false)
	LocalDate targetDate;

	@Enumerated(EnumType.STRING)
	IssueRiskStatus status = IssueRiskStatus.OPEN;

	LocalDate closureDate;

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
