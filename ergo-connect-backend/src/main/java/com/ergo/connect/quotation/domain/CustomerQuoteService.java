package com.ergo.connect.quotation.domain;

import static com.ergo.connect.common.security.Role.CUSTOMER_KAM;
import static com.ergo.connect.common.security.Role.CUSTOMER_MANAGEMENT;
import static com.ergo.connect.common.security.Role.CUSTOMER_RFQ;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
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
import com.ergo.connect.quotation.domain.ComparisonDtos.CustomerProcessStep;
import com.ergo.connect.quotation.domain.ComparisonDtos.CustomerQuoteAction;
import com.ergo.connect.quotation.domain.ComparisonDtos.CustomerQuoteContent;
import com.ergo.connect.quotation.domain.ComparisonDtos.CustomerQuoteView;
import com.ergo.connect.quotation.domain.ComparisonDtos.CustomerQuotes;
import com.ergo.connect.quotation.domain.ComparisonDtos.CustomerQuotesView;
import com.ergo.connect.quotation.domain.ComparisonDtos.CustomerSupplierQuote;
import com.ergo.connect.quotation.domain.ComparisonDtos.ErgoCustomerQuoteView;
import com.ergo.connect.quotation.domain.ComparisonDtos.ErgoCustomerQuotes;
import com.ergo.connect.quotation.domain.ComparisonDtos.ErgoQuoteForm;
import com.ergo.connect.quotation.domain.ComparisonDtos.ErgoQuoteTerms;
import com.ergo.connect.quotation.domain.ComparisonDtos.QuoteType;
import com.ergo.connect.quotation.domain.ComparisonDtos.ResponseForm;
import com.ergo.connect.quotation.domain.ComparisonDtos.ResponseStatus;
import com.ergo.connect.quotation.domain.ComparisonDtos.SendForm;
import com.ergo.connect.quotation.domain.QuoteDtos.Named;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteAction;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteData;
import com.ergo.connect.quotation.domain.QuoteService.RfqState;
import com.ergo.connect.rfq.api.RfqApi;
import com.ergo.connect.rfq.api.RfqApi.CustomerResponse;
import com.ergo.connect.rfq.api.RfqApi.CustomerRfqRef;
import com.ergo.connect.rfq.api.RfqApi.RfqRef;

import tools.jackson.databind.json.JsonMapper;

/**
 * Quotes ERGO sends the customer and the customer's answer (SRS §5.3, §12.7.2, §13.3.9-13.3.10, §14.4). Client
 * decision: ERGO forwards one supplier's quote as-is (the customer sees that supplier, its prices and cost breakdown)
 * or sends its own quote (no supplier, margin hidden). Customers see only what was sent to their own company.
 */
@Service
@Transactional
public class CustomerQuoteService {

	/** The role sheet (ModuleAccess), Quotation column: these customer roles see ERGO's quotes and answer them. */
	private static final Set<Role> CUSTOMER_RESPONDERS = ModuleAccess.actors(Module.QUOTATION, CompanyType.CUSTOMER);

	private final CustomerQuoteRepository customerQuotes;

	private final InvitationRepository invitations;

	private final SupplierQuoteRepository quotes;

	private final QuoteReader reader;

	private final RfqApi rfqs;

	private final Directory directory;

	private final MasterDataApi masterData;

	private final AuditLogService audit;

	private final JsonMapper json;

	private final Notifier notifier;

	CustomerQuoteService(CustomerQuoteRepository customerQuotes, InvitationRepository invitations,
			SupplierQuoteRepository quotes, QuoteReader reader, RfqApi rfqs, Directory directory,
			MasterDataApi masterData, AuditLogService audit, JsonMapper json, Notifier notifier) {
		this.notifier = notifier;
		this.customerQuotes = customerQuotes;
		this.invitations = invitations;
		this.quotes = quotes;
		this.reader = reader;
		this.rfqs = rfqs;
		this.directory = directory;
		this.masterData = masterData;
		this.audit = audit;
		this.json = json;
	}

	/** ERGO: every quote sent on the RFQ with its basis and margin. A customer: what was sent to its company. */
	@Transactional(readOnly = true)
	public CustomerQuotesView list(long rfqId) {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() == CompanyType.CUSTOMER) {
			CustomerRfqRef rfq = rfqs.forCustomer(rfqId); // 404 unless it is this customer's RFQ
			List<CustomerQuote> sent = customerQuotes.findByRfqIdAndCustomerCompanyIdOrderByIdDesc(rfqId, me.companyId());
			Map<Long, UserRef> users = directory.users(ids(sent.stream().map(q -> q.responseByUserId)));
			return new CustomerQuotes("CUSTOMER", sent.stream().map(q -> new CustomerQuoteView(q.id, q.submittedAt,
					content(q), q.responseStatus, QuoteService.userName(users, q.responseByUserId), q.responseAt,
					q.responseComment, actions(me, q, rfq))).toList());
		}
		rfqs.forErgo(rfqId); // 404 outside the ERGO user's RFQ scope
		List<CustomerQuote> sent = customerQuotes.findByRfqIdOrderByIdDesc(rfqId);
		Map<Long, Invitation> basis = invitations.findByRfqIdOrderById(rfqId).stream()
			.collect(Collectors.toMap(i -> i.id, Function.identity()));
		Map<Long, CompanyRef> suppliers = directory.companies(sent.stream()
			.map(q -> basis.get(q.assignmentId).supplierCompanyId).toList());
		Map<Long, UserRef> users = directory.users(ids(sent.stream()
			.flatMap(q -> Stream.of(q.submittedByUserId, q.responseByUserId))));
		return new ErgoCustomerQuotes("ERGO", sent.stream().map(q -> {
			CompanyRef s = suppliers.get(basis.get(q.assignmentId).supplierCompanyId);
			return new ErgoCustomerQuoteView(q.id, q.quoteType, q.assignmentId, new Named(s.id(), s.code(), s.name()),
					q.supplierQuoteVersion, content(q), q.marginAmountHidden, q.marginPercentageHidden,
					QuoteService.userName(users, q.submittedByUserId), q.submittedAt, q.responseStatus,
					QuoteService.userName(users, q.responseByUserId), q.responseAt, q.responseComment);
		}).toList());
	}

	/**
	 * Sends the customer a quote based on the supplier quote ERGO selected for it: the supplier's last submitted version
	 * as-is, or ERGO's own terms. What the customer sees is frozen now; the RFQ moves to Sent to Customer.
	 */
	public CustomerQuotesView send(long rfqId, SendForm form) {
		CurrentUser me = CurrentUser.get();
		RfqRef rfq = rfqs.forErgo(rfqId);
		Invitation invitation = invitations.findById(form.quoteId()).filter(i -> i.rfqId == rfqId)
			.orElseThrow(() -> ApiException.notFound("Quote"));
		if (!QuoteService.allowed(QuoteAction.SEND_TO_CUSTOMER, me, invitation, RfqState.of(rfq))) {
			throw ApiException.conflict("Only a quote selected for the customer can be sent, once quotes are in and no "
					+ "earlier quote is waiting for the customer's answer");
		}
		SupplierQuote quote = quotes.findByAssignmentId(invitation.id).orElseThrow();
		QuoteData submitted = reader.lastSubmitted(List.of(quote)).get(quote.id);
		LocalDate today = LocalDate.now();
		boolean sentTheOtherWayBefore = customerQuotes.findByRfqIdOrderByIdDesc(rfqId).stream()
			.anyMatch(q -> q.assignmentId.equals(invitation.id) && q.quoteType != form.type());
		if (sentTheOtherWayBefore) {
			throw ApiException.conflict("This supplier's quote was already sent to the customer the other way "
					+ "round (as-is vs. an ERGO quote). Sending it the other way now could reveal the margin or the "
					+ "supplier's identity; keep the same type, or base the new quote on a different supplier.");
		}

		CustomerQuote sent = new CustomerQuote();
		sent.rfqId = rfqId;
		sent.customerCompanyId = rfq.customerCompanyId();
		sent.quoteType = form.type();
		sent.assignmentId = invitation.id;
		sent.supplierQuoteVersion = quote.currentVersion;
		sent.submittedByUserId = me.userId();
		String remarks = QuoteService.text(form.remarks());
		CustomerQuoteContent content = switch (form.type()) {
			case SUPPLIER_QUOTE -> {
				if (submitted.validityDate().isBefore(today)) {
					throw ApiException.unprocessable("The supplier's quote expired on " + submitted.validityDate()
							+ ". Ask the supplier for a revision first.");
				}
				sent.currencyId = submitted.currency().id();
				sent.quotedProductionPiecePrice = submitted.productionPiecePrice();
				sent.quotedPpapPiecePrice = submitted.ppapPiecePrice();
				sent.quotedToolingCost = submitted.toolingCostTotal();
				CompanyRef supplier = directory.company(invitation.supplierCompanyId).orElseThrow();
				yield new CustomerQuoteContent(QuoteType.SUPPLIER_QUOTE, supplier.name(), customerView(submitted), null,
						remarks);
			}
			case ERGO_QUOTE -> {
				ErgoQuoteForm terms = form.ergoQuote();
				if (terms == null) {
					throw ApiException.unprocessable("Fill in ERGO's quote");
				}
				yield new CustomerQuoteContent(QuoteType.ERGO_QUOTE, null, null, ergoQuote(sent, terms, submitted, today),
						remarks);
			}
		};
		sent.customerView = json.writeValueAsString(content);
		ModuleSwitch.requireWritable(Module.QUOTATION, Module.RFQ); // both locked until commit: no switch change mid-operation
		rfqs.sentToCustomer(rfqId); // 409 unless the RFQ is ready for a quote to the customer
		customerQuotes.save(sent);
		notifier.send(NotificationKind.CUSTOMER_QUOTE_SENT, "rfq", rfqId, rfq.number(), null,
				Recipients.company(rfq.customerCompanyId(), CUSTOMER_RESPONDERS.toArray(Role[]::new)));
		return list(rfqId);
	}

	/** SRS §14.4: approve, or request a revision / decline with a comment. Once only. */
	public CustomerQuotesView respond(long id, ResponseForm form) {
		CurrentUser me = CurrentUser.get();
		CustomerQuote quote = customerQuotes.findById(id)
			.filter(q -> me.companyType() == CompanyType.CUSTOMER && q.customerCompanyId == me.companyId()
					&& CUSTOMER_RESPONDERS.contains(me.role()))
			.orElseThrow(() -> ApiException.notFound("Quote"));
		CustomerRfqRef rfqRef = rfqs.forCustomer(quote.rfqId); // the RFQ's own customer scope applies too
		if (quote.responseStatus != ResponseStatus.WAITING) {
			throw ApiException.conflict("This quote has already been answered");
		}
		if (form.response() != CustomerResponse.REVISION_REQUESTED
				&& !ModuleAccess.canApprove(me.role(), Module.QUOTATION)) {
			throw ApiException.forbidden("Only the customer's approving role can approve or decline a quote; "
					+ "you can request a revision");
		}
		String comment = QuoteService.text(form.comment());
		if (form.response() != CustomerResponse.APPROVED && comment == null) {
			throw ApiException.unprocessable(form.response() == CustomerResponse.DECLINED
					? "Tell ERGO why you decline" : "Tell ERGO what should change");
		}
		if (comment != null) {
			audit.reason(comment);
		}
		quote.responseStatus = ResponseStatus.valueOf(form.response().name());
		quote.responseByUserId = me.userId();
		quote.responseAt = Instant.now();
		quote.responseComment = comment;
		ModuleSwitch.requireWritable(Module.QUOTATION, Module.RFQ); // both locked until commit: no switch change mid-operation
		rfqs.customerResponded(quote.rfqId, form.response()); // 409 when the RFQ no longer waits (e.g. closed)
		notifier.send(NotificationKind.CUSTOMER_QUOTE_RESPONDED, "rfq", quote.rfqId, rfqRef.number(),
				switch (form.response()) {
					case APPROVED -> "Approved";
					case REVISION_REQUESTED -> "Revision requested";
					case DECLINED -> "Declined";
				}, Recipients.ergo(Role.ERGO_ADMIN, Role.ERGO_RFQ));
		return list(quote.rfqId);
	}

	/**
	 * ERGO's terms for the customer. The margin on the supplier's production piece price is worked out here with the
	 * rate of the day and frozen with the quote; it stays empty when ERGO has no rate for the pair.
	 */
	/** Only what the client decision promises the customer: name (carried separately), prices and cost breakdown. */
	private static CustomerSupplierQuote customerView(QuoteData q) {
		return new CustomerSupplierQuote(q.validityDate(), q.currency(), q.incoterm(), q.paymentTerm(),
				q.toolingCostTotal(), q.ppapPiecePrice(), q.productionPiecePrice(), q.costs(), q.moq(),
				q.materialLeadTimeWeeks(), q.toolingLeadTimeDays(), q.sampleLeadTimeDays(), q.productionLeadTimeDays(),
				q.processSteps().stream()
					.map(step -> new CustomerProcessStep(step.process() == null ? null : step.process().name(),
							step.stepName(), step.cycleTimeSeconds(),
							step.sourcingType() == null ? null : step.sourcingType().name()))
					.toList());
	}

	private ErgoQuoteTerms ergoQuote(CustomerQuote sent, ErgoQuoteForm terms, QuoteData supplier, LocalDate today) {
		masterData.requireActive(Ref.CURRENCY, terms.currencyId(), "Currency");
		masterData.requireActive(Ref.PAYMENT_TERM, terms.paymentTermId(), "Payment term");
		masterData.requireActive(Ref.INCOTERM, terms.incotermId(), "Incoterm");
		if (terms.validityDate().isBefore(today)) {
			throw ApiException.unprocessable("The validity date is in the past");
		}
		sent.currencyId = terms.currencyId();
		sent.quotedProductionPiecePrice = terms.productionPiecePrice();
		sent.quotedPpapPiecePrice = terms.ppapPiecePrice();
		sent.quotedToolingCost = terms.toolingCost();
		masterData.rate(supplier.currency().id(), terms.currencyId(), today).ifPresent(rate -> {
			BigDecimal cost = supplier.productionPiecePrice().multiply(rate);
			sent.marginAmountHidden = terms.productionPiecePrice().subtract(cost).setScale(4, RoundingMode.HALF_UP);
			if (terms.productionPiecePrice().signum() > 0) {
				sent.marginPercentageHidden = sent.marginAmountHidden.multiply(BigDecimal.valueOf(100))
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
		if (!CUSTOMER_RESPONDERS.contains(me.role()) || quote.responseStatus != ResponseStatus.WAITING
				|| !rfq.awaitingAnswer()) {
			return EnumSet.noneOf(CustomerQuoteAction.class);
		}
		// "A" roles may only ask for a revision; approving or declining is the "CO" role's final decision.
		return ModuleAccess.canApprove(me.role(), Module.QUOTATION) ? EnumSet.allOf(CustomerQuoteAction.class)
				: EnumSet.of(CustomerQuoteAction.REQUEST_REVISION);
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
