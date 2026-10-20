package com.ergo.connect.common.security;

import java.util.EnumMap;
import java.util.Map;

import com.ergo.connect.common.security.ModuleAccess.Module;

/**
 * The signed-off role access sheet ("role access.xlsx", 2026-10-19) as shipped. It is no longer what the system
 * enforces (that is the {@code role_module_permissions} table, see {@link AccessPolicy}); it is what "reset to default"
 * restores and what the parity tests check the seed against. Codes: NV, V, A, O.
 */
public final class DefaultAccessSheet {

	// Columns, in order: RFQ, QUOTATION, NPD, OFP, QUALITY.
	private static final Map<Role, String[]> SHEET = new EnumMap<>(Role.class);

	static {
		row(Role.ERGO_ADMIN, "V", "V", "V", "V", "V");
		row(Role.ERGO_MANAGEMENT, "O", "O", "O", "O", "O");
		row(Role.ERGO_KAM, "A", "V", "V", "V", "V");
		row(Role.ERGO_RFQ, "O", "O", "V", "V", "V");
		row(Role.ERGO_NPD, "V", "V", "O", "V", "V");
		row(Role.ERGO_OFP, "NV", "NV", "V", "O", "A");
		row(Role.ERGO_QUALITY, "NV", "NV", "A", "A", "O");
		row(Role.CUSTOMER_RFQ, "O", "O", "V", "NV", "V");
		row(Role.CUSTOMER_NPD, "V", "NV", "O", "NV", "O");
		row(Role.CUSTOMER_OFP, "NV", "NV", "NV", "O", "V");
		row(Role.CUSTOMER_QUALITY, "V", "NV", "A", "V", "O");
		row(Role.CUSTOMER_KAM, "A", "A", "V", "V", "V");
		row(Role.CUSTOMER_MANAGEMENT, "A", "A", "A", "A", "A");
		row(Role.SUPPLIER_RFQ, "A", "A", "NV", "NV", "NV");
		row(Role.SUPPLIER_NPD, "NV", "NV", "A", "NV", "V");
		row(Role.SUPPLIER_OFP, "NV", "NV", "NV", "A", "V");
		row(Role.SUPPLIER_QUALITY, "V", "NV", "A", "A", "A");
		row(Role.SUPPLIER_KAM, "A", "A", "A", "A", "A");
	}

	private DefaultAccessSheet() {
	}

	private static void row(Role role, String... levels) {
		SHEET.put(role, levels);
	}

	/** The shipped default for a workflow module, or {@code null} for a module with no shipped default. */
	public static String code(Role role, String module) {
		try {
			return SHEET.get(role)[Module.valueOf(module).ordinal()];
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

}
