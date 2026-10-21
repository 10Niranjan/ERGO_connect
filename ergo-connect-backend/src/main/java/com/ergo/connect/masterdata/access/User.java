package com.ergo.connect.masterdata.access;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;
import com.ergo.connect.common.audit.AuditMasked;
import com.ergo.connect.common.security.Role;

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
import jakarta.persistence.Table;

/** A login (SRS §13.2.2). Belongs to exactly one company for life (ARCHITECTURE.md §8). */
@Entity
@Table(name = "users")
class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "user_id")
	Long id;

	@Column(updatable = false)
	Long companyId;

	String firstName;

	String lastName;

	String email;

	String mobile;

	@AuditMasked
	String passwordHash;

	boolean mustChangePassword = true;

	/** Email-OTP second factor (SRS §12.4.1), mandatory for ERGO_ADMIN (ARCHITECTURE.md §8, Phase 8g). */
	boolean twoFactorEnabled = false;

	/** Mapped roles (user_role_mappings). Changes are audited explicitly as ROLE_ADDED / ROLE_REMOVED. */
	@ElementCollection
	@CollectionTable(name = "user_role_mappings", joinColumns = @JoinColumn(name = "user_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "role_code")
	Set<Role> roles = EnumSet.noneOf(Role.class);

	@Enumerated(EnumType.STRING)
	@Column(name = "default_role_code")
	Role defaultRole;

	/** ERGO Key Account Manager's customer accounts (user_account_assignments). Audited explicitly. */
	@ElementCollection
	@CollectionTable(name = "user_account_assignments", joinColumns = @JoinColumn(name = "user_id"))
	@Column(name = "customer_company_id")
	Set<Long> accountIds = new HashSet<>();

	boolean active = true;

	/** Soft delete by ERGO Admin: the row stays for the audit trail, but the user is hidden and can never sign in. */
	Instant deletedAt;

	Long deletedByUserId;

	@AuditExclude
	int failedLoginCount;

	@AuditExclude
	Instant lockedUntil;

	@AuditExclude
	Instant lastLoginAt;

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

	@UpdateTimestamp
	@AuditExclude
	Instant updatedAt;

}
