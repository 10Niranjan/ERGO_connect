package com.ergo.connect.masterdata.access;

import java.time.Instant;

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

/** ERGO, customer and supplier organisations (SRS §13.2.1). Fields are package-private: only this package edits them. */
@Entity
@Table(name = "companies")
class Company {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "company_id")
	Long id;

	@Column(name = "company_code", updatable = false)
	String code;

	@Column(name = "company_name")
	String name;

	/** Never changes: users' roles are validated against it. */
	@Enumerated(EnumType.STRING)
	@Column(name = "company_type", updatable = false)
	CompanyType type;

	@Column(name = "address_line_1")
	String addressLine1;

	@Column(name = "address_line_2")
	String addressLine2;

	String city;

	String state;

	String country;

	String postalCode;

	String taxId;

	String website;

	String primaryContactName;

	String primaryContactEmail;

	String primaryContactPhone;

	boolean active = true;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

	@UpdateTimestamp
	@AuditExclude
	Instant updatedAt;

}
