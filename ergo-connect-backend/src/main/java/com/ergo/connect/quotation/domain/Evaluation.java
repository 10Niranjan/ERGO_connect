package com.ergo.connect.quotation.domain;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * ERGO's scores (0-10, higher is better; risk 10 = lowest risk) and remarks for one supplier quote (SRS §13.3.8
 * quote_comparison_lines). ERGO-internal; audited field by field.
 */
@Entity
@Table(name = "quote_comparison_lines")
class Evaluation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "quote_comparison_line_id")
	Long id;

	@Column(updatable = false)
	Long assignmentId;

	Integer commercialScore;

	Integer technicalScore;

	Integer qualityScore;

	Integer riskScore;

	String internalRemarks;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

	@UpdateTimestamp
	@AuditExclude
	Instant updatedAt;

}
