package com.ergo.connect.quotation.domain;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The parts of an RFQ ERGO sent to one supplier in one go (flow doc §2: the whole RFQ, or a sub-RFQ of some of its
 * parts). The supplier's quotes are per part ({@link Invitation}); this only groups them.
 */
@Entity
@Table(name = "sub_rfqs")
class SubRfq {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "sub_rfq_id")
	Long id;

	@Column(updatable = false)
	Long rfqId;

	@Column(updatable = false)
	int subNo;

	@Column(updatable = false)
	Long supplierCompanyId;

	@Column(updatable = false)
	LocalDate responseDueDate;

	@Column(updatable = false)
	Long sentByUserId;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

}
