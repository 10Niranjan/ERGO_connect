package com.ergo.connect.masterdata.access;

/**
 * PENDING_APPROVAL → ACTIVE or REJECTED (ERGO Admin / ERGO Management); REJECTED → PENDING_APPROVAL (resubmitted);
 * ACTIVE ↔ INACTIVE (ERGO Admin). Only ACTIVE companies can have users signing in or new users added.
 */
public enum CompanyStatus {
	PENDING_APPROVAL, ACTIVE, INACTIVE, REJECTED
}
