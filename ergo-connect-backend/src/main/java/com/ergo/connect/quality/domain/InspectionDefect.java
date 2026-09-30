package com.ergo.connect.quality.domain;

import java.math.BigDecimal;
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

/** A defect line on an inspection (SRS §13.6.2). No defect-category master table in the SRS: free text for MVP
 * (matches the V16 migration comment). Immutable once created ({@link InspectionService} replaces the whole set on
 * submit/edit rather than patching individual lines) - simplest shape that satisfies "record defects", no separate
 * defect CRUD surface needed. */
@Entity
@Table(name = "inspection_defects")
class InspectionDefect {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "inspection_defect_id")
	Long id;

	@Column(updatable = false)
	Long inspectionId;

	@Column(updatable = false)
	String defectCategory;

	@Column(updatable = false)
	String defectDescription;

	@Column(updatable = false)
	BigDecimal defectQuantity;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	DefectSeverity severity;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

}
