package com.ergo.connect.npd.domain;

import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;

/** Who gives one decision in the validation loop, each tied to exactly one role (flow doc steps 9-10). */
public enum ValidationParty {

	/** Supplier Quality's internal approval, which is also the submission to ERGO Quality and Supplier Quality. */
	SUPPLIER_INTERNAL(Role.SUPPLIER_QUALITY, CompanyType.SUPPLIER),
	SUPPLIER_QUALITY(Role.SUPPLIER_QUALITY, CompanyType.SUPPLIER),
	ERGO_QUALITY(Role.ERGO_QUALITY, CompanyType.ERGO),
	CUSTOMER_NPD(Role.CUSTOMER_NPD, CompanyType.CUSTOMER),
	CUSTOMER_QUALITY(Role.CUSTOMER_QUALITY, CompanyType.CUSTOMER);

	private final Role role;

	private final CompanyType side;

	ValidationParty(Role role, CompanyType side) {
		this.role = role;
		this.side = side;
	}

	Role role() {
		return role;
	}

	CompanyType side() {
		return side;
	}

	/** The parties whose decision closes the quality validation. */
	static final java.util.Set<ValidationParty> VALIDATORS = java.util.EnumSet.of(SUPPLIER_QUALITY, ERGO_QUALITY);

	/** The parties whose decision closes the customer's final review. */
	static final java.util.Set<ValidationParty> CUSTOMER_REVIEWERS = java.util.EnumSet.of(CUSTOMER_NPD, CUSTOMER_QUALITY);

	/** The party a role decides as, or null when the role takes no part in the loop. */
	static ValidationParty ofRole(Role role) {
		for (ValidationParty party : values()) {
			if (party != SUPPLIER_INTERNAL && party.role == role) {
				return party;
			}
		}
		return null;
	}

}
