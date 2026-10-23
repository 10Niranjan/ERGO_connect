package com.ergo.connect.quotation.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.ergo.connect.quotation.domain.CustomerQuoteDtos.ResponseStatus;

/** Package-private; the services apply ERGO's RFQ scope, or the customer's own company, to every read. */
interface CustomerQuoteRepository extends Repository<CustomerQuote, Long> {

	CustomerQuote save(CustomerQuote quote);

	Optional<CustomerQuote> findById(Long id);

	List<CustomerQuote> findByRfqIdOrderByIdDesc(Long rfqId);

	List<CustomerQuote> findByRfqIdAndCustomerCompanyIdOrderByIdDesc(Long rfqId, Long customerCompanyId);

	long countByRfqId(Long rfqId);

	boolean existsByRfqIdAndResponseStatusIn(Long rfqId, Collection<ResponseStatus> statuses);

}

interface CustomerQuoteLineRepository extends Repository<CustomerQuoteLine, Long> {

	CustomerQuoteLine save(CustomerQuoteLine line);

	List<CustomerQuoteLine> findBySubmissionIdIn(Collection<Long> submissionIds);

	List<CustomerQuoteLine> findByAssignmentIdIn(Collection<Long> assignmentIds);

	Optional<CustomerQuoteLine> findFirstByAssignmentIdOrderByIdDesc(Long assignmentId);

}
