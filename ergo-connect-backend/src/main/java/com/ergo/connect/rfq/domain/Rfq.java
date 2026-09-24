package com.ergo.connect.rfq.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A request for quotation (SRS §13.3.1). Field changes are audited automatically; status changes only happen in
 * RfqService's workflow actions. Package-private fields: only this package touches them.
 */
@Entity
@Table(name = "rfqs")
class Rfq {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "rfq_id")
	Long id;

	@Column(name = "rfq_number", updatable = false)
	String number;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	SourceType sourceType;

	@Column(updatable = false)
	Long customerCompanyId;

	@Column(updatable = false)
	Long createdByUserId;

	Long ownerUserId;

	@Enumerated(EnumType.STRING)
	RfqStatus status;

	@Enumerated(EnumType.STRING)
	Priority priority;

	String partNumber;

	String revision;

	String description;

	Integer eau;

	BigDecimal netWeightKg;

	BigDecimal grossWeightKg;

	Long materialId;

	String materialSpec;

	Long coatingId;

	String coatingSpec;

	LocalDate targetQuoteDate;

	Long currencyId;

	BigDecimal targetToolingCost;

	BigDecimal targetPpapPiecePrice;

	BigDecimal targetProductionPiecePrice;

	Long paymentTermId;

	Integer materialLeadTimeWeeks;

	Integer toolingLeadTimeDays;

	Integer sampleLeadTimeDays;

	Integer productionLeadTimeDays;

	Integer moq;

	String customerRemarks;

	String internalNotes;

	String clarificationQuestion;

	String clarificationAnswer;

	/** Audited explicitly as one PROCESS_FLOW change (collections are not diffed field by field). */
	@ElementCollection
	@CollectionTable(name = "rfq_process_steps", joinColumns = @JoinColumn(name = "rfq_id"))
	@OrderColumn(name = "sequence_no")
	List<ProcessStep> processSteps = new ArrayList<>();

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
