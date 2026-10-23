package com.ergo.connect.quotation.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.quotation.domain.CustomerQuoteDtos.CustomerQuotesView;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.GenerateForm;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.ResponseForm;
import com.ergo.connect.quotation.domain.CustomerQuoteService;

import jakarta.validation.Valid;

/**
 * SRS §12.7.2 and flow doc §4-5: ERGO generates the customer's quotation, the customer answers it. Layer 1 (roles) is
 * here; record scope and status rules are in CustomerQuoteService. Generating is ERGO RFQ's job (the "O" roles); other
 * ERGO roles view within their RFQ scope; customers see only what was generated for them.
 */
@RestController
class CustomerQuoteController {

	// The role sheet (ModuleAccess), Quotation column.
	private static final String EVALUATORS = "@access.approve('QUOTATION', 'ERGO')";

	/** Customers that may answer a quotation at all; CustomerQuoteService limits the final answers to "CO". */
	private static final String CUSTOMERS = "@access.act('QUOTATION', 'CUSTOMER')";

	private static final String QUOTE_READERS = "@access.view('QUOTATION', 'ERGO') or @access.view('QUOTATION', 'CUSTOMER')";

	private final CustomerQuoteService customerQuotes;

	CustomerQuoteController(CustomerQuoteService customerQuotes) {
		this.customerQuotes = customerQuotes;
	}

	@GetMapping("/api/rfqs/{rfqId}/customer-quotes")
	@PreAuthorize(QUOTE_READERS)
	CustomerQuotesView customerQuotes(@PathVariable long rfqId) {
		return customerQuotes.list(rfqId);
	}

	/** "Generate Quotation for Customer". */
	@PostMapping("/api/rfqs/{rfqId}/customer-quotes")
	@PreAuthorize(EVALUATORS)
	CustomerQuotesView generate(@PathVariable long rfqId, @Valid @RequestBody GenerateForm body) {
		return customerQuotes.generate(rfqId, body);
	}

	@PostMapping("/api/customer-quotes/{id}/respond")
	@PreAuthorize(CUSTOMERS)
	CustomerQuotesView respond(@PathVariable long id, @Valid @RequestBody ResponseForm body) {
		return customerQuotes.respond(id, body);
	}

}
