package com.ergo.connect.quotation.domain;

import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;
import static com.ergo.connect.common.security.Role.ERGO_NPD;
import static com.ergo.connect.common.security.Role.ERGO_RFQ;
import static com.ergo.connect.common.security.Role.SUPPLIER_KAM;
import static com.ergo.connect.common.security.Role.SUPPLIER_RFQ;
import static com.ergo.connect.quotation.domain.QuoteStatus.CLARIFICATION_REQUESTED;
import static com.ergo.connect.quotation.domain.QuoteStatus.DRAFT_SAVED;
import static com.ergo.connect.quotation.domain.QuoteStatus.NOT_STARTED;
import static com.ergo.connect.quotation.domain.QuoteStatus.REGRETTED;
import static com.ergo.connect.quotation.domain.QuoteStatus.REOPENED;
import static com.ergo.connect.quotation.domain.QuoteStatus.NOT_SELECTED;
import static com.ergo.connect.quotation.domain.QuoteStatus.RESUBMITTED;
import static com.ergo.connect.quotation.domain.QuoteStatus.SELECTED_FOR_CUSTOMER;
import static com.ergo.connect.quotation.domain.QuoteStatus.SHORTLISTED;
import static com.ergo.connect.quotation.domain.QuoteStatus.SUBMITTED;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.documents.api.DocumentStore;
import com.ergo.connect.documents.api.DocumentStore.DocumentInfo;
import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;
import com.ergo.connect.masterdata.api.MasterDataApi;
import com.ergo.connect.masterdata.api.MasterDataApi.Ref;
import com.ergo.connect.quotation.domain.ComparisonDtos.ResponseStatus;
import com.ergo.connect.quotation.domain.QuoteDtos.CostBreakdown;
import com.ergo.connect.quotation.domain.QuoteDtos.Decision;
import com.ergo.connect.quotation.domain.QuoteDtos.DocumentView;
import com.ergo.connect.quotation.domain.QuoteDtos.ErgoQuoteRow;
import com.ergo.connect.quotation.domain.QuoteDtos.ErgoQuoteView;
import com.ergo.connect.quotation.domain.QuoteDtos.Named;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteAction;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteData;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteForm;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteView;
import com.ergo.connect.quotation.domain.QuoteDtos.RfqSuppliers;
import com.ergo.connect.quotation.domain.QuoteDtos.StepView;
import com.ergo.connect.quotation.domain.QuoteDtos.SupplierQuoteRow;
import com.ergo.connect.quotation.domain.QuoteDtos.SupplierQuoteView;
import com.ergo.connect.quotation.domain.QuoteDtos.VersionView;
import com.ergo.connect.rfq.api.RfqApi;
import com.ergo.connect.rfq.api.RfqApi.RfqRef;
import com.ergo.connect.rfq.api.RfqApi.SupplierRfq;

import tools.jackson.databind.json.JsonMapper;

/**
 * Supplier float and supplier quotes (SRS §5.3, §14.3, §17.2). Record scope (SRS §10.5): a supplier sees only its own
 * invitations; ERGO users see those of RFQs in their RFQ scope; customers see nothing here (ERGO sends them quotes in
 * Phase 2c). {@link #allowed} is the single rule book for enforcement and for the caller's buttons.
 */
@Service
@Transactional
public class QuoteService {

	/** linked_module of files attached to a quote. */
	static final String MODULE = "quotation";

	/** SRS §10.5 view: ERGO Admin, Management, RFQ (V); KAM and NPD (RV, scoped by the RFQ module). */
	private static final Set<Role> ERGO_VIEWERS = EnumSet.of(ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ, ERGO_KAM, ERGO_NPD);

	/** SRS §9.1: ERGO RFQ runs the float and controls supplier quote edit permission (with Admin, Management). */
	private static final Set<Role> ERGO_BUYERS = EnumSet.of(ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ);

	/** SRS §9.1/§10.5: ERGO RFQ (and Admin) compare quotes and submit them to the customer. */
	static final Set<Role> EVALUATORS = EnumSet.of(ERGO_ADMIN, ERGO_RFQ);

	/** SRS §10.5: Supplier RFQ and Supplier KAM create, edit (when open) and submit quotes. */
	private static final Set<Role> SUPPLIER_QUOTERS = EnumSet.of(SUPPLIER_RFQ, SUPPLIER_KAM);

	/** The RFQ-side facts the rule book needs; a supplier only ever learns whether quoting is open. */
	record RfqState(boolean quotingOpen, boolean evaluating, boolean offering) {

		static RfqState of(RfqRef rfq) {
			return new RfqState(rfq.quotingOpen(), rfq.evaluating(), rfq.offering());
		}

	}

	/** A downloadable file: metadata plus content. */
	public record DocumentFile(DocumentInfo info, byte[] content) {
	}

	private final InvitationRepository invitations;

	private final SupplierQuoteRepository quotes;

	private final RfqApi rfqs;

	private final Directory directory;

	private final MasterDataApi masterData;

	private final DocumentStore documents;

	private final AuditLogService audit;

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final QuoteReader reader;

	private final CustomerQuoteRepository customerQuotes;

	QuoteService(InvitationRepository invitations, SupplierQuoteRepository quotes, RfqApi rfqs, Directory directory,
			MasterDataApi masterData, DocumentStore documents, AuditLogService audit, JdbcTemplate jdbc,
			JsonMapper json, QuoteReader reader, CustomerQuoteRepository customerQuotes) {
		this.invitations = invitations;
		this.quotes = quotes;
		this.rfqs = rfqs;
		this.directory = directory;
		this.masterData = masterData;
		this.documents = documents;
		this.audit = audit;
		this.jdbc = jdbc;
		this.json = json;
		this.reader = reader;
		this.customerQuotes = customerQuotes;
	}

	// ---------------------------------------------------------------- ERGO: the suppliers of an RFQ

	@Transactional(readOnly = true)
	public RfqSuppliers forRfq(long rfqId) {
		CurrentUser me = CurrentUser.get();
		RfqRef rfq = rfqs.forErgo(rfqId);
		List<Invitation> list = invitations.findByRfqIdOrderById(rfqId);
		Map<Long, SupplierQuote> byInvitation = reader.byInvitation(list);
		Map<Long, QuoteData> submitted = reader.lastSubmitted(byInvitation.values());
		Map<Long, CompanyRef> suppliers = directory.companies(list.stream().map(i -> i.supplierCompanyId).toList());
		List<ErgoQuoteRow> rows = list.stream().map(i -> {
			SupplierQuote q = byInvitation.get(i.id);
			CompanyRef s = suppliers.get(i.supplierCompanyId);
			// ERGO works with what the supplier submitted, never with unsent drafts
			QuoteData sent = q == null ? null : submitted.get(q.id);
			return new ErgoQuoteRow(i.id, new Named(s.id(), s.code(), s.name()), i.status, i.responseDueDate,
					q == null ? 0 : q.currentVersion, q == null ? null : q.submittedAt,
					sent == null || sent.currency() == null ? null : sent.currency().code(),
					sent == null ? null : sent.productionPiecePrice(), sent == null ? null : sent.toolingCostTotal(),
					sent == null ? null : sent.moq(), sent == null ? null : sent.productionLeadTimeDays());
		}).toList();
		return new RfqSuppliers(ERGO_BUYERS.contains(me.role()) && rfq.acceptsInvitations(), rows);
	}

	/** SRS §5.3 "supplier assignment and RFQ float". Only active supplier companies; each once per RFQ. */
	public RfqSuppliers invite(long rfqId, Set<Long> supplierIds, LocalDate responseDueDate) {
		CurrentUser me = CurrentUser.get();
		RfqRef rfq = rfqs.forErgo(rfqId);
		if (!rfq.acceptsInvitations()) {
			throw ApiException.conflict("Suppliers can be invited once ERGO has validated the RFQ");
		}
		Set<Long> invited = invitations.findByRfqIdOrderById(rfqId).stream().map(i -> i.supplierCompanyId)
			.collect(Collectors.toSet());
		Map<Long, CompanyRef> companies = directory.companies(supplierIds);
		for (Long id : supplierIds) {
			CompanyRef company = companies.get(id);
			if (company == null || company.type() != CompanyType.SUPPLIER || !company.active()) {
				throw ApiException.unprocessable("Only active suppliers can be invited");
			}
			if (invited.contains(id)) {
				throw ApiException.unprocessable(company.name() + " is already invited");
			}
		}
		for (Long id : supplierIds) {
			Invitation invitation = new Invitation();
			invitation.rfqId = rfqId;
			invitation.supplierCompanyId = id;
			invitation.assignedByUserId = me.userId();
			invitation.responseDueDate = responseDueDate;
			invitation.status = NOT_STARTED;
			invitations.save(invitation);
		}
		rfqs.sentToSuppliers(rfqId);
		progress(rfqId);
		return forRfq(rfqId);
	}

	// ---------------------------------------------------------------- supplier: my invitations

	@Transactional(readOnly = true)
	public List<SupplierQuoteRow> mine() {
		CurrentUser me = CurrentUser.get();
		List<Invitation> mine = invitations.findBySupplierCompanyIdOrderByIdDesc(me.companyId());
		Map<Long, SupplierRfq> rfqInfo = rfqs.forSupplier(mine.stream().map(i -> i.rfqId).collect(Collectors.toSet()));
		Map<Long, SupplierQuote> byInvitation = reader.byInvitation(mine);
		return mine.stream().map(i -> {
			SupplierRfq r = rfqInfo.get(i.rfqId);
			SupplierQuote q = byInvitation.get(i.id);
			int version = q == null ? 0 : q.currentVersion;
			return new SupplierQuoteRow(i.id, r.number(), r.partNumber(), r.revision(), r.description(),
					i.responseDueDate, i.status.forSupplier(version), version, q == null ? null : q.submittedAt,
					r.quotingOpen());
		}).toList();
	}

	@Transactional(readOnly = true)
	public QuoteView get(long id) {
		CurrentUser me = CurrentUser.get();
		return view(me, visible(me, id));
	}

	// ---------------------------------------------------------------- supplier actions

	public QuoteView saveDraft(long id, QuoteForm form) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		require(QuoteAction.EDIT, me, invitation);
		SupplierQuote quote = quotes.findByAssignmentId(invitation.id).orElseGet(() -> {
			SupplierQuote created = new SupplierQuote();
			created.assignmentId = invitation.id;
			return created;
		});
		apply(quote, form);
		quotes.save(quote);
		if (invitation.status == NOT_STARTED) {
			invitation.status = DRAFT_SAVED;
		}
		return view(me, invitation);
	}

	/** SRS §14.3: final submission locks the quote; each submission is kept as an immutable version. */
	public QuoteView submit(long id) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		require(QuoteAction.SUBMIT, me, invitation);
		SupplierQuote quote = quotes.findByAssignmentId(invitation.id)
			.orElseThrow(() -> ApiException.unprocessable("Save your quote before submitting it"));
		requireComplete(quote);
		boolean resubmission = quote.currentVersion > 0;
		quote.currentVersion++;
		quote.submittedAt = Instant.now();
		quote.submittedByUserId = me.userId();
		// the supplier's files go out with this version: from now on ERGO sees them and nobody deletes them
		Long[] files = documents.list(MODULE, invitation.id).stream()
			.filter(d -> d.uploadedByCompanyId() == invitation.supplierCompanyId)
			.map(DocumentInfo::id)
			.toArray(Long[]::new);
		jdbc.update("""
				INSERT INTO supplier_quote_versions (supplier_quote_id, version_no, snapshot, document_ids,
				                                     submitted_by_user_id, reopen_reason, reopened_by_user_id, reopened_at)
				VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?)""", quote.id, quote.currentVersion,
				json.writeValueAsString(data(quote)), files, me.userId(), quote.reopenReason, quote.reopenedByUserId,
				quote.reopenedAt == null ? null : OffsetDateTime.ofInstant(quote.reopenedAt, ZoneOffset.UTC));
		quote.reopenReason = null;
		quote.reopenedByUserId = null;
		quote.reopenedAt = null;
		invitation.status = resubmission ? RESUBMITTED : SUBMITTED;
		invitation.statusBeforeClarification = null;
		invitation.quoteEditOpen = false;
		audit.record(Invitation.class, invitation.id, "QUOTE_SUBMITTED", "version", null,
				String.valueOf(quote.currentVersion));
		progress(invitation.rfqId);
		return view(me, invitation);
	}

	/** SRS §9.3 "regrets RFQs with reason". */
	public QuoteView regret(long id, String reason) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		require(QuoteAction.REGRET, me, invitation);
		invitation.regretReason = required(reason, "Tell ERGO why you cannot quote");
		audit.reason(invitation.regretReason);
		invitation.status = REGRETTED;
		invitation.quoteEditOpen = false;
		progress(invitation.rfqId);
		return view(me, invitation);
	}

	public QuoteView requestClarification(long id, String question) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		require(QuoteAction.REQUEST_CLARIFICATION, me, invitation);
		invitation.clarificationQuestion = required(question, "Write your question for ERGO");
		invitation.clarificationAnswer = null;
		audit.reason(invitation.clarificationQuestion);
		invitation.statusBeforeClarification = invitation.status;
		invitation.status = CLARIFICATION_REQUESTED;
		return view(me, invitation);
	}

	// ---------------------------------------------------------------- ERGO actions

	public QuoteView answerClarification(long id, String answer) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		require(QuoteAction.ANSWER_CLARIFICATION, me, invitation);
		invitation.clarificationAnswer = required(answer, "Write the answer for the supplier");
		audit.reason(invitation.clarificationAnswer);
		invitation.status = invitation.statusBeforeClarification == null ? NOT_STARTED
				: invitation.statusBeforeClarification;
		invitation.statusBeforeClarification = null;
		return view(me, invitation);
	}

	/** SRS §14.3: re-open requires a reason; the next submission becomes a new version carrying it. */
	public QuoteView reopen(long id, String reason) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		require(QuoteAction.REOPEN, me, invitation);
		String why = required(reason, "A reason is required to re-open a quote");
		audit.reason(why);
		SupplierQuote quote = quotes.findByAssignmentId(invitation.id).orElseThrow();
		quote.reopenReason = why;
		quote.reopenedByUserId = me.userId();
		quote.reopenedAt = Instant.now();
		invitation.status = REOPENED;
		invitation.quoteEditOpen = true;
		progress(invitation.rfqId);
		return view(me, invitation);
	}

	/**
	 * SRS §14.3 / §17.3: ERGO shortlists a quote, selects it for the customer or marks it not selected (with a
	 * reason, audited); CLEAR_DECISION puts it back to submitted. Suppliers keep seeing "submitted" throughout. Frozen
	 * while a quote is with the customer, so the decision behind an answered quote can never change underneath it.
	 */
	public QuoteView decide(long id, Decision decision, String comment) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		require(QuoteAction.valueOf(decision.name()), me, invitation);
		if (!customerQuotes.findByAssignmentIdAndResponseStatus(invitation.id, ResponseStatus.WAITING).isEmpty()) {
			throw ApiException.conflict("This quote is with the customer; its decision can change once they respond");
		}
		if (decision == Decision.MARK_NOT_SELECTED) {
			audit.reason(required(comment, "Give a reason this quote was not selected"));
		}
		invitation.status = switch (decision) {
			case SHORTLIST -> SHORTLISTED;
			case SELECT_FOR_CUSTOMER -> SELECTED_FOR_CUSTOMER;
			case MARK_NOT_SELECTED -> NOT_SELECTED;
			case CLEAR_DECISION -> quotes.findByAssignmentId(invitation.id).orElseThrow().currentVersion > 1 ? RESUBMITTED
					: SUBMITTED;
		};
		rfqs.comparing(invitation.rfqId);
		return view(me, invitation);
	}

	// ---------------------------------------------------------------- versions and files

	@Transactional(readOnly = true)
	public List<VersionView> versions(long id) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		SupplierQuote quote = quotes.findByAssignmentId(invitation.id).orElse(null);
		if (quote == null) {
			return List.of();
		}
		record Row(int version, String snapshot, Long[] files, Long submittedBy, Instant submittedAt,
				String reopenReason, Long reopenedBy, Instant reopenedAt) {
		}
		List<Row> rows = jdbc.query("""
				SELECT version_no, snapshot::text, document_ids, submitted_by_user_id, submitted_at, reopen_reason,
				       reopened_by_user_id, reopened_at
				FROM supplier_quote_versions WHERE supplier_quote_id = ? ORDER BY version_no""",
				(rs, n) -> new Row(rs.getInt(1), rs.getString(2), (Long[]) rs.getArray(3).getArray(), rs.getLong(4),
						rs.getObject(5, OffsetDateTime.class).toInstant(), rs.getString(6), (Long) rs.getObject(7),
						rs.getObject(8) == null ? null : rs.getObject(8, OffsetDateTime.class).toInstant()),
				quote.id);
		Map<Long, UserRef> users = directory.users(rows.stream()
			.flatMap(r -> Stream.of(r.submittedBy(), r.reopenedBy())).filter(Objects::nonNull).toList());
		Map<Long, String> fileNames = documents.list(MODULE, invitation.id).stream()
			.collect(Collectors.toMap(DocumentInfo::id, DocumentInfo::fileName));
		return rows.stream().map(r -> new VersionView(r.version(), r.submittedAt(), userName(users, r.submittedBy()),
				r.reopenReason(), userName(users, r.reopenedBy()), r.reopenedAt(),
				json.readValue(r.snapshot(), QuoteData.class),
				Arrays.stream(r.files()).map(fileNames::get).filter(Objects::nonNull).sorted().toList())).toList();
	}

	@Transactional(readOnly = true)
	public List<DocumentView> documents(long id) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		Set<Long> submitted = submittedFiles(invitation);
		boolean mayDelete = mayDeleteOwnFiles(me, invitation);
		List<DocumentView> result = new ArrayList<>();
		if (me.companyType() == CompanyType.SUPPLIER) {
			rfqs.supplierDocuments(invitation.rfqId).forEach(d -> result.add(documentView(me, "RFQ", d, false)));
		}
		documents.list(MODULE, invitation.id).stream().filter(d -> canSee(me, invitation, d, submitted))
			.forEach(d -> result.add(documentView(me, "QUOTE", d,
					mayDelete && d.uploadedByCompanyId() == me.companyId() && !submitted.contains(d.id()))));
		return result;
	}

	/**
	 * Files on a quote are never shared by visibility class: ERGO sees a supplier's files once they are submitted with
	 * a version, the supplier sees its own, and ERGO's own files stay internal.
	 */
	public DocumentView upload(long id, String fileName, byte[] content) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		require(QuoteAction.UPLOAD, me, invitation);
		return documentView(me, "QUOTE",
				documents.store(MODULE, invitation.id, fileName, content, Visibility.INTERNAL), true);
	}

	@Transactional(readOnly = true)
	public DocumentFile download(long id, String source, long documentId) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		DocumentInfo document = "RFQ".equals(source) && me.companyType() == CompanyType.SUPPLIER
				? rfqs.supplierDocuments(invitation.rfqId).stream().filter(d -> d.id() == documentId).findFirst()
					.orElse(null)
				: documents.find(MODULE, invitation.id, documentId)
					.filter(d -> canSee(me, invitation, d, submittedFiles(invitation)))
					.orElse(null);
		if (document == null) {
			throw ApiException.notFound("Document");
		}
		return new DocumentFile(document, documents.content(document));
	}

	public void deleteDocument(long id, long documentId) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		Set<Long> submitted = submittedFiles(invitation);
		DocumentInfo document = documents.find(MODULE, invitation.id, documentId)
			.filter(d -> canSee(me, invitation, d, submitted))
			.orElseThrow(() -> ApiException.notFound("Document"));
		if (document.uploadedByCompanyId() != me.companyId() || submitted.contains(document.id())
				|| !mayDeleteOwnFiles(me, invitation)) {
			throw ApiException.conflict("This file can no longer be removed");
		}
		documents.delete(document);
	}

	// ---------------------------------------------------------------- rules

	/** Record scope. Out-of-scope quotes do not exist for the caller (404). */
	private Invitation visible(CurrentUser me, long id) {
		Invitation invitation = invitations.findById(id).orElse(null);
		boolean inScope = invitation != null && switch (me.companyType()) {
			case SUPPLIER -> SUPPLIER_QUOTERS.contains(me.role()) && invitation.supplierCompanyId == me.companyId();
			case ERGO -> {
				rfqs.forErgo(invitation.rfqId); // 404 unless the RFQ is in this ERGO user's scope
				yield ERGO_VIEWERS.contains(me.role());
			}
			case CUSTOMER -> false; // customers only ever see what ERGO sends them (Phase 2c)
		};
		if (!inScope) {
			throw ApiException.notFound("Quote");
		}
		return invitation;
	}

	/**
	 * The rule book: SRS §10.5 rights, §14.3 statuses, §17.2 edit rules (edit only while open) and §17.3 comparison
	 * actions. Also yields each caller's buttons.
	 */
	static boolean allowed(QuoteAction action, CurrentUser me, Invitation i, RfqState rfq) {
		boolean supplier = SUPPLIER_QUOTERS.contains(me.role());
		boolean buyer = ERGO_BUYERS.contains(me.role());
		boolean evaluator = EVALUATORS.contains(me.role()) && rfq.evaluating();
		boolean submitted = QuoteStatus.SUBMITTED_STATES.contains(i.status);
		QuoteStatus status = i.status;
		return switch (action) {
			case EDIT, SUBMIT -> supplier && rfq.quotingOpen() && i.quoteEditOpen
					&& QuoteStatus.EDITABLE.contains(status);
			case REGRET -> supplier && rfq.quotingOpen() && (status == NOT_STARTED || status == DRAFT_SAVED
					|| status == CLARIFICATION_REQUESTED && i.statusBeforeClarification != REOPENED);
			case REQUEST_CLARIFICATION -> supplier && rfq.quotingOpen()
					&& (status == NOT_STARTED || status == DRAFT_SAVED || status == REOPENED);
			case ANSWER_CLARIFICATION -> buyer && status == CLARIFICATION_REQUESTED;
			case REOPEN -> buyer && rfq.quotingOpen() && submitted; // "request revision" in the comparison too
			case UPLOAD -> supplier ? rfq.quotingOpen() && i.quoteEditOpen && QuoteStatus.EDITABLE.contains(status)
					: buyer && status != REGRETTED;
			case EVALUATE -> evaluator && (submitted || status == REOPENED);
			case SHORTLIST -> evaluator && submitted && status != SHORTLISTED;
			case SELECT_FOR_CUSTOMER -> evaluator && submitted && status != SELECTED_FOR_CUSTOMER;
			case MARK_NOT_SELECTED -> evaluator && submitted && status != NOT_SELECTED;
			case CLEAR_DECISION -> evaluator && QuoteStatus.DECIDED.contains(status);
			case SEND_TO_CUSTOMER -> EVALUATORS.contains(me.role()) && rfq.offering() && status == SELECTED_FOR_CUSTOMER;
		};
	}

	static Set<QuoteAction> actions(CurrentUser me, Invitation invitation, RfqState rfq) {
		return Arrays.stream(QuoteAction.values()).filter(a -> allowed(a, me, invitation, rfq))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(QuoteAction.class)));
	}

	private void require(QuoteAction action, CurrentUser me, Invitation invitation) {
		if (!allowed(action, me, invitation, rfqState(me, invitation))) {
			throw ApiException.conflict("This action is not available for this quote right now");
		}
	}

	private RfqState rfqState(CurrentUser me, Invitation invitation) {
		return me.companyType() == CompanyType.SUPPLIER
				? new RfqState(rfqs.forSupplier(invitation.rfqId).quotingOpen(), false, false)
				: RfqState.of(rfqs.forErgo(invitation.rfqId));
	}

	/** SRS §17.2: currency, prices, MOQ, lead time and the full cost breakdown before submission. */
	private static void requireComplete(SupplierQuote q) {
		List<String> missing = new ArrayList<>();
		if (q.currencyId == null) {
			missing.add("currency");
		}
		if (q.validityDate == null) {
			missing.add("validity date");
		}
		if (q.productionPiecePrice == null) {
			missing.add("production piece price");
		}
		if (q.moq == null) {
			missing.add("MOQ");
		}
		if (q.productionLeadTimeDays == null) {
			missing.add("production lead time");
		}
		if (Stream.of(q.rawMaterialCost, q.processingCost, q.overheadCost, q.packagingCost, q.profit)
			.anyMatch(Objects::isNull)) {
			missing.add("cost breakdown (raw material, processing, overhead, packaging, profit)");
		}
		if (!missing.isEmpty()) {
			throw ApiException.unprocessable("Please complete: " + String.join(", ", missing));
		}
		if (q.validityDate.isBefore(LocalDate.now())) {
			throw ApiException.unprocessable("The quote validity date is in the past");
		}
	}

	/** Newly chosen master entries must be active; entries that were already set may since have been retired. */
	private void apply(SupplierQuote q, QuoteForm form) {
		boolean prices = Stream.of(form.toolingCostTotal(), form.ppapPiecePrice(), form.productionPiecePrice(),
				form.rawMaterialCost(), form.processingCost(), form.overheadCost(), form.packagingCost(), form.profit())
			.anyMatch(Objects::nonNull) || form.tooling() != null && !form.tooling().isEmpty();
		if (prices && form.currencyId() == null) {
			throw ApiException.unprocessable("Choose the quote currency before entering prices");
		}
		if (!Objects.equals(q.currencyId, form.currencyId())) {
			masterData.requireActive(Ref.CURRENCY, form.currencyId(), "Currency");
		}
		if (!Objects.equals(q.incotermId, form.incotermId())) {
			masterData.requireActive(Ref.INCOTERM, form.incotermId(), "Incoterm");
		}
		if (!Objects.equals(q.paymentTermId, form.paymentTermId())) {
			masterData.requireActive(Ref.PAYMENT_TERM, form.paymentTermId(), "Payment term");
		}
		List<QuoteStep> steps = form.processSteps() == null ? List.of()
				: form.processSteps().stream().map(QuoteService::clean).toList();
		Set<Long> knownProcesses = q.processSteps.stream().map(QuoteStep::processId).filter(Objects::nonNull)
			.collect(Collectors.toSet());
		steps.stream().map(QuoteStep::processId).filter(p -> p != null && !knownProcesses.contains(p))
			.forEach(p -> masterData.requireActive(Ref.PROCESS, p, "Process"));
		List<ToolingLine> tooling = form.tooling() == null ? List.of() : form.tooling().stream()
			.map(t -> new ToolingLine(t.toolName().trim(), t.toolingCost(), text(t.remarks()))).toList();

		q.quoteReference = text(form.quoteReference());
		q.validityDate = form.validityDate();
		q.currencyId = form.currencyId();
		q.incotermId = form.incotermId();
		q.paymentTermId = form.paymentTermId();
		q.toolingCostTotal = tooling.isEmpty() ? form.toolingCostTotal()
				: tooling.stream().map(ToolingLine::toolingCost).reduce(BigDecimal.ZERO, BigDecimal::add);
		q.ppapPiecePrice = form.ppapPiecePrice();
		q.productionPiecePrice = form.productionPiecePrice();
		q.rawMaterialCost = form.rawMaterialCost();
		q.processingCost = form.processingCost();
		q.overheadCost = form.overheadCost();
		q.packagingCost = form.packagingCost();
		q.profit = form.profit();
		q.moq = form.moq();
		q.materialLeadTimeWeeks = form.materialLeadTimeWeeks();
		q.toolingLeadTimeDays = form.toolingLeadTimeDays();
		q.sampleLeadTimeDays = form.sampleLeadTimeDays();
		q.productionLeadTimeDays = form.productionLeadTimeDays();
		q.assumptionsDeviations = text(form.assumptionsDeviations());
		q.remarks = text(form.remarks());
		if (!q.tooling.equals(tooling)) {
			q.tooling.clear();
			q.tooling.addAll(tooling);
		}
		if (!q.processSteps.equals(steps)) {
			q.processSteps.clear();
			q.processSteps.addAll(steps);
		}
	}

	/**
	 * Recomputes the RFQ status from the invitations (SRS §14.2 quotes received partially / completely). A re-opened
	 * quote still counts as received (its last version stands) but is awaited again until resubmitted.
	 */
	private void progress(long rfqId) {
		List<Invitation> all = invitations.findByRfqIdOrderById(rfqId);
		Map<Long, SupplierQuote> byInvitation = reader.byInvitation(all);
		int received = (int) all.stream().filter(i -> i.status != REGRETTED && byInvitation.containsKey(i.id)
				&& byInvitation.get(i.id).currentVersion > 0).count();
		int awaited = (int) all.stream()
			.filter(i -> i.status != REGRETTED && !QuoteStatus.SUBMITTED_STATES.contains(i.status)).count();
		rfqs.quoteProgress(rfqId, received, awaited);
	}

	private QuoteView view(CurrentUser me, Invitation i) {
		SupplierQuote q = quotes.findByAssignmentId(i.id).orElse(null);
		QuoteData data = q == null ? null : data(q);
		int version = q == null ? 0 : q.currentVersion;
		Instant submittedAt = q == null ? null : q.submittedAt;
		String reopenReason = q == null ? null : q.reopenReason;
		if (me.companyType() == CompanyType.SUPPLIER) {
			SupplierRfq rfq = rfqs.forSupplier(i.rfqId);
			return new SupplierQuoteView("SUPPLIER", i.id, i.status.forSupplier(version), i.responseDueDate, rfq, data,
					version, submittedAt, reopenReason, i.clarificationQuestion, i.clarificationAnswer, i.regretReason,
					actions(me, i, new RfqState(rfq.quotingOpen(), false, false)));
		}
		RfqRef rfq = rfqs.forErgo(i.rfqId);
		CompanyRef supplier = directory.company(i.supplierCompanyId).orElseThrow();
		Map<Long, UserRef> users = directory.users(List.of(i.assignedByUserId));
		// ERGO sees the last submitted version, never the supplier's unsent changes
		QuoteData ergoData = q == null ? null : reader.lastSubmitted(List.of(q)).get(q.id);
		return new ErgoQuoteView("ERGO", i.id, i.status, i.responseDueDate, rfq.id(), rfq.number(),
				new Named(supplier.id(), supplier.code(), supplier.name()), userName(users, i.assignedByUserId),
				i.createdAt, ergoData, version, submittedAt, reopenReason, i.clarificationQuestion,
				i.clarificationAnswer, i.regretReason, actions(me, i, RfqState.of(rfq)));
	}

	private QuoteData data(SupplierQuote q) {
		Map<Long, MasterDataApi.Named> currency = masterData.describe(Ref.CURRENCY, ids(q.currencyId));
		Map<Long, MasterDataApi.Named> incoterm = masterData.describe(Ref.INCOTERM, ids(q.incotermId));
		Map<Long, MasterDataApi.Named> term = masterData.describe(Ref.PAYMENT_TERM, ids(q.paymentTermId));
		Map<Long, MasterDataApi.Named> processes = masterData.describe(Ref.PROCESS,
				q.processSteps.stream().map(QuoteStep::processId).filter(Objects::nonNull).collect(Collectors.toSet()));
		return new QuoteData(q.quoteReference, q.validityDate, named(currency, q.currencyId),
				named(incoterm, q.incotermId), named(term, q.paymentTermId), q.toolingCostTotal, List.copyOf(q.tooling),
				q.ppapPiecePrice, q.productionPiecePrice,
				new CostBreakdown(q.rawMaterialCost, q.processingCost, q.overheadCost, q.packagingCost, q.profit), q.moq,
				q.materialLeadTimeWeeks, q.toolingLeadTimeDays, q.sampleLeadTimeDays, q.productionLeadTimeDays,
				q.processSteps.stream().map(s -> new StepView(named(processes, s.processId()), s.stepName(),
						s.cycleTimeSeconds(), s.sourcingType(), s.outsourcedSupplierName(), s.remarks())).toList(),
				q.assumptionsDeviations, q.remarks);
	}

	private static DocumentView documentView(CurrentUser me, String source, DocumentInfo d, boolean canDelete) {
		return new DocumentView(d.id(), source, d.fileName(), d.size(), d.visibility(),
				d.uploadedByCompanyId() == me.companyId(), canDelete, d.createdAt());
	}

	/** ERGO: its own files and the supplier's submitted ones (drafts stay private). A supplier: its own. */
	private static boolean canSee(CurrentUser me, Invitation i, DocumentInfo d, Set<Long> submitted) {
		return me.companyType() == CompanyType.ERGO
				? d.uploadedByCompanyId() != i.supplierCompanyId || submitted.contains(d.id())
				: d.uploadedByCompanyId() == me.companyId();
	}

	/** Own files, never one submitted with a version: suppliers while the quote is editable, ERGO buyers any time. */
	private boolean mayDeleteOwnFiles(CurrentUser me, Invitation i) {
		return SUPPLIER_QUOTERS.contains(me.role()) ? allowed(QuoteAction.EDIT, me, i, rfqState(me, i))
				: ERGO_BUYERS.contains(me.role());
	}

	/** Ids of the supplier's files that went out with any submitted version of this quote. */
	private Set<Long> submittedFiles(Invitation invitation) {
		return new HashSet<>(jdbc.queryForList("""
				SELECT DISTINCT unnest(v.document_ids) FROM supplier_quote_versions v
				JOIN supplier_quotes q ON q.supplier_quote_id = v.supplier_quote_id
				WHERE q.assignment_id = ?""", Long.class, invitation.id));
	}

	// ---------------------------------------------------------------- small helpers

	private static QuoteStep clean(QuoteStep step) {
		return new QuoteStep(step.processId(), step.stepName().trim(), step.cycleTimeSeconds(), step.sourcingType(),
				step.sourcingType() == QuoteStep.SourcingType.OUTSOURCED ? text(step.outsourcedSupplierName()) : null,
				text(step.remarks()));
	}

	private static Set<Long> ids(Long id) {
		return id == null ? Set.of() : Set.of(id);
	}

	private static Named named(Map<Long, MasterDataApi.Named> names, Long id) {
		MasterDataApi.Named n = id == null ? null : names.get(id);
		return n == null ? null : new Named(n.id(), n.code(), n.name());
	}

	static String userName(Map<Long, UserRef> users, Long id) {
		UserRef user = id == null ? null : users.get(id);
		return user == null ? null : user.name();
	}

	/** Trimmed text; null when blank. */
	static String text(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String required(String value, String message) {
		String text = text(value);
		if (text == null) {
			throw ApiException.unprocessable(message);
		}
		return text;
	}

}
