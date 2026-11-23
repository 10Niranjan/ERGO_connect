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

	List<Company> findByNameContainingIgnoreCase(String fragment);

	boolean existsByCodeIgnoreCase(String code);

	Optional<Company> findByRfqPrefix(String rfqPrefix);

	/** Dev seeding only: re-resolve the demo companies when the database already has them. */
	Optional<Company> findByCodeIgnoreCase(String code);

	long count();

	/** Dashboard "Pending approvals" widget (ERGO Admin/Management only). */
	long countByStatus(CompanyStatus status);

	/** The pending companies this ERGO Management reviewer could actually act on: not registered or sent by them. */
	@Query("""
			select count(c) from Company c
			where c.status = com.ergo.connect.masterdata.access.CompanyStatus.PENDING_APPROVAL
			  and (c.createdByUserId is null or c.createdByUserId <> :userId)
			  and (c.submittedByUserId is null or c.submittedByUserId <> :userId)""")
	long countReviewable(long userId);

	/** Automatic company numbers (client question 27). */
	@Query(value = "select nextval('company_customer_number_seq')", nativeQuery = true)
	long nextCustomerNumber();

	@Query(value = "select nextval('company_supplier_number_seq')", nativeQuery = true)
	long nextSupplierNumber();

}
