package com.ergo.connect.masterdata.access;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

/** Supplier audit reports and customer assessments (client questions 28 and 29). ERGO only, never sent outside. */
public final class CompanyAssessmentDtos {

	private CompanyAssessmentDtos() {
	}

	public enum AssessmentKind {
		SUPPLIER_AUDIT, CUSTOMER_ASSESSMENT
	}

	public enum AuditType {
		ON_SITE, VIRTUAL, DESK_REVIEW
	}

	public enum Recommendation {
		APPROVE, REJECT
	}

	public enum RiskRating {
		LOW, MEDIUM, HIGH
	}

	/**
	 * One form for both kinds; the company's type decides which fields are required. Supplier audit: audit type,
	 * auditor, findings, recommendation and the report file. Customer assessment: notes (findings) and risk rating,
	 * file optional.
	 */
	public record AssessmentRequest(@NotNull @PastOrPresent LocalDate assessmentDate, AuditType auditType,
			@Size(max = 200) String auditor, @NotBlank @Size(max = 4000) String findings, Recommendation recommendation,
			RiskRating riskRating) {
	}

	public record AssessmentFile(long id, String fileName, long size) {
	}

	public record AssessmentView(long id, AssessmentKind kind, LocalDate assessmentDate, AuditType auditType,
			String auditor, String findings, Recommendation recommendation, RiskRating riskRating, AssessmentFile file,
			String createdBy, Instant createdAt) {
	}

	/** The company's assessments, newest first, and whether the caller may add one. */
	public record AssessmentList(AssessmentKind kind, boolean canAdd, java.util.List<AssessmentView> items) {
	}

}
