package com.ergo.connect.masterdata.access;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import com.ergo.connect.masterdata.access.CompanyAssessmentDtos.AssessmentKind;
import com.ergo.connect.masterdata.access.CompanyAssessmentDtos.AuditType;
import com.ergo.connect.masterdata.access.CompanyAssessmentDtos.Recommendation;
import com.ergo.connect.masterdata.access.CompanyAssessmentDtos.RiskRating;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A supplier audit report or a customer assessment (client questions 28 and 29). ERGO-internal history: never
 * changed or deleted (V47 trigger), so a correction is a new row.
 */
@Entity
@Immutable
@Table(name = "company_assessments")
class CompanyAssessment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "assessment_id")
	Long id;

	Long companyId;

	@Enumerated(EnumType.STRING)
	AssessmentKind kind;

	LocalDate assessmentDate;

	@Enumerated(EnumType.STRING)
	AuditType auditType;

	String auditor;

	String findings;

	@Enumerated(EnumType.STRING)
	Recommendation recommendation;

	@Enumerated(EnumType.STRING)
	RiskRating riskRating;

	Long documentId;

	Long createdByUserId;

	/** Set on insert by AuditEntityListener: the Super Admin who acted as this user, else null. */
	Long actingAdminUserId;

	@CreationTimestamp
	Instant createdAt;

}
