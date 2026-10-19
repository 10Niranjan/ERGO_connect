package com.ergo.connect.quality.domain;

import com.ergo.connect.common.security.ModuleAccess;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.Role;

/**
 * Which record-scope branch a role's Quality access takes, derived from the role sheet ({@link ModuleAccess}) so the
 * four services that scope inspections, NCRs and deviations (and {@code QualityApiService}'s probes) cannot drift
 * from it: a role with no Quality access is {@link #NONE}; every ERGO role with access sees everything except the
 * account-scoped KAM and the NPD / OFP roles, who see only records linked to their own module; customers and
 * suppliers see their own company's records (each service adds its own visibility flag).
 */
enum QualityScope {

	FULL, KAM, NPD_LINKED, OFP_LINKED, SUPPLIER, CUSTOMER, NONE;

	static QualityScope of(Role role) {
		if (!ModuleAccess.canView(role, Module.QUALITY)) {
			return NONE;
		}
		return switch (role.companyType()) {
			case ERGO -> switch (role) {
				case ERGO_KAM -> KAM;
				case ERGO_NPD -> NPD_LINKED;
				case ERGO_OFP -> OFP_LINKED;
				default -> FULL;
			};
			case SUPPLIER -> SUPPLIER;
			case CUSTOMER -> CUSTOMER;
		};
	}

}
