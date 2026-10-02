package com.ergo.connect.quotation.domain;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
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

	private final QuoteService quoteService;

	QuotationApiService(CustomerQuoteRepository customerQuotes, InvitationRepository invitations,
			SupplierQuoteRepository quotes, Directory directory, QuoteService quoteService) {
		this.customerQuotes = customerQuotes;
		this.invitations = invitations;
		this.quotes = quotes;
		this.directory = directory;
		this.quoteService = quoteService;
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

	@Override
	@Transactional(readOnly = true)
	public boolean visibleToCaller(long invitationId) {
		return quoteService.inScope(CurrentUser.get(), invitationId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean fileVisibleToCaller(long invitationId, long documentId) {
		return quoteService.fileVisible(CurrentUser.get(), invitationId, documentId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean replaceOpenForCaller(long invitationId) {
		return quoteService.uploadOpenForCaller(CurrentUser.get(), invitationId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean supplierInvitedToRfq(long rfqId) {
		CurrentUser me = CurrentUser.get();
		// M3: only the supplier roles that actually work quotes (same set as QuoteService), not every supplier role.
		if (me.companyType() != CompanyType.SUPPLIER || !QuoteService.SUPPLIER_QUOTERS.contains(me.role())) {
			return false;
		}
		return invitations.findByRfqIdOrderById(rfqId).stream().anyMatch(i -> i.supplierCompanyId == me.companyId());
	}

}
