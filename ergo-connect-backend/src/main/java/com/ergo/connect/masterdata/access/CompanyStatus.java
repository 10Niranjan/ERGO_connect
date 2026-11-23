package com.ergo.connect.masterdata.access;

/**
 * DRAFT → PENDING_APPROVAL (sent for approval by an ERGO editor once the minimum information is in);
 * PENDING_APPROVAL → ACTIVE, REJECTED or INFO_REQUESTED (ERGO Management only); INFO_REQUESTED and REJECTED →
 * PENDING_APPROVAL (sent again); ACTIVE ↔ INACTIVE (ERGO Admin). Only ACTIVE companies can have users signing in or
 * new users added.
 */
public enum CompanyStatus {
	DRAFT, PENDING_APPROVAL, INFO_REQUESTED, ACTIVE, INACTIVE, REJECTED
}
