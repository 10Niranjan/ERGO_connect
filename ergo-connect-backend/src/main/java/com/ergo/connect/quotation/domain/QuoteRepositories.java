package com.ergo.connect.quotation.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** Package-private; QuoteService applies the record scope for every read. */
interface InvitationRepository extends Repository<Invitation, Long> {

	Invitation save(Invitation invitation);

	Optional<Invitation> findById(Long id);

	List<Invitation> findByRfqIdOrderById(Long rfqId);

	/** The overdue scan: response date passed and the supplier can still quote. */
	List<Invitation> findByResponseDueDateBeforeAndStatusIn(java.time.LocalDate date, java.util.Collection<QuoteStatus> statuses);

	List<Invitation> findBySupplierCompanyIdOrderByIdDesc(Long supplierCompanyId);

}

interface SupplierQuoteRepository extends Repository<SupplierQuote, Long> {

	SupplierQuote save(SupplierQuote quote);

	Optional<SupplierQuote> findByAssignmentId(Long assignmentId);

	List<SupplierQuote> findByAssignmentIdIn(Collection<Long> assignmentIds);

}
