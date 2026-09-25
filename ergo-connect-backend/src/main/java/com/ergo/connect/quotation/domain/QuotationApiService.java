package com.ergo.connect.quotation.domain;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.quotation.api.QuotationApi;

@Service
@Transactional
class QuotationApiService implements QuotationApi {

	private final CustomerQuoteRepository customerQuotes;

	private final InvitationRepository invitations;

	private final SupplierQuoteRepository quotes;

	private final Directory directory;

	QuotationApiService(CustomerQuoteRepository customerQuotes, InvitationRepository invitations,
			SupplierQuoteRepository quotes, Directory directory) {
		this.customerQuotes = customerQuotes;
		this.invitations = invitations;
		this.quotes = quotes;
		this.directory = directory;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SelectedQuote> selectedQuoteFor(long rfqId) {
		return customerQuotes
			.findFirstByRfqIdAndResponseStatusOrderByIdDesc(rfqId, ComparisonDtos.ResponseStatus.APPROVED)
			.map(approved -> {
				Invitation invitation = invitations.findById(approved.assignmentId).orElseThrow();
				CompanyRef supplier = directory.company(invitation.supplierCompanyId).orElseThrow();
				String reference = quotes.findByAssignmentId(invitation.id).map(q -> q.quoteReference).orElse(null);
				return new SelectedQuote(supplier.id(), supplier.name(), reference);
			});
	}

}
