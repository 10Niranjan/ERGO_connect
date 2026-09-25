package com.ergo.connect.quotation.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.quotation.domain.ComparisonDtos.ComparisonView;
import com.ergo.connect.quotation.domain.ComparisonDtos.CustomerQuotesView;
import com.ergo.connect.quotation.domain.ComparisonDtos.EvaluationForm;
import com.ergo.connect.quotation.domain.ComparisonDtos.ResponseForm;
import com.ergo.connect.quotation.domain.ComparisonDtos.SendForm;
import com.ergo.connect.quotation.domain.ComparisonDtos.SummaryForm;
import com.ergo.connect.quotation.domain.ComparisonService;
import com.ergo.connect.quotation.domain.CustomerQuoteService;

import jakarta.validation.Valid;

/**
 * SRS §12.7.1-12.7.2 quote comparison and the customer's quote review. Layer 1 (roles) is here; record scope and
 * status rules are in ComparisonService and CustomerQuoteService. Comparing and sending is ERGO RFQ's job (SRS §9.1,
 * §10.5 C/E/S), with ERGO Admin; other ERGO roles view within their RFQ scope; customers see only what was sent to them.
 */
@RestController
class ComparisonController {

	private static final String ERGO_VIEWERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM', "
			+ "'ERGO_NPD')";

	private static final String EVALUATORS = "hasAnyRole('ERGO_ADMIN', 'ERGO_RFQ')";

	private static final String CUSTOMERS = "hasAnyRole('CUSTOMER_RFQ', 'CUSTOMER_KAM', 'CUSTOMER_MANAGEMENT')";

	private static final String QUOTE_READERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM', "
			+ "'ERGO_NPD', 'CUSTOMER_RFQ', 'CUSTOMER_KAM', 'CUSTOMER_MANAGEMENT')";

	private final ComparisonService comparisons;

	private final CustomerQuoteService customerQuotes;

	ComparisonController(ComparisonService comparisons, CustomerQuoteService customerQuotes) {
		this.comparisons = comparisons;
		this.customerQuotes = customerQuotes;
	}

	@GetMapping("/api/rfqs/{rfqId}/comparison")
	@PreAuthorize(ERGO_VIEWERS)
	ComparisonView comparison(@PathVariable long rfqId) {
		return comparisons.comparison(rfqId);
	}

	@PutMapping("/api/rfqs/{rfqId}/comparison")
	@PreAuthorize(EVALUATORS)
	ComparisonView saveSummary(@PathVariable long rfqId, @Valid @RequestBody SummaryForm body) {
		return comparisons.saveSummary(rfqId, body);
	}

	@PutMapping("/api/quotes/{id}/evaluation")
	@PreAuthorize(EVALUATORS)
	ComparisonView evaluate(@PathVariable long id, @Valid @RequestBody EvaluationForm body) {
		return comparisons.evaluate(id, body);
	}

	@GetMapping("/api/rfqs/{rfqId}/customer-quotes")
	@PreAuthorize(QUOTE_READERS)
	CustomerQuotesView customerQuotes(@PathVariable long rfqId) {
		return customerQuotes.list(rfqId);
	}

	@PostMapping("/api/rfqs/{rfqId}/customer-quotes")
	@PreAuthorize(EVALUATORS)
	CustomerQuotesView send(@PathVariable long rfqId, @Valid @RequestBody SendForm body) {
		return customerQuotes.send(rfqId, body);
	}

	@PostMapping("/api/customer-quotes/{id}/respond")
	@PreAuthorize(CUSTOMERS)
	CustomerQuotesView respond(@PathVariable long id, @Valid @RequestBody ResponseForm body) {
		return customerQuotes.respond(id, body);
	}

}
