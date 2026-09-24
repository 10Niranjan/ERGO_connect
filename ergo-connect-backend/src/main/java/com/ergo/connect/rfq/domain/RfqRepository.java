package com.ergo.connect.rfq.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** Package-private; RfqService applies the record scope for every read. */
interface RfqRepository extends Repository<Rfq, Long> {

	Rfq save(Rfq rfq);

	Optional<Rfq> findById(Long id);

	List<Rfq> findByIdIn(Collection<Long> ids);

	List<Rfq> findAllByOrderByIdDesc();

	List<Rfq> findByCustomerCompanyIdOrderByIdDesc(Long customerCompanyId);

	List<Rfq> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	List<Rfq> findByStatusInOrderByIdDesc(Collection<RfqStatus> statuses);

}
