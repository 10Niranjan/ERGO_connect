package com.ergo.connect.quotation.domain;

import static com.ergo.connect.common.security.Role.CUSTOMER_RFQ;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.event.NotificationKind;
import com.ergo.connect.common.event.Notifier;
import com.ergo.connect.common.event.Recipients;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.ModuleAccess;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.ModuleSwitch;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;
import com.ergo.connect.masterdata.api.MasterDataApi;
import com.ergo.connect.masterdata.api.MasterDataApi.Ref;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.CustomerLine;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.CustomerQuoteAction;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.CustomerQuoteContent;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.CustomerQuoteView;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.CustomerQuotes;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.CustomerQuotesView;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.ErgoCustomerQuoteView;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.ErgoCustomerQuotes;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.ErgoLineBasis;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.ErgoQuoteForm;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.ErgoQuoteTerms;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.GenerateForm;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.GenerateLine;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.ResponseForm;
import com.ergo.connect.quotation.domain.CustomerQuoteDtos.ResponseStatus;
import com.ergo.connect.quotation.domain.QuoteDtos.Named;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteData;
import com.ergo.connect.rfq.api.RfqApi;
import com.ergo.connect.rfq.api.RfqApi.CustomerResponse;
import com.ergo.connect.rfq.api.RfqApi.CustomerRfqRef;
import com.ergo.connect.rfq.api.RfqApi.ItemRef;
import com.ergo.connect.rfq.api.RfqApi.RfqRef;

import tools.jackson.databind.json.JsonMapper;

/**
 * The quotation ERGO generates for the customer and the customer's answer (SRS §5.3, §12.7.2, §13.3.9-13.3.10, §14.4;
 * flow doc §4-5). "Generate quotation for customer" turns the accepted supplier quotes of the chosen parts into ERGO's
 * own quotation, a line per part in the customer's format: a supplier's quotation is never forwarded, the customer
 * sees no supplier and no margin, and both the supplier quotes and the generated quotation are kept. The customer
 * answers approve / correction required / reject / on hold, each with a comment; on hold is answered again.
 */
@Service
@Transactional
public class CustomerQuoteService {

	/** The role sheet (ModuleAccess), Quotation column: these customer roles see ERGO's quotations and answer them. */
	private static final Set<Role> CUSTOMER_RESPONDERS = ModuleAccess.actors(Module.QUOTATION, CompanyType.CUSTOMER);

	private final CustomerQuoteRepository customerQuotes;

	private final CustomerQuoteLineRepository lines;

	private final InvitationRepository invitations;

	private final SupplierQuoteRepository quotes;

	private final QuoteReader reader;

	private final QuoteService quoteService;

	private final RfqApi rfqs;

	private final Directory directory;

	private final MasterDataApi masterData;

	private final AuditLogService audit;

	private final JsonMapper json;

	private final Notifier notifier;

	CustomerQuoteService(CustomerQuoteRepository customerQuotes, CustomerQuoteLineRepository lines,
			InvitationRepository invitations, SupplierQuoteRepository quotes, QuoteReader reader,
			QuoteService quoteService, RfqApi rfqs, Directory directory, MasterDataApi masterData,
			AuditLogService audit, JsonMapper json, Notifier notifier) {
		this.notifier = notifier;
		this.customerQuotes = customerQuotes;
		this.lines = lines;
		this.invitations = invitations;
		this.quotes = quotes;
		this.reader = reader;
		this.quoteService = quoteService;
		this.rfqs = rfqs;
		this.directory = directory;
		this.masterData = masterData;
		this.audit = audit;
		this.json = json;
	}

	/** ERGO: every quotation generated on the RFQ with its basis and margin. A customer: what was generated for it. */
	@Transactional(readOnly = true)
	public CustomerQuotesView list(long rfqId) {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() == CompanyType.CUSTOMER) {
			CustomerRfqRef rfq = rfqs.forCustomer(rfqId); // 404 unless it is this customer's RFQ
			List<CustomerQuote> sent = customerQuotes.findByRfqIdAndCustomerCompanyIdOrderByIdDesc(rfqId, me.companyId());
			Map<Long, UserRef> users = directory.users(ids(sent.stream().map(q -> q.responseByUserId)));
			return new CustomerQuotes("CUSTOMER", sent.stream().map(q -> new CustomerQuoteView(q.id, q.versionNo,
					q.submittedAt, content(q), q.responseStatus, QuoteService.userName(users, q.responseByUserId),
					q.responseAt, q.responseComment, actions(me, q, rfq))).toList());
		}
		rfqs.forErgo(rfqId); // 404 outside the ERGO user's RFQ scope
		List<CustomerQuote> sent = customerQuotes.findByRfqIdOrderByIdDesc(rfqId);
		Map<Long, List<CustomerQuoteLine>> linesBySubmission = sent.isEmpty() ? Map.of()
				: lines.findBySubmissionIdIn(sent.stream().map(q -> q.id).toList()).stream()
					.collect(Collectors.groupingBy(l -> l.submissionId));
		Map<Long, Invitation> basis = invitations.findByRfqIdOrderById(rfqId).stream()
			.collect(Collectors.toMap(i -> i.id, Function.identity()));
		Map<Long, CompanyRef> suppliers = directory
			.companies(basis.values().stream().map(i -> i.supplierCompanyId).collect(Collectors.toSet()));
		Map<Long, UserRef> users = directory.users(ids(sent.stream()
			.flatMap(q -> Stream.of(q.submittedByUserId, q.responseByUserId))));
		return new ErgoCustomerQuotes("ERGO", sent.stream().map(q -> {
			CustomerQuoteContent content = content(q);
			Map<Long, String> partNumbers = content.lines().stream()
				.collect(Collectors.toMap(CustomerLine::rfqItemId, CustomerLine::partNumber));
			List<ErgoLineBasis> lineBasis = linesBySubmission.getOrDefault(q.id, List.of()).stream().map(l -> {
				CompanyRef s = suppliers.get(basis.get(l.assignmentId).supplierCompanyId);
				return new ErgoLineBasis(l.assignmentId, l.rfqItemId, partNumbers.get(l.rfqItemId),
						new Named(s.id(), s.code(), s.name()), l.supplierQuoteVersion, l.marginAmountHidden,
						l.marginPercentageHidden);
			}).toList();
			return new ErgoCustomerQuoteView(q.id, q.versionNo, content, lineBasis,
					QuoteService.userName(users, q.submittedByUserId), q.submittedAt, q.responseStatus,
					QuoteService.userName(users, q.responseByUserId), q.responseAt, q.responseComment);
		}).toList());
	}

	/**
	 * Flow doc §4 "Generate Quotation for Customer": for the chosen parts, whose supplier quotes ERGO has accepted (or
	 * the customer sent back for correction), the system generates the customer-facing quotation from ERGO's own
	 * terms and sends it to the customer. What the customer sees is frozen now; the parts move to "with customer".
	 */
	public CustomerQuotesView generate(long rfqId, GenerateForm form) {
		CurrentUser me = CurrentUser.get();
		RfqRef rfq = rfqs.forErgo(rfqId);
		if (!QuoteService.EVALUATORS.contains(me.role())) {
			throw ApiException.forbidden("Only ERGO's approving RFQ roles generate the customer quotation");
		}
		if (!rfq.quotingOpen()) {
			throw ApiException.conflict("A quotation can be generated once the RFQ is out with suppliers");
		}
		if (customerQuotes.existsByRfqIdAndResponseStatusIn(rfqId, ResponseStatus.OPEN)) {
			throw ApiException.conflict("An earlier quotation is still waiting for the customer's answer");
		}
		Map<Long, ItemRef> parts = rfq.items().stream().collect(Collectors.toMap(ItemRef::id, Function.identity()));
		Set<Long> seen = new HashSet<>();
		List<Invitation> chosen = new ArrayList<>();
		for (GenerateLine line : form.lines()) {
			Invitation invitation = invitations.findById(line.quoteId()).filter(i -> i.rfqId == rfqId)
				.orElseThrow(() -> ApiException.notFound("Quote"));
			if (!seen.add(invitation.id)) {
				throw ApiException.unprocessable("A part is listed twice");
			}
			if (!QuoteStatus.READY_FOR_CUSTOMER.contains(invitation.status)) {
				throw ApiException.conflict("Only parts whose quote ERGO has accepted can be quoted to the customer");
			}
			chosen.add(invitation);
		}
		Map<Long, SupplierQuote> quoteByInvitation = reader.byInvitation(chosen);
		Map<Long, QuoteData> submitted = reader.lastSubmitted(quoteByInvitation.values());
		LocalDate today = LocalDate.now();

		CustomerQuote sent = new CustomerQuote();
		sent.rfqId = rfqId;
		sent.customerCompanyId = rfq.customerCompanyId();
		sent.versionNo = (int) customerQuotes.countByRfqId(rfqId) + 1;
		sent.submittedByUserId = me.userId();
		List<CustomerLine> customerLines = new ArrayList<>();
		List<CustomerQuoteLine> toSave = new ArrayList<>();
		for (GenerateLine form1 : form.lines()) {
			Invitation invitation = chosen.stream().filter(i -> i.id.equals(form1.quoteId())).findFirst().orElseThrow();
			SupplierQuote supplierQuote = quoteByInvitation.get(invitation.id);
			QuoteData supplierData = supplierQuote == null ? null : submitted.get(supplierQuote.id);
			if (supplierData == null) {
				throw ApiException.conflict("The part has no submitted supplier quote to base the quotation on");
			}
			ItemRef part = parts.get(invitation.rfqItemId);
			CustomerQuoteLine line = new CustomerQuoteLine();
			line.assignmentId = invitation.id;
			line.rfqItemId = invitation.rfqItemId;
			line.supplierQuoteVersion = supplierQuote.currentVersion;
			ErgoQuoteTerms terms = ergoQuote(line, form1.ergoQuote(), supplierData, today);
			customerLines.add(new CustomerLine(part.id(), part.lineNo(), part.partNumber(), part.revision(),
					part.description(), terms));
			toSave.add(line);
		}
		customerLines.sort((a, b) -> Integer.compare(a.lineNo(), b.lineNo()));
		sent.customerView = json.writeValueAsString(
				new CustomerQuoteContent(QuoteService.text(form.remarks()), customerLines));
		ModuleSwitch.requireWritable(Module.QUOTATION, Module.RFQ); // both locked until commit: no switch change mid-operation
		customerQuotes.save(sent);
		for (CustomerQuoteLine line : toSave) {
			line.submissionId = sent.id;
			lines.save(line);
		}
		for (Invitation invitation : chosen) {
			invitation.status = QuoteStatus.WITH_CUSTOMER;
		}
		quoteService.progress(rfqId);
		notifier.send(NotificationKind.CUSTOMER_QUOTE_SENT, "rfq", rfqId, rfq.number(), null,
				Recipients.company(rfq.customerCompanyId(), CUSTOMER_RESPONDERS.toArray(Role[]::new)));
		return list(rfqId);
	}

	/** SRS §14.4 / flow doc §5: approve, correction required, reject or on hold - each with a comment. */
	public CustomerQuotesView respond(long id, ResponseForm form) {
		CurrentUser me = CurrentUser.get();
		CustomerQuote quote = customerQuotes.findById(id)
			.filter(q -> me.companyType() == CompanyType.CUSTOMER && q.customerCompanyId == me.companyId()
					&& CUSTOMER_RESPONDERS.contains(me.role()))
			.orElseThrow(() -> ApiException.notFound("Quote"));
		CustomerRfqRef rfqRef = rfqs.forCustomer(quote.rfqId); // the RFQ's own customer scope applies too
		if (!ResponseStatus.OPEN.contains(quote.responseStatus)) {
			throw ApiException.conflict("This quotation has already been answered");
		}
		if (!rfqRef.awaitingAnswer()) {
			throw ApiException.conflict("This RFQ is no longer waiting for your answer");
		}
		if (quote.responseStatus == ResponseStatus.ON_HOLD && form.response() == CustomerResponse.ON_HOLD) {
			throw ApiException.conflict("This quotation is already on hold");
		}
		if (form.response() != CustomerResponse.REVISION_REQUESTED
				&& !ModuleAccess.canApprove(me.role(), Module.QUOTATION)) {
			throw ApiException.forbidden("Only the customer's approving role can approve, reject or hold a quotation; "
					+ "you can request a correction");
		}
		String comment = QuoteService.text(form.comment());
		if (comment == null) { // flow doc §5: every decision carries a comment
			throw ApiException.unprocessable(switch (form.response()) {
				case APPROVED -> "Add a comment with your approval";
				case DECLINED -> "Tell ERGO why you reject";
				case ON_HOLD -> "Tell ERGO why the decision is on hold";
				case REVISION_REQUESTED -> "Tell ERGO what should be corrected";
			});
		}
		audit.reason(comment);
		ModuleSwitch.requireWritable(Module.QUOTATION, Module.RFQ); // both locked until commit: no switch change mid-operation
		ResponseStatus answer = ResponseStatus.valueOf(form.response().name());
		quote.responseStatus = answer;
		quote.responseByUserId = me.userId();
		quote.responseAt = Instant.now();
		quote.responseComment = comment;
		Set<Long> assignmentIds = lines.findBySubmissionIdIn(List.of(quote.id)).stream().map(l -> l.assignmentId)
			.collect(Collectors.toSet());
		for (Invitation invitation : invitations.findByIdIn(assignmentIds)) {
			invitation.status = answer.partStatus();
		}
		quoteService.progress(quote.rfqId);
		notifier.send(NotificationKind.CUSTOMER_QUOTE_RESPONDED, "rfq", quote.rfqId, rfqRef.number(),
				switch (form.response()) {
					case APPROVED -> "Approved";
					case ON_HOLD -> "On hold";
					case REVISION_REQUESTED -> "Correction required";
					case DECLINED -> "Rejected";
				}, Recipients.ergo(Role.ERGO_ADMIN, Role.ERGO_RFQ));
		return list(quote.rfqId);
	}

	/**
	 * ERGO's terms for one part of the quotation. The margin on the supplier's production piece price is worked out
	 * here with the rate of the day and frozen with the line; it stays empty when ERGO has no rate for the pair.
	 */
	private ErgoQuoteTerms ergoQuote(CustomerQuoteLine line, ErgoQuoteForm terms, QuoteData supplier, LocalDate today) {
		masterData.requireActive(Ref.CURRENCY, terms.currencyId(), "Currency");
		masterData.requireActive(Ref.PAYMENT_TERM, terms.paymentTermId(), "Payment term");
		masterData.requireActive(Ref.INCOTERM, terms.incotermId(), "Incoterm");
		if (terms.validityDate().isBefore(today)) {
			throw ApiException.unprocessable("The validity date is in the past");
		}
		line.currencyId = terms.currencyId();
		line.quotedProductionPiecePrice = terms.productionPiecePrice();
		line.quotedPpapPiecePrice = terms.ppapPiecePrice();
		line.quotedToolingCost = terms.toolingCost();
		masterData.rate(supplier.currency().id(), terms.currencyId(), today).ifPresent(rate -> {
			BigDecimal cost = supplier.productionPiecePrice().multiply(rate);
			line.marginAmountHidden = terms.productionPiecePrice().subtract(cost).setScale(4, RoundingMode.HALF_UP);
			if (terms.productionPiecePrice().signum() > 0) {
				line.marginPercentageHidden = line.marginAmountHidden.multiply(BigDecimal.valueOf(100))
					.divide(terms.productionPiecePrice(), 2, RoundingMode.HALF_UP);
			}
		});
		return new ErgoQuoteTerms(named(Ref.CURRENCY, terms.currencyId()), terms.productionPiecePrice(),
				terms.ppapPiecePrice(), terms.toolingCost(), named(Ref.PAYMENT_TERM, terms.paymentTermId()),
				named(Ref.INCOTERM, terms.incotermId()), terms.moq(), terms.materialLeadTimeWeeks(),
				terms.toolingLeadTimeDays(), terms.sampleLeadTimeDays(), terms.productionLeadTimeDays(),
				terms.validityDate());
	}

	private Set<CustomerQuoteAction> actions(CurrentUser me, CustomerQuote quote, CustomerRfqRef rfq) {
		if (!CUSTOMER_RESPONDERS.contains(me.role()) || !ResponseStatus.OPEN.contains(quote.responseStatus)
				|| !rfq.awaitingAnswer()) {
			return EnumSet.noneOf(CustomerQuoteAction.class);
		}
		// "A" roles may only ask for a correction; approving, rejecting or holding is the "CO" role's decision.
		if (!ModuleAccess.canApprove(me.role(), Module.QUOTATION)) {
			return EnumSet.of(CustomerQuoteAction.REQUEST_REVISION);
		}
		Set<CustomerQuoteAction> all = EnumSet.allOf(CustomerQuoteAction.class);
		if (quote.responseStatus == ResponseStatus.ON_HOLD) {
			all.remove(CustomerQuoteAction.PUT_ON_HOLD); // already on hold: only a final answer remains
		}
		return all;
	}

	private CustomerQuoteContent content(CustomerQuote quote) {
		return json.readValue(quote.customerView, CustomerQuoteContent.class);
	}

	private Named named(Ref ref, Long id) {
		MasterDataApi.Named n = id == null ? null : masterData.describe(ref, Set.of(id)).get(id);
		return n == null ? null : new Named(n.id(), n.code(), n.name());
	}

	private static List<Long> ids(Stream<Long> ids) {
		return ids.filter(Objects::nonNull).distinct().toList();
	}

}
