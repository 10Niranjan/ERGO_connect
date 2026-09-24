package com.ergo.connect.masterdata.access;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.masterdata.api.Directory;

@Service
@Transactional(readOnly = true)
class DirectoryService implements Directory {

	private final CompanyRepository companies;

	private final UserRepository users;

	DirectoryService(CompanyRepository companies, UserRepository users) {
		this.companies = companies;
		this.users = users;
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
	public Set<Long> accountsOf(long userId) {
		return users.findById(userId).map(user -> Set.copyOf(user.accountIds)).orElse(Set.of());
	}

	private static CompanyRef ref(Company c) {
		return new CompanyRef(c.id, c.code, c.name, c.type, c.status == CompanyStatus.ACTIVE);
	}

}
