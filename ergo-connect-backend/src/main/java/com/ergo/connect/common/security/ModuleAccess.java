package com.ergo.connect.common.security;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.ergo.connect.common.security.ModuleAccess.Level.A;
import static com.ergo.connect.common.security.ModuleAccess.Level.CO;
import static com.ergo.connect.common.security.ModuleAccess.Level.NONE;
import static com.ergo.connect.common.security.ModuleAccess.Level.O;
import static com.ergo.connect.common.security.ModuleAccess.Level.V;
import static com.ergo.connect.common.security.ModuleAccess.Module.NPD;
import static com.ergo.connect.common.security.ModuleAccess.Module.OFP;
import static com.ergo.connect.common.security.ModuleAccess.Module.QUALITY;
import static com.ergo.connect.common.security.ModuleAccess.Module.QUOTATION;
import static com.ergo.connect.common.security.ModuleAccess.Module.RFQ;

/**
 * The one role-by-module access sheet ("role access.xlsx", client decision 2026-10-19, ARCHITECTURE.md §8): for each
 * of the 18 roles and the five workflow modules, how far the role may go. Layer 1 of access control (the role check)
 * reads this and nothing else; layer 2 (record scope: own company, assigned records) stays in each module's service.
 * It deliberately replaces the per-module role lists the controllers and services used to hardcode.
 * <p>
 * The legend, literally: {@code V} view only; {@code A} acts (create, edit, submit) but gives no final approval;
 * {@code O} owns and gives the final approval; {@code CO} the customer's final approval (the customer-side {@code O}:
 * it also acts); blank = no access, so the page, its endpoints and its notifications are closed to that role.
 * Which workflow step counts as "final approval" in a module is that module's own rule book (SRS §14).
 */
public final class ModuleAccess {

	public enum Module {
		RFQ, QUOTATION, NPD, OFP, QUALITY;
	}

	public enum Level {

		NONE, V, A, O, CO;

		public boolean canView() {
			return this != NONE;
		}

		/** Create / edit / submit: {@code A}, and the two approving levels (they act too). */
		public boolean canAct() {
			return this == A || this == O || this == CO;
		}

		/** Final approval: ERGO's {@code O} or the customer's {@code CO}. */
		public boolean canApprove() {
			return this == O || this == CO;
		}

	}

	private static final Map<Role, Level[]> SHEET = new EnumMap<>(Role.class);

	// Columns, in order: RFQ, QUOTATION, NPD, OFP, QUALITY.
	static {
		row(Role.ERGO_ADMIN, V, V, V, V, V);
		row(Role.ERGO_MANAGEMENT, O, O, O, O, O);
		row(Role.ERGO_KAM, A, V, V, V, V);
		row(Role.ERGO_RFQ, O, O, V, V, V);
		row(Role.ERGO_NPD, V, V, O, V, V);
		row(Role.ERGO_OFP, NONE, NONE, V, O, A);
		row(Role.ERGO_QUALITY, NONE, NONE, A, A, O);
		row(Role.CUSTOMER_RFQ, CO, CO, V, NONE, V);
		row(Role.CUSTOMER_NPD, V, NONE, CO, NONE, CO);
		row(Role.CUSTOMER_OFP, NONE, NONE, NONE, CO, V);
		row(Role.CUSTOMER_QUALITY, V, NONE, A, V, CO);
		row(Role.CUSTOMER_KAM, A, A, V, V, V);
		row(Role.CUSTOMER_MANAGEMENT, A, A, A, A, A);
		row(Role.SUPPLIER_RFQ, A, A, NONE, NONE, NONE);
		row(Role.SUPPLIER_NPD, NONE, NONE, A, NONE, V);
		row(Role.SUPPLIER_OFP, NONE, NONE, NONE, A, V);
		row(Role.SUPPLIER_QUALITY, V, NONE, A, A, A);
		row(Role.SUPPLIER_KAM, A, A, A, A, A);
	}

	private ModuleAccess() {
	}

	private static void row(Role role, Level rfq, Level quotation, Level npd, Level ofp, Level quality) {
		Level[] levels = new Level[Module.values().length];
		levels[RFQ.ordinal()] = rfq;
		levels[QUOTATION.ordinal()] = quotation;
		levels[NPD.ordinal()] = npd;
		levels[OFP.ordinal()] = ofp;
		levels[QUALITY.ordinal()] = quality;
		SHEET.put(role, levels);
	}

	public static Level level(Role role, Module module) {
		return SHEET.get(role)[module.ordinal()];
	}

	public static boolean canView(Role role, Module module) {
		return level(role, module).canView();
	}

	public static boolean canAct(Role role, Module module) {
		return level(role, module).canAct();
	}

	public static boolean canApprove(Role role, Module module) {
		return level(role, module).canApprove();
	}

	/** Roles with any access at all to the module. */
	public static Set<Role> viewers(Module module) {
		return rolesWhere(module, Level::canView, null);
	}

	/** Roles that create / edit / submit in the module (this includes the approving roles). */
	public static Set<Role> actors(Module module) {
		return rolesWhere(module, Level::canAct, null);
	}

	/** Roles that give the module's final approval. */
	public static Set<Role> approvers(Module module) {
		return rolesWhere(module, Level::canApprove, null);
	}

	public static Set<Role> viewers(Module module, CompanyType side) {
		return rolesWhere(module, Level::canView, side);
	}

	public static Set<Role> actors(Module module, CompanyType side) {
		return rolesWhere(module, Level::canAct, side);
	}

	public static Set<Role> approvers(Module module, CompanyType side) {
		return rolesWhere(module, Level::canApprove, side);
	}

	private static Set<Role> rolesWhere(Module module, java.util.function.Predicate<Level> test, CompanyType side) {
		return Arrays.stream(Role.values())
			.filter(r -> (side == null || r.companyType() == side) && test.test(level(r, module)))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
	}

}
