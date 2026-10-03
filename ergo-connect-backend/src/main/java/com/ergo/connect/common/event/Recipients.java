package com.ergo.connect.common.event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.ergo.connect.common.security.Role;

/**
 * Who a publisher wants to tell. This is a request, not a grant: the communication module still drops the acting
 * user, anyone who is inactive, anyone the event's audience rules exclude, and anyone who cannot read the record the
 * event is about (the owning module's own scope checks, run as that person) - so a wrong or stale target can at worst
 * notify nobody, never disclose a record.
 *
 * @param userIds explicit people (assignee, mentioned user, comment author); every role they hold counts
 * @param ergoRoles ERGO staff holding at least one of these roles
 * @param companies a customer or supplier company's staff holding at least one of the given roles (empty = any role)
 */
public record Recipients(Set<Long> userIds, Set<Role> ergoRoles, List<CompanyRoles> companies) {

	public record CompanyRoles(long companyId, Set<Role> roles) {
	}

	public static Recipients none() {
		return new Recipients(Set.of(), Set.of(), List.of());
	}

	public static Recipients users(Collection<Long> userIds) {
		return new Recipients(Set.copyOf(userIds), Set.of(), List.of());
	}

	public static Recipients ergo(Role... roles) {
		return new Recipients(Set.of(), roleSet(roles), List.of());
	}

	/** A company's staff in the given roles; no roles = everyone in the company who can read the record. */
	public static Recipients company(long companyId, Role... roles) {
		return new Recipients(Set.of(), Set.of(), List.of(new CompanyRoles(companyId, roleSet(roles))));
	}

	public Recipients and(Recipients other) {
		List<CompanyRoles> merged = new ArrayList<>(companies);
		merged.addAll(other.companies);
		java.util.Set<Long> ids = new java.util.HashSet<>(userIds);
		ids.addAll(other.userIds);
		Set<Role> roles = roleSet();
		roles.addAll(ergoRoles);
		roles.addAll(other.ergoRoles);
		return new Recipients(Set.copyOf(ids), Set.copyOf(roles), List.copyOf(merged));
	}

	private static Set<Role> roleSet(Role... roles) {
		Set<Role> set = EnumSet.noneOf(Role.class);
		set.addAll(Arrays.asList(roles));
		return set;
	}

}
