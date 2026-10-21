package com.ergo.connect.masterdata.access;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Package-private, no unscoped finders beyond the internal ones documented below. */
interface UserRepository extends Repository<User, Long> {

	User save(User user);

	/** Internal only (auth flows, ERGO Admin writes). User-facing reads go through the scoped finders. */
	Optional<User> findById(Long id);

	/** Pre-authentication lookup for login. */
	@Query("select u from User u where lower(u.email) = lower(:email)")
	Optional<User> findByEmail(String email);

	boolean existsByEmailIgnoreCase(String email);

	List<User> findByIdIn(Collection<Long> ids);

	List<User> findByCompanyIdAndActiveTrueOrderByFirstNameAscLastNameAsc(Long companyId);

	@Query("select distinct u from User u left join fetch u.roles where u.id in :ids")
	List<User> findWithRolesByIdIn(Collection<Long> ids);

	@Query("""
			select u from User u
			where u.active = true and u.companyId in (select c.id from Company c where c.type = :type)""")
	List<User> findActiveByCompanyType(com.ergo.connect.common.security.CompanyType type);

	/** Record scope lives in the query: companyId null = every company (ERGO roles), else only that company. */
	@Query("""
			select u from User u left join fetch u.roles left join fetch u.accountIds
			where u.deletedAt is null and (:companyId is null or u.companyId = :companyId)
			order by u.firstName, u.lastName""")
	List<User> findAllVisible(Long companyId);

	/** Soft-deleted users do not exist for anyone. */
	@Query("""
			select u from User u
			where u.id = :id and u.deletedAt is null and (:companyId is null or u.companyId = :companyId)""")
	Optional<User> findVisible(Long id, Long companyId);

}
