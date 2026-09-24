package com.ergo.connect.masterdata.access;

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

	/** Record scope lives in the query: companyId null = every company (ERGO roles), else only that company. */
	@Query("""
			select u from User u left join fetch u.roles left join fetch u.accountIds
			where (:companyId is null or u.companyId = :companyId)
			order by u.firstName, u.lastName""")
	List<User> findAllVisible(Long companyId);

	@Query("select u from User u where u.id = :id and (:companyId is null or u.companyId = :companyId)")
	Optional<User> findVisible(Long id, Long companyId);

}
