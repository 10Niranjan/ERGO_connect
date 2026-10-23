package com.ergo.connect.rfq.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/**
 * One part number of an RFQ (flow doc §1). Field changes are audited automatically, like the RFQ's own. Supplier
 * quotes, status and the customer quotation are kept per item by the quotation module (by {@link #id}).
 */
@Entity
@Table(name = "rfq_items")
class RfqItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "rfq_item_id")
	Long id;

	@Column(updatable = false)
	Long rfqId;

	int lineNo;

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

	BigDecimal targetToolingCost;

	BigDecimal targetPpapPiecePrice;

	BigDecimal targetProductionPiecePrice;

	Integer materialLeadTimeWeeks;

	Integer toolingLeadTimeDays;

	Integer sampleLeadTimeDays;

	Integer productionLeadTimeDays;

	Integer moq;

	/** Audited explicitly as one PROCESS_FLOW change (collections are not diffed field by field). */
	@ElementCollection
	@CollectionTable(name = "rfq_item_process_steps", joinColumns = @JoinColumn(name = "rfq_item_id"))
	@OrderColumn(name = "sequence_no")
	List<ProcessStep> processSteps = new ArrayList<>();

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

	@UpdateTimestamp
	@AuditExclude
	Instant updatedAt;

}
