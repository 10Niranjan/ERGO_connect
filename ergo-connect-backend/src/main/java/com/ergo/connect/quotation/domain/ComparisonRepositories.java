package com.ergo.connect.quotation.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** Package-private; the services apply ERGO's RFQ scope, or the customer's own company, to every read. */
interface ComparisonRepository extends Repository<Comparison, Long> {

	Comparison save(Comparison comparison);

	Optional<Comparison> findByRfqId(Long rfqId);

}

interface EvaluationRepository extends Repository<Evaluation, Long> {

	Evaluation save(Evaluation evaluation);

	Optional<Evaluation> findByAssignmentId(Long assignmentId);

	List<Evaluation> findByAssignmentIdIn(Collection<Long> assignmentIds);

}

interface CustomerQuoteRepository extends Repository<CustomerQuote, Long> {

	CustomerQuote save(CustomerQuote quote);

	Optional<CustomerQuote> findById(Long id);

	List<CustomerQuote> findByRfqIdOrderByIdDesc(Long rfqId);

	List<CustomerQuote> findByRfqIdAndCustomerCompanyIdOrderByIdDesc(Long rfqId, Long customerCompanyId);

	List<CustomerQuote> findByAssignmentIdAndResponseStatus(Long assignmentId, ComparisonDtos.ResponseStatus responseStatus);

}
