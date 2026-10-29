package com.ergo.connect.rfq.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.Repository;

/** Package-private; RfqService applies the record scope for every read. */
interface RfqRepository extends Repository<Rfq, Long> {

	/** The only Specification method on purpose: JpaSpecificationExecutor would also bring bulk update/delete that skip @Version, the workflow and audit. */
	Page<Rfq> findAll(Specification<Rfq> spec, Pageable pageable);

	Rfq save(Rfq rfq);

	Optional<Rfq> findById(Long id);

	List<Rfq> findByIdIn(Collection<Long> ids);

	List<Rfq> findAllByOrderByIdDesc();

	List<Rfq> findByCustomerCompanyIdOrderByIdDesc(Long customerCompanyId);

	List<Rfq> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	List<Rfq> findByStatusInOrderByIdDesc(Collection<RfqStatus> statuses);

}

interface RfqItemRepository extends Repository<RfqItem, Long> {

	RfqItem save(RfqItem item);

	void delete(RfqItem item);

	Optional<RfqItem> findById(Long id);

	List<RfqItem> findByIdIn(Collection<Long> ids);

	List<RfqItem> findByRfqIdOrderByLineNo(Long rfqId);

	List<RfqItem> findByRfqIdInOrderByRfqIdAscLineNoAsc(Collection<Long> rfqIds);

}
