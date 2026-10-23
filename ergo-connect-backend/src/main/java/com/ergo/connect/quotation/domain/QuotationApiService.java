package com.ergo.connect.quotation.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.ModuleAccess;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.ModuleSwitch;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.quotation.api.QuotationApi;
import com.ergo.connect.rfq.api.RfqApi;
import com.ergo.connect.rfq.api.RfqApi.SupplierRfqItem;

@Service
@Transactional
class QuotationApiService implements QuotationApi {

	private final CustomerQuoteLineRepository lines;

	private final CustomerQuoteRepository customerQuotes;

	private final InvitationRepository invitations;

	private final SupplierQuoteRepository quotes;

	private final Directory directory;

	private final QuoteService quoteService;

	private final RfqApi rfqs;

	QuotationApiService(CustomerQuoteLineRepository lines, CustomerQuoteRepository customerQuotes,
			InvitationRepository invitations, SupplierQuoteRepository quotes, Directory directory,
			QuoteService quoteService, RfqApi rfqs) {
		this.lines = lines;
		this.customerQuotes = customerQuotes;
		this.invitations = invitations;
		this.quotes = quotes;
		this.directory = directory;
		this.quoteService = quoteService;
		this.rfqs = rfqs;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ApprovedPart> approvedParts() {
		CurrentUser me = CurrentUser.get();
		if (!ModuleSwitch.readable(Module.QUOTATION) || me.companyType() != CompanyType.ERGO) {
			return List.of(); // a hidden Quotation module reveals no quote, so nothing can start from it
		}
		Set<Long> accounts = me.role() == Role.ERGO_KAM ? directory.accountsOf(me.userId()) : Set.of();
		boolean wide = switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ, ERGO_NPD -> true;
			default -> false;
		};
		if (!wide && accounts.isEmpty()) {
			return List.of();
		}
		List<Invitation> approved = invitations.findByStatusOrderByIdDesc(QuoteStatus.CUSTOMER_APPROVED);
		return approvedParts(approved).stream().filter(p -> wide || accounts.contains(p.customerCompanyId())).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ApprovedPart> approvedPart(long rfqItemId) {
		return approvedParts().stream().filter(p -> p.rfqItemId() == rfqItemId).findFirst();
	}

	@Override
	@Transactional(readOnly = true)
	public List<CustomerApprovedPart> customerApprovedParts() {
		CurrentUser me = CurrentUser.get();
		if (!ModuleSwitch.readable(Module.QUOTATION) || me.companyType() != CompanyType.CUSTOMER) {
			return List.of();
		}
		// the customer's own live parts (RFQ record scope), so only the caller's own company's parts are ever looked at
		Map<Long, RfqApi.CustomerPart> own = rfqs.customerParts().stream()
			.collect(Collectors.toMap(RfqApi.CustomerPart::itemId, Function.identity()));
		if (own.isEmpty()) {
			return List.of();
		}
		return invitations.findByRfqIdIn(own.values().stream().map(RfqApi.CustomerPart::rfqId).collect(Collectors.toSet()))
			.stream()
			.filter(i -> i.status == QuoteStatus.CUSTOMER_APPROVED && own.containsKey(i.rfqItemId))
			.map(i -> {
				RfqApi.CustomerPart part = own.get(i.rfqItemId);
				CustomerQuoteLine line = lines.findFirstByAssignmentIdOrderByIdDesc(i.id).orElseThrow();
				return new CustomerApprovedPart(part.rfqId(), part.rfqNumber(), part.itemId(), part.partNumber(),
						customerQuotes.findById(line.submissionId).orElseThrow().responseAt);
			})
			.toList();
	}

	private List<ApprovedPart> approvedParts(List<Invitation> approved) {
		if (approved.isEmpty()) {
			return List.of();
		}
		Map<Long, CompanyRef> suppliers = directory
			.companies(approved.stream().map(i -> i.supplierCompanyId).collect(Collectors.toSet()));
		Map<Long, SupplierQuote> byInvitation = quotes
			.findByAssignmentIdIn(approved.stream().map(i -> i.id).toList()).stream()
			.collect(Collectors.toMap(q -> q.assignmentId, Function.identity()));
		return approved.stream().map(i -> {
			CustomerQuoteLine line = lines.findFirstByAssignmentIdOrderByIdDesc(i.id).orElseThrow();
			CustomerQuote quote = customerQuotes.findById(line.submissionId).orElseThrow();
			SupplierQuote q = byInvitation.get(i.id);
			return new ApprovedPart(i.id, i.rfqId, i.rfqItemId, quote.customerCompanyId, i.supplierCompanyId,
					suppliers.get(i.supplierCompanyId).name(), q == null ? null : q.quoteReference, quote.responseAt);
		}).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<PartStatusItem> partStatusItems() {
		CurrentUser me = CurrentUser.get();
		if (!ModuleSwitch.readable(Module.QUOTATION) || !ModuleSwitch.readable(Module.RFQ)
				|| !ModuleAccess.canView(me.role(), Module.QUOTATION)) {
			return List.of();
		}
		if (me.companyType() == CompanyType.CUSTOMER) {
			// Built from the customer's own parts, like the RFQ's /parts list, so the two always agree and neither
			// shows how far ERGO has got with distributing the parts.
			List<RfqApi.CustomerPart> own = rfqs.customerParts();
			Map<Long, Invitation> active = own.isEmpty() ? Map.of()
					: invitations.findByRfqIdIn(own.stream().map(RfqApi.CustomerPart::rfqId).collect(Collectors.toSet()))
						.stream()
						.filter(i -> i.status != QuoteStatus.RECALLED)
						.collect(Collectors.toMap(i -> i.rfqItemId, Function.identity()));
			return own.stream()
				.map(p -> new PartStatusItem(p.rfqId(), p.rfqNumber(), p.itemId(), p.partNumber(),
						(active.containsKey(p.itemId()) ? active.get(p.itemId()).status : QuoteStatus.NOT_STARTED)
							.forCustomer()
							.name(),
						null))
				.toList();
		}
		List<Invitation> mine = switch (me.companyType()) {
			case SUPPLIER -> invitations.findBySupplierCompanyIdOrderByIdDesc(me.companyId());
			default -> invitations.findByStatusIn(Set.of(QuoteStatus.values())); // narrowed by RFQ scope below
		};
		mine = mine.stream().filter(i -> i.status != QuoteStatus.RECALLED).toList();
		Map<Long, SupplierRfqItem> parts = rfqs.forSupplierItems(mine.stream().map(i -> i.rfqItemId).toList());
		Map<Long, Boolean> scope = new java.util.HashMap<>();
		Map<Long, CompanyRef> suppliers = me.companyType() == CompanyType.ERGO
				? directory.companies(mine.stream().map(i -> i.supplierCompanyId).collect(Collectors.toSet()))
				: Map.of();
		return mine.stream()
			.filter(i -> parts.containsKey(i.rfqItemId))
			.filter(i -> me.companyType() == CompanyType.SUPPLIER
					|| scope.computeIfAbsent(i.rfqId, rfqs::visibleToCaller))
			.map(i -> {
				SupplierRfqItem p = parts.get(i.rfqItemId);
				return switch (me.companyType()) {
					case SUPPLIER -> new PartStatusItem(i.rfqId, p.rfqNumber(), i.rfqItemId, p.partNumber(),
							i.status.forSupplier().name(), null);
					case CUSTOMER -> new PartStatusItem(i.rfqId, p.rfqNumber(), i.rfqItemId, p.partNumber(),
							i.status.forCustomer().name(), null);
					case ERGO -> new PartStatusItem(i.rfqId, p.rfqNumber(), i.rfqItemId, p.partNumber(),
							i.status.name(), suppliers.get(i.supplierCompanyId).name());
				};
			})
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean visibleToCaller(long invitationId) {
		// The documents / communication probes concern the quote itself: a supplier role that only has RFQ access
		// (view of the assigned RFQ) is not in its scope.
		CurrentUser me = CurrentUser.get();
		return ModuleSwitch.readable(Module.QUOTATION) && ModuleAccess.canView(me.role(), Module.QUOTATION)
				&& quoteService.inScope(me, invitationId);
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Optional<com.ergo.connect.common.record.RecordParties> partiesVisibleToCaller(long invitationId) {
		CurrentUser me = CurrentUser.get();
		if (!ModuleSwitch.readable(Module.QUOTATION) || !ModuleAccess.canView(me.role(), Module.QUOTATION)
				|| !quoteService.inScope(me, invitationId)) {
			return java.util.Optional.empty();
		}
		return invitations.findById(invitationId).map(i -> new com.ergo.connect.common.record.RecordParties(null, i.supplierCompanyId));
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.List<com.ergo.connect.common.event.OverdueItem> overdueItems(java.time.LocalDate today) {
		// A reminder asks someone to act: with either module read-only or hidden nobody can, and a hidden module's
		// items must not be reported at all. The scan is stateless, so it resumes by itself once both accept changes.
		if (!ModuleSwitch.writableNow(Module.QUOTATION, Module.RFQ)) {
			return List.of();
		}
		return quoteService.overdue(today);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean fileVisibleToCaller(long invitationId, long documentId) {
		return ModuleSwitch.readable(Module.QUOTATION)
				&& quoteService.fileVisible(CurrentUser.get(), invitationId, documentId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean replaceOpenForCaller(long invitationId) {
		return ModuleSwitch.writable(Module.QUOTATION)
				&& quoteService.uploadOpenForCaller(CurrentUser.get(), invitationId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean supplierMayReadRfqDocument(long rfqId, long documentId) {
		CurrentUser me = CurrentUser.get();
		// Same set as QuoteService.SUPPLIER_RFQ_VIEWERS: the supplier roles with RFQ access see the ERGO-shared RFQ files.
		if (me.companyType() != CompanyType.SUPPLIER || !ModuleSwitch.readable(Module.QUOTATION)
				|| !ModuleAccess.canView(me.role(), ModuleAccess.Module.RFQ)) {
			return false;
		}
		Set<Long> mine = invitations.findByRfqIdOrderById(rfqId).stream()
			.filter(i -> i.supplierCompanyId == me.companyId() && i.status != QuoteStatus.RECALLED)
			.map(i -> i.rfqItemId)
			.collect(Collectors.toSet());
		return rfqs.supplierDocuments(rfqId, mine).stream().anyMatch(d -> d.id() == documentId);
	}

	@Override
	@Transactional(readOnly = true)
	public List<SupplierQuoteItem> dashboardItems() {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() != CompanyType.SUPPLIER || !QuoteService.SUPPLIER_QUOTERS.contains(me.role())
				|| !ModuleSwitch.readable(Module.QUOTATION)) {
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
		if (role != Role.ERGO_ADMIN && role != Role.ERGO_MANAGEMENT && role != Role.ERGO_RFQ
				|| !ModuleSwitch.readable(Module.QUOTATION) || !ModuleSwitch.readable(Module.RFQ)) {
			return 0;
		}
		return quoteService.pendingSubmissionCount();
	}

}
