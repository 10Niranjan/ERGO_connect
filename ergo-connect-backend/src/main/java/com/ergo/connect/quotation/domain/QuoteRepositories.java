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
