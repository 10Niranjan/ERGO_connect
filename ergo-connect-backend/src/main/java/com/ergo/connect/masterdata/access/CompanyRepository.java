package com.ergo.connect.masterdata.access;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Package-private and declared method by method (no JpaRepository), so nothing outside this package can read
 * companies unscoped. Record scope is applied by CompanyService.
 */
interface CompanyRepository extends Repository<Company, Long> {

	Company save(Company company);

	Optional<Company> findById(Long id);

	List<Company> findByIdIn(Collection<Long> ids);


	List<Company> findByIdInOrderByNameAsc(Collection<Long> ids);

	List<Company> findAllByOrderByNameAsc();

	boolean existsByCodeIgnoreCase(String code);

	/** Dev seeding only: re-resolve the demo companies when the database already has them. */
	Optional<Company> findByCodeIgnoreCase(String code);

	long count();

	/** Dashboard "Pending approvals" widget (ERGO Admin/Management only). */
	long countByStatus(CompanyStatus status);

	/** The pending companies this reviewer could actually act on: not their own, and no suppliers for ERGO Admin. */
	@Query("""
			select count(c) from Company c
			where c.status = com.ergo.connect.masterdata.access.CompanyStatus.PENDING_APPROVAL
			  and (c.createdByUserId is null or c.createdByUserId <> :userId)
			  and (:admin = false or c.type <> com.ergo.connect.common.security.CompanyType.SUPPLIER)""")
	long countReviewable(long userId, boolean admin);

}
