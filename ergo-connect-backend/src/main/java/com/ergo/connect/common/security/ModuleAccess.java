package com.ergo.connect.common.security;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The role-by-module access sheet for the five workflow modules: for each of the 18 roles, how far the role may go.
 * Layer 1 of access control (the role check) reads this and nothing else; layer 2 (record scope: own company, assigned
 * records) stays in each module's service.
 * <p>
 * The values are not here any more: ERGO Admin edits them at runtime (Permission Management) and they live in the
 * database ({@link AccessPolicy}; the shipped defaults are {@link DefaultAccessSheet}). This class keeps its original
 * methods so every {@code @access.*} check and service rule reads the live sheet. The role sets it returns are live
 * views, so a constant such as {@code private static final Set<Role> EDITORS = ModuleAccess.actors(...)} still
 * reflects an edit made after class loading.
 * <p>
 * The legend: {@code V} view only; {@code A} acts (create, edit, submit) but gives no final approval; {@code O} owns and
 * gives the final approval; {@code CO} the customer's final approval (the customer-side {@code O}: it also acts);
 * {@code NONE} = no access, so the page, its endpoints and its notifications are closed to that role. Which workflow
 * step counts as "final approval" in a module is that module's own rule book (SRS §14).
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

		/** Final approval: {@code O} or the customer's {@code CO}. */
		public boolean canApprove() {
			return this == O || this == CO;
		}

	}

	private ModuleAccess() {
	}

	public static Level level(Role role, Module module) {
		return AccessPolicy.level(role, module.name());
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

	/** {@link #actors(Module, CompanyType)} without the given roles (still live). */
	public static Set<Role> actorsExcept(Module module, CompanyType side, Role... except) {
		Set<Role> actors = actors(module, side);
		Set<Role> excluded = EnumSet.noneOf(Role.class);
		excluded.addAll(Arrays.asList(except));
		return new LiveRoleSet(r -> actors.contains(r) && !excluded.contains(r));
	}

	/** {@link #viewers(Module, CompanyType)} without the given roles (still live). */
	public static Set<Role> viewersExcept(Module module, CompanyType side, Role... except) {
		Set<Role> viewers = viewers(module, side);
		Set<Role> excluded = EnumSet.noneOf(Role.class);
		excluded.addAll(Arrays.asList(except));
		return new LiveRoleSet(r -> viewers.contains(r) && !excluded.contains(r));
	}

	private static Set<Role> rolesWhere(Module module, Predicate<Level> test, CompanyType side) {
		return new LiveRoleSet(r -> (side == null || r.companyType() == side) && test.test(level(r, module)));
	}

	/** Read-only set of roles recomputed from the live sheet on every use. */
	private static final class LiveRoleSet extends AbstractSet<Role> {

		private final Predicate<Role> member;

		LiveRoleSet(Predicate<Role> member) {
			this.member = member;
		}

		@Override
		public boolean contains(Object o) {
			return o instanceof Role r && member.test(r);
		}

		@Override
		public Iterator<Role> iterator() {
			return Arrays.stream(Role.values())
				.filter(member)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)))
				.iterator();
		}

		@Override
		public int size() {
			return (int) Arrays.stream(Role.values()).filter(member).count();
		}

	}

}
