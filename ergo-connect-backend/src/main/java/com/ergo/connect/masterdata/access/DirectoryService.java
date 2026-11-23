package com.ergo.connect.masterdata.access;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.api.Directory;

@Service
@Transactional(readOnly = true)
class DirectoryService implements Directory {

	private final CompanyRepository companies;

	private final UserRepository users;

	private final CompanyService companyService;

	DirectoryService(CompanyRepository companies, UserRepository users, CompanyService companyService) {
		this.companies = companies;
		this.users = users;
		this.companyService = companyService;
	}

	@Override
	public boolean companyVisibleToCaller(long id) {
		return companyService.visibleToCaller(id);
	}

	@Override
	public Optional<CompanyRef> company(long id) {
		return companies.findById(id).map(DirectoryService::ref);
	}

	/**
	 * The prefix of this customer's RFQ numbers: the one ERGO set, else initials of its name ("International
	 * Consortium" -> IC), at most four. A derived prefix is stored the first time it is needed and made unique (a digit
	 * is added when another company already has it), so two customers never share a number series.
	 */
	@Override
	@Transactional
	public String rfqPrefix(long companyId) {
		Company c = companies.findById(companyId).orElseThrow();
		if (c.rfqPrefix != null) {
			return c.rfqPrefix;
		}
		StringBuilder initials = new StringBuilder();
		for (String word : c.name.split("[^\\p{L}\\p{N}]+")) {
			String letter = word.isEmpty() ? "" : word.substring(0, 1).toUpperCase().replaceAll("[^A-Z0-9]", "");
			initials.append(letter);
		}
		String base = initials.length() > 4 ? initials.substring(0, 4) : initials.toString();
		if (base.length() < 2) {
			base = "RFQ";
		}
		String prefix = base;
		for (int n = 2; n <= 99 && companies.findByRfqPrefix(prefix).isPresent(); n++) {
			prefix = base + n;
		}
		if (companies.findByRfqPrefix(prefix).isPresent()) {
			// 99 other companies share these initials: the company's own id is unique by construction
			prefix = "C" + Long.toString(companyId, 36).toUpperCase();
		}
		c.rfqPrefix = prefix;
		return prefix;
	}

	@Override
	public Set<Long> companyIdsByName(String fragment) {
		return companies.findByNameContainingIgnoreCase(fragment).stream().map(c -> c.id).collect(Collectors.toSet());
	}

	@Override
	public Map<Long, CompanyRef> companies(Collection<Long> ids) {
		return companies.findByIdIn(ids).stream().map(DirectoryService::ref)
			.collect(Collectors.toMap(CompanyRef::id, Function.identity()));
	}

	@Override
	public Map<Long, UserRef> users(Collection<Long> ids) {
		return users.findByIdIn(ids).stream()
			.map(u -> new UserRef(u.id, u.firstName + " " + u.lastName, u.companyId))
			.collect(Collectors.toMap(UserRef::id, Function.identity()));
	}

	@Override
	public java.util.List<UserRef> activeUsersOfType(com.ergo.connect.common.security.CompanyType type) {
		return users.findActiveByCompanyType(type).stream()
			.map(u -> new UserRef(u.id, u.firstName + " " + u.lastName, u.companyId))
			.toList();
	}

	@Override
	public Map<Long, UserRef> activeUsers(Collection<Long> ids) {
		return users.findByIdIn(ids).stream()
			.filter(u -> u.active)
			.collect(Collectors.toMap(u -> u.id, u -> new UserRef(u.id, u.firstName + " " + u.lastName, u.companyId)));
	}

	@Override
	public Map<Long, Set<com.ergo.connect.common.security.Role>> rolesOf(Collection<Long> userIds) {
		return users.findWithRolesByIdIn(userIds).stream().collect(Collectors.toMap(u -> u.id, u -> Set.copyOf(u.roles)));
	}

	@Override
	public java.util.List<UserRef> activeUsersOf(long companyId) {
		return users.findByCompanyIdAndActiveTrueOrderByFirstNameAscLastNameAsc(companyId).stream()
			.map(u -> new UserRef(u.id, u.firstName + " " + u.lastName, u.companyId))
			.toList();
	}

	@Override
	public Set<Long> accountsOf(long userId) {
		return users.findById(userId).map(user -> Set.copyOf(user.accountIds)).orElse(Set.of());
	}

	@Override
	public int pendingCompanyApprovalsCount() {
		// ERGO Management alone approves companies (client question 11)
		if (CurrentUser.get().role() != Role.ERGO_MANAGEMENT) {
			return 0;
		}
		return (int) companies.countReviewable(CurrentUser.get().userId());
	}

	private static CompanyRef ref(Company c) {
		return new CompanyRef(c.id, c.code, c.name, c.type, c.status == CompanyStatus.ACTIVE);
	}

}
