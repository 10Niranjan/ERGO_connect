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
 * The customer's Sample PO for one approved part (flow doc step 6). Field changes are audited automatically; status
 * changes happen only in SamplePoService (submit, return, accept). Package-private fields: only this package touches them.
 */
@Entity
@Table(name = "sample_pos")
class SamplePo {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "sample_po_id")
	Long id;

	@Column(updatable = false)
	String samplePoNumber;

	@Column(updatable = false)
	Long rfqId;

	@Column(updatable = false)
	Long rfqItemId;

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
	SamplePoStatus status;

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
