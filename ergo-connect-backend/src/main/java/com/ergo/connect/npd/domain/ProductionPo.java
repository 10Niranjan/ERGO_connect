package com.ergo.connect.npd.domain;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;

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
 * The customer's production order for an approved sample project (flow doc step 11), submitted under OFP. Field changes
 * are audited automatically; status changes happen only in ProductionPoService (submit, return, accept).
 */
@Entity
@Table(name = "production_pos")
class ProductionPo {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "production_po_id")
	Long id;

	@Column(updatable = false)
	String productionPoNumber;

	/** The approved sample project this order continues. */
	@Column(updatable = false)
	Long sourceProjectId;

	@Column(updatable = false)
	Long customerCompanyId;

	@Column(updatable = false)
	String partNumber;

	/** The customer's own PO number, as printed on their document. */
	String customerPoNumber;

	int quantity;

	LocalDate requiredDate;

	String remarks;

	@Enumerated(EnumType.STRING)
	ProductionPoStatus status;

	String returnComment;

	@Column(updatable = false)
	Long createdByUserId;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

	@UpdateTimestamp
	@AuditExclude
	Instant updatedAt;

	/** Optimistic lock: of two concurrent changes (an edit and a KAM decision) the later one fails (409). */
	@Version
	@AuditExclude
	long rowVersion;

}
