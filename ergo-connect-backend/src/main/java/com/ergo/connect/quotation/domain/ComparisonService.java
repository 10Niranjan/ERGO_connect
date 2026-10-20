package com.ergo.connect.quotation.domain;

import static com.ergo.connect.quotation.domain.QuoteStatus.REGRETTED;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.ModuleSwitch;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;
import com.ergo.connect.masterdata.api.MasterDataApi;
import com.ergo.connect.masterdata.api.MasterDataApi.Ref;
import com.ergo.connect.quotation.domain.ComparisonDtos.ComparisonLine;
import com.ergo.connect.quotation.domain.ComparisonDtos.ComparisonView;
import com.ergo.connect.quotation.domain.ComparisonDtos.Converted;
import com.ergo.connect.quotation.domain.ComparisonDtos.EvaluationForm;
import com.ergo.connect.quotation.domain.ComparisonDtos.Scores;
import com.ergo.connect.quotation.domain.ComparisonDtos.SummaryForm;
import com.ergo.connect.quotation.domain.ComparisonDtos.TargetsView;
import com.ergo.connect.quotation.domain.QuoteDtos.Named;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteAction;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteData;
import com.ergo.connect.quotation.domain.QuoteService.RfqState;
import com.ergo.connect.rfq.api.RfqApi;
import com.ergo.connect.rfq.api.RfqApi.RfqRef;
import com.ergo.connect.rfq.api.RfqApi.Targets;

/**
 * SRS §12.7.1 / §17.3 quote comparison: every submitted quote side by side (last submitted version only), converted
 * to the RFQ currency where ERGO has a rate, with ERGO's scores and written assessment. ERGO-only: suppliers and
 * customers never reach it (role layer), and the RFQ's ERGO record scope applies (KAM: own accounts).
 */
@Service
@Transactional
public class ComparisonService {

	private final InvitationRepository invitations;

	private final EvaluationRepository evaluations;

	private final ComparisonRepository comparisons;

	private final QuoteReader reader;

	private final RfqApi rfqs;

	private final Directory directory;

	private final MasterDataApi masterData;

	ComparisonService(InvitationRepository invitations, EvaluationRepository evaluations,
			ComparisonRepository comparisons, QuoteReader reader, RfqApi rfqs, Directory directory,
			MasterDataApi masterData) {
		this.invitations = invitations;
		this.evaluations = evaluations;
		this.comparisons = comparisons;
		this.reader = reader;
		this.rfqs = rfqs;
		this.directory = directory;
		this.masterData = masterData;
	}

	@Transactional(readOnly = true)
	public ComparisonView comparison(long rfqId) {
		CurrentUser me = CurrentUser.get();
		RfqRef rfq = rfqs.forErgo(rfqId);
		List<Invitation> all = invitations.findByRfqIdOrderById(rfqId);
		Map<Long, SupplierQuote> quotes = reader.byInvitation(all);
		// only what suppliers submitted is compared: never drafts, never a supplier that regretted
		List<Invitation> quoted = all.stream()
			.filter(i -> i.status != REGRETTED && quotes.containsKey(i.id) && quotes.get(i.id).currentVersion > 0)
			.toList();
		Map<Long, QuoteData> submitted = reader.lastSubmitted(quoted.stream().map(i -> quotes.get(i.id)).toList());
		Map<Long, CompanyRef> suppliers = directory.companies(quoted.stream().map(i -> i.supplierCompanyId).toList());
		Map<Long, Evaluation> scores = evaluations.findByAssignmentIdIn(quoted.stream().map(i -> i.id).toList())
			.stream().collect(Collectors.toMap(e -> e.assignmentId, Function.identity()));
		RfqState state = RfqState.of(rfq);
		LocalDate today = LocalDate.now();

		List<ComparisonLine> lines = quoted.stream().map(i -> {
			SupplierQuote q = quotes.get(i.id);
			QuoteData data = submitted.get(q.id);
			CompanyRef s = suppliers.get(i.supplierCompanyId);
			Evaluation e = scores.get(i.id);
			return new ComparisonLine(i.id, new Named(s.id(), s.code(), s.name()), i.status, q.currentVersion,
					q.submittedAt, data, convert(data, rfq.currencyId(), today), e == null ? null : scores(e),
					e == null ? null : total(e), e == null ? null : e.internalRemarks,
					QuoteService.actions(me, i, state));
		}).toList();

		Comparison c = comparisons.findByRfqId(rfqId).orElse(null);
		Map<Long, UserRef> users = directory.users(c == null ? List.of() : List.of(c.preparedByUserId));
		return new ComparisonView(rfq.id(), rfq.number(), named(Ref.CURRENCY, rfq.currencyId()), targets(rfq.targets()),
				c == null ? null : c.internalSummary, c == null ? null : c.recommendationSummary,
				c == null ? null : QuoteService.userName(users, c.preparedByUserId), c == null ? null : c.preparedAt,
				QuoteService.EVALUATORS.contains(me.role()) && rfq.evaluating(), lines);
	}

	/** ERGO's written assessment and recommendation (SRS §13.3.7). */
	public ComparisonView saveSummary(long rfqId, SummaryForm form) {
		CurrentUser me = CurrentUser.get();
		RfqRef rfq = rfqs.forErgo(rfqId);
		if (!rfq.evaluating()) {
			throw ApiException.conflict("Quotes can be compared once some are in, until the customer has decided");
		}
		Comparison c = comparisons.findByRfqId(rfqId).orElseGet(() -> {
			Comparison created = new Comparison();
			created.rfqId = rfqId;
			return created;
		});
		c.internalSummary = QuoteService.text(form.internalSummary());
		c.recommendationSummary = QuoteService.text(form.recommendationSummary());
		c.preparedByUserId = me.userId();
		c.preparedAt = Instant.now();
		comparisons.save(c);
		ModuleSwitch.requireWritable(Module.QUOTATION, Module.RFQ); // both locked until commit: no switch change mid-operation
		rfqs.comparing(rfqId);
		return comparison(rfqId);
	}

	/** ERGO's scores and internal remarks for one submitted quote (SRS §13.3.8, §17.3 manual scoring). */
	public ComparisonView evaluate(long quoteId, EvaluationForm form) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = invitations.findById(quoteId).orElseThrow(() -> ApiException.notFound("Quote"));
		RfqRef rfq = rfqs.forErgo(invitation.rfqId); // 404 outside the caller's RFQ scope
		if (!QuoteService.allowed(QuoteAction.EVALUATE, me, invitation, RfqState.of(rfq))) {
			throw ApiException.conflict("This quote cannot be scored now");
		}
		Evaluation e = evaluations.findByAssignmentId(invitation.id).orElseGet(() -> {
			Evaluation created = new Evaluation();
			created.assignmentId = invitation.id;
			return created;
		});
		Scores scores = form.scores();
		e.commercialScore = scores.commercial();
		e.technicalScore = scores.technical();
		e.qualityScore = scores.quality();
		e.riskScore = scores.risk();
		e.internalRemarks = QuoteService.text(form.internalRemarks());
		evaluations.save(e);
		ModuleSwitch.requireWritable(Module.QUOTATION, Module.RFQ); // both locked until commit: no switch change mid-operation
		rfqs.comparing(invitation.rfqId);
		return comparison(invitation.rfqId);
	}

	/** The quote's prices in the RFQ currency at the rate of the day; empty when ERGO has entered no rate. */
	private Converted convert(QuoteData quote, long rfqCurrencyId, LocalDate day) {
		BigDecimal rate = masterData.rate(quote.currency().id(), rfqCurrencyId, day).orElse(null);
		return rate == null ? new Converted(null, null, null, null)
				: new Converted(rate, times(quote.productionPiecePrice(), rate), times(quote.ppapPiecePrice(), rate),
						times(quote.toolingCostTotal(), rate));
	}

	private TargetsView targets(Targets t) {
		return new TargetsView(t.toolingCost(), t.ppapPiecePrice(), t.productionPiecePrice(),
				named(Ref.PAYMENT_TERM, t.paymentTermId()), t.moq(), t.materialLeadTimeWeeks(), t.toolingLeadTimeDays(),
				t.sampleLeadTimeDays(), t.productionLeadTimeDays());
	}

	private Named named(Ref ref, Long id) {
		MasterDataApi.Named n = id == null ? null : masterData.describe(ref, Set.of(id)).get(id);
		return n == null ? null : new Named(n.id(), n.code(), n.name());
	}

	private static Scores scores(Evaluation e) {
		return new Scores(e.commercialScore, e.technicalScore, e.qualityScore, e.riskScore);
	}

	/** The average of the scores given, to one decimal; null when none is given. */
	static BigDecimal total(Evaluation e) {
		List<Integer> given = Stream.of(e.commercialScore, e.technicalScore, e.qualityScore, e.riskScore)
			.filter(Objects::nonNull).toList();
		return given.isEmpty() ? null : BigDecimal.valueOf(given.stream().mapToInt(Integer::intValue).sum())
			.divide(BigDecimal.valueOf(given.size()), 1, RoundingMode.HALF_UP);
	}

	private static BigDecimal times(BigDecimal amount, BigDecimal rate) {
		return amount == null ? null : amount.multiply(rate).setScale(4, RoundingMode.HALF_UP);
	}

}
