package com.ergo.connect.common.security;

import static com.ergo.connect.common.security.CompanyType.CUSTOMER;
import static com.ergo.connect.common.security.CompanyType.ERGO;
import static com.ergo.connect.common.security.CompanyType.SUPPLIER;

/** The SRS §2 roles. Codes match the {@code roles} table (V2 migration); RolesCatalogTests keeps the two in sync. */
public enum Role {

	ERGO_ADMIN(ERGO), ERGO_MANAGEMENT(ERGO), ERGO_KAM(ERGO), ERGO_RFQ(ERGO), ERGO_NPD(ERGO), ERGO_OFP(ERGO),
	ERGO_QUALITY(ERGO),

	CUSTOMER_RFQ(CUSTOMER), CUSTOMER_NPD(CUSTOMER), CUSTOMER_OFP(CUSTOMER), CUSTOMER_QUALITY(CUSTOMER),
	CUSTOMER_KAM(CUSTOMER), CUSTOMER_MANAGEMENT(CUSTOMER),

	SUPPLIER_RFQ(SUPPLIER), SUPPLIER_NPD(SUPPLIER), SUPPLIER_OFP(SUPPLIER), SUPPLIER_QUALITY(SUPPLIER),
	SUPPLIER_KAM(SUPPLIER);

	private final CompanyType companyType;

	Role(CompanyType companyType) {
		this.companyType = companyType;
	}

	/** Only users of this company type may hold the role (also enforced by a DB trigger). */
	public CompanyType companyType() {
		return companyType;
	}

}
