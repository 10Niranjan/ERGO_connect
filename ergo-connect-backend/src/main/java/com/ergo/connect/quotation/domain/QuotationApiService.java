package com.ergo.connect.quotation.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.ModuleAccess;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.Role;
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
		// The documents / communication probes concern the quote itself: a supplier role that only has RFQ access
		// (view of the assigned RFQ) is not in its scope.
		CurrentUser me = CurrentUser.get();
		return ModuleAccess.canView(me.role(), Module.QUOTATION) && quoteService.inScope(me, invitationId);
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Optional<com.ergo.connect.common.record.RecordParties> partiesVisibleToCaller(long invitationId) {
		CurrentUser me = CurrentUser.get();
		if (!ModuleAccess.canView(me.role(), Module.QUOTATION) || !quoteService.inScope(me, invitationId)) {
			return java.util.Optional.empty();
		}
		return invitations.findById(invitationId).map(i -> new com.ergo.connect.common.record.RecordParties(null, i.supplierCompanyId));
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.List<com.ergo.connect.common.event.OverdueItem> overdueItems(java.time.LocalDate today) {
		return quoteService.overdue(today);
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
		// Same set as QuoteService.SUPPLIER_RFQ_VIEWERS: the supplier roles with RFQ access see the ERGO-shared RFQ files.
		if (me.companyType() != CompanyType.SUPPLIER || !ModuleAccess.canView(me.role(), ModuleAccess.Module.RFQ)) {
			return false;
		}
		return invitations.findByRfqIdOrderById(rfqId).stream().anyMatch(i -> i.supplierCompanyId == me.companyId());
	}

	@Override
	@Transactional(readOnly = true)
	public List<SupplierQuoteItem> dashboardItems() {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() != CompanyType.SUPPLIER || !QuoteService.SUPPLIER_QUOTERS.contains(me.role())) {
			return List.of();
		}
		LocalDate today = LocalDate.now();
		return quoteService.quoteRows().stream()
			.map(r -> new SupplierQuoteItem(r.id(), r.rfqNumber(), r.partNumber(), r.status().name(),
					r.responseDueDate(), r.quotingOpen() && r.responseDueDate() != null
							&& r.responseDueDate().isBefore(today) && QuoteStatus.EDITABLE.contains(r.status())))
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public int pendingSubmissionCount() {
		Role role = CurrentUser.get().role();
		if (role != Role.ERGO_ADMIN && role != Role.ERGO_MANAGEMENT && role != Role.ERGO_RFQ) {
			return 0;
		}
		return quoteService.pendingSubmissionCount();
	}

}
