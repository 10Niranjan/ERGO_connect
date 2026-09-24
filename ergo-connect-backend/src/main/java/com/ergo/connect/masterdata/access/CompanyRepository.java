package com.ergo.connect.masterdata.access;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

	long count();

}
