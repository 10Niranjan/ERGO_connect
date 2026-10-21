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
		Role role = CurrentUser.get().role();
		if (role != Role.ERGO_ADMIN && role != Role.ERGO_MANAGEMENT) {
			return 0;
		}
		return (int) companies.countReviewable(CurrentUser.get().userId(), role == Role.ERGO_ADMIN);
	}

	private static CompanyRef ref(Company c) {
		return new CompanyRef(c.id, c.code, c.name, c.type, c.status == CompanyStatus.ACTIVE);
	}

}
