package com.ergo.connect.quotation.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** Package-private; QuoteService applies the record scope for every read. */
interface InvitationRepository extends Repository<Invitation, Long> {

	Invitation save(Invitation invitation);

	Optional<Invitation> findById(Long id);

	List<Invitation> findByIdIn(Collection<Long> ids);

	List<Invitation> findByRfqIdOrderById(Long rfqId);

	List<Invitation> findByRfqIdIn(java.util.Collection<Long> rfqIds);

	/** The overdue scan: response date passed and the supplier can still quote. */
	List<Invitation> findByResponseDueDateBeforeAndStatusIn(java.time.LocalDate date,
			java.util.Collection<QuoteStatus> statuses);

	List<Invitation> findBySupplierCompanyIdOrderByIdDesc(Long supplierCompanyId);

	/** The supplier's list page. The only Specification method on purpose (see RfqRepository). */
	org.springframework.data.domain.Page<Invitation> findAll(org.springframework.data.jpa.domain.Specification<Invitation> spec,
			org.springframework.data.domain.Pageable pageable);

	/** Ids only, to ask the RFQ module which of this supplier's parts match a search. */
	@org.springframework.data.jpa.repository.Query("select i.rfqItemId from Invitation i where i.supplierCompanyId = :supplier and i.status <> com.ergo.connect.quotation.domain.QuoteStatus.RECALLED")
	List<Long> itemIdsOf(@org.springframework.data.repository.query.Param("supplier") Long supplier);

	/** Dashboard "Quotes pending submission" widget (ERGO Admin/Management/RFQ only; {@code QuoteService} filters
	 * by each invitation's own RFQ still being open for quotes). */
	List<Invitation> findByStatusIn(java.util.Collection<QuoteStatus> statuses);

	/** The parts the customer has approved, for the modules that start from them (NPD). */
	List<Invitation> findByStatusOrderByIdDesc(QuoteStatus status);

	Optional<Invitation> findFirstByRfqItemIdAndStatusNot(Long rfqItemId, QuoteStatus status);

}

interface SubRfqRepository extends Repository<SubRfq, Long> {

	SubRfq save(SubRfq subRfq);

	Optional<SubRfq> findById(Long id);

	List<SubRfq> findByIdIn(Collection<Long> ids);

	List<SubRfq> findByRfqIdOrderBySubNo(Long rfqId);

	int countByRfqId(Long rfqId);

}

interface SupplierQuoteRepository extends Repository<SupplierQuote, Long> {

	SupplierQuote save(SupplierQuote quote);

	Optional<SupplierQuote> findByAssignmentId(Long assignmentId);

	List<SupplierQuote> findByAssignmentIdIn(Collection<Long> assignmentIds);

}
