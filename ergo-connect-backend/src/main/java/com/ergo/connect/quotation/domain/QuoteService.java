package com.ergo.connect.quotation.domain;

import static com.ergo.connect.quotation.domain.QuoteStatus.DRAFT_SAVED;
import static com.ergo.connect.quotation.domain.QuoteStatus.NOT_STARTED;
import static com.ergo.connect.quotation.domain.QuoteStatus.RECALLED;
import static com.ergo.connect.quotation.domain.QuoteStatus.RESUBMITTED;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.ergo.connect.documents.api.DocumentStore;
import com.ergo.connect.documents.api.DocumentStore.DocumentInfo;
import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;
import com.ergo.connect.masterdata.api.MasterDataApi;
import com.ergo.connect.masterdata.api.MasterDataApi.Ref;
import com.ergo.connect.quotation.domain.QuoteDtos.CostBreakdown;
import com.ergo.connect.quotation.domain.QuoteDtos.DistributeForm;
import com.ergo.connect.quotation.domain.QuoteDtos.DocumentView;
import com.ergo.connect.quotation.domain.QuoteDtos.ErgoQuoteView;
import com.ergo.connect.quotation.domain.QuoteDtos.Named;
import com.ergo.connect.quotation.domain.QuoteDtos.PartRef;
import com.ergo.connect.quotation.domain.QuoteDtos.PartRow;
import com.ergo.connect.quotation.domain.QuoteDtos.PartStatusRow;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteAction;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteData;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteForm;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteView;
import com.ergo.connect.quotation.domain.QuoteDtos.RfqQuotes;
import com.ergo.connect.quotation.domain.QuoteDtos.StepView;
import com.ergo.connect.quotation.domain.QuoteDtos.SubRfqForm;
import com.ergo.connect.quotation.domain.QuoteDtos.SupplierAssignedRfqRow;
import com.ergo.connect.quotation.domain.QuoteDtos.SupplierAssignedRfqView;
import com.ergo.connect.quotation.domain.QuoteDtos.SupplierQuoteRow;
import com.ergo.connect.quotation.domain.QuoteDtos.SupplierQuoteView;
import com.ergo.connect.quotation.domain.QuoteDtos.VersionView;
import com.ergo.connect.quotation.api.QuotationApi.SupplierQuoteItem;
import com.ergo.connect.rfq.api.RfqApi;
import com.ergo.connect.rfq.api.RfqApi.ItemRef;
import com.ergo.connect.rfq.api.RfqApi.QuotationSummary;
import com.ergo.connect.rfq.api.RfqApi.RfqRef;
import com.ergo.connect.rfq.api.RfqApi.SupplierItemRef;
import com.ergo.connect.rfq.api.RfqApi.SupplierRfqItem;

import tools.jackson.databind.json.JsonMapper;

/**
 * Distribution to suppliers and the supplier's quote per part (flow doc §2-4; SRS §5.3, §14.3, §17.2). ERGO sends the
 * whole RFQ to one supplier or splits it into sub-RFQs; the supplier quotes and submits each part number on its own;
 * ERGO reviews each quote and either sends it back (revision required, with comments) or accepts it for the customer
 * quotation. Record scope (SRS §10.5): a supplier sees only the parts sent to it; ERGO users see those of RFQs in
 * their RFQ scope; customers see nothing here (ERGO generates their quotation). {@link #allowed} is the single rule
 * book for enforcement and for the caller's buttons.
 */
@Service
@Transactional
public class QuoteService {

	/** linked_module of files attached to a quote. */
	static final String MODULE = "quotation";
	private static final int MAX_PAGE_SIZE = 100;
	private static final int MAX_SEARCH_LENGTH = 100;

	/** The role sheet (ModuleAccess), Quotation column. View (ERGO KAM/NPD are scoped by the RFQ module). */
	private static final Set<Role> ERGO_VIEWERS = ModuleAccess.viewers(Module.QUOTATION, CompanyType.ERGO);

	/** ERGO that runs the distribution and sends quotes back ("A" and up). */
	private static final Set<Role> ERGO_BUYERS = ModuleAccess.actors(Module.QUOTATION, CompanyType.ERGO);

	/** ERGO that accepts quotes and generates the customer quotation: the final-approval ("O") roles. */
	static final Set<Role> EVALUATORS = ModuleAccess.approvers(Module.QUOTATION, CompanyType.ERGO);

	/** Supplier roles that create, edit (when open) and submit quotes. */
	static final Set<Role> SUPPLIER_QUOTERS = ModuleAccess.actors(Module.QUOTATION, CompanyType.SUPPLIER);

	/** The RFQ-side fact the rule book needs: whether quote work is open on the RFQ at all. */
	record RfqState(boolean quotingOpen) {

		static RfqState of(RfqRef rfq) {
			return new RfqState(rfq.quotingOpen());
		}

	}

	/** A downloadable file: metadata plus content. */
	public record DocumentFile(DocumentInfo info, byte[] content) {
	}

	private final InvitationRepository invitations;

	private final SubRfqRepository subRfqs;

	private final SupplierQuoteRepository quotes;

	private final RfqApi rfqs;

	private final Directory directory;

	private final MasterDataApi masterData;

	private final DocumentStore documents;

	private final AuditLogService audit;

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final QuoteReader reader;

	private final Notifier notifier;

	QuoteService(InvitationRepository invitations, SubRfqRepository subRfqs, SupplierQuoteRepository quotes,
			RfqApi rfqs, Directory directory, MasterDataApi masterData, DocumentStore documents, AuditLogService audit,
			JdbcTemplate jdbc, JsonMapper json, QuoteReader reader, Notifier notifier) {
		this.notifier = notifier;
		this.invitations = invitations;
		this.subRfqs = subRfqs;
		this.quotes = quotes;
		this.rfqs = rfqs;
		this.directory = directory;
		this.masterData = masterData;
		this.documents = documents;
		this.audit = audit;
		this.jdbc = jdbc;
		this.json = json;
		this.reader = reader;
	}

	// ---------------------------------------------------------------- ERGO: the parts of an RFQ and their suppliers

	/** The RFQ's parts, each with the supplier it is with (if any) and that supplier's quote and status. */
	@Transactional(readOnly = true)
	public RfqQuotes forRfq(long rfqId) {
		CurrentUser me = CurrentUser.get();
		RfqRef rfq = rfqs.forErgo(rfqId);
		Map<Long, Invitation> byItem = activeByItem(rfqId);
		Map<Long, SupplierQuote> byInvitation = reader.byInvitation(byItem.values());
		Map<Long, QuoteData> submitted = reader.lastSubmitted(byInvitation.values());
		Map<Long, CompanyRef> suppliers = directory
			.companies(byItem.values().stream().map(i -> i.supplierCompanyId).collect(Collectors.toSet()));
		Map<Long, SubRfq> subs = subRfqs.findByRfqIdOrderBySubNo(rfqId).stream()
			.collect(Collectors.toMap(s -> s.id, Function.identity()));
		List<PartRow> rows = rfq.items().stream().map(item -> {
			PartRef part = partRef(item);
			Invitation i = byItem.get(item.id());
			if (i == null) {
				return new PartRow(part, null, null, null, null, null, null, null, null, null, null, null, null,
						Set.of());
			}
			SupplierQuote q = byInvitation.get(i.id);
			CompanyRef s = suppliers.get(i.supplierCompanyId);
			// ERGO works with what the supplier submitted, never with unsent drafts
			QuoteData sent = q == null ? null : submitted.get(q.id);
			return new PartRow(part, i.id, subs.get(i.subRfqId).subNo, new Named(s.id(), s.code(), s.name()), i.status,
					i.responseDueDate, q == null ? 0 : q.currentVersion, q == null ? null : q.submittedAt,
					sent == null || sent.currency() == null ? null : sent.currency().code(),
					sent == null ? null : sent.productionPiecePrice(), sent == null ? null : sent.toolingCostTotal(),
					sent == null ? null : sent.moq(), sent == null ? null : sent.productionLeadTimeDays(),
					actions(me, i, RfqState.of(rfq)));
		}).toList();
		boolean unassigned = rows.stream().anyMatch(r -> r.quoteId() == null);
		return new RfqQuotes(ERGO_BUYERS.contains(me.role()) && rfq.acceptsInvitations() && unassigned, rows);
	}

	/**
	 * Flow doc §2 "ERGO RFQ review and supplier distribution": the whole RFQ to one supplier, or sub-RFQs of some of
	 * its parts to different suppliers. Each part goes to one supplier; a part that was recalled can be sent again.
	 */
	public RfqQuotes distribute(long rfqId, DistributeForm form) {
		CurrentUser me = CurrentUser.get();
		// Distributing writes quotations and moves the RFQ: both modules must accept changes before anything is saved.
		ModuleSwitch.requireWritable(Module.QUOTATION, Module.RFQ);
		// Two ERGO users distributing at once must not send the same part to two suppliers, and the parts are read only
		// after the lock: an RFQ edit that held it may have removed one.
		jdbc.queryForList("SELECT rfq_id FROM rfqs WHERE rfq_id = ? FOR UPDATE", Long.class, rfqId);
		RfqRef rfq = rfqs.forErgo(rfqId);
		if (!rfq.acceptsInvitations()) {
			throw ApiException.conflict("Parts can be sent to suppliers while ERGO reviews the RFQ or once it is out");
		}
		Set<Long> partIds = rfq.items().stream().map(ItemRef::id).collect(Collectors.toSet());
		Set<Long> taken = activeByItem(rfqId).keySet();
		Set<Long> supplierIds = new HashSet<>();
		Set<Long> chosen = new HashSet<>();
		Map<Long, CompanyRef> companies = directory
			.companies(form.subRfqs().stream().map(SubRfqForm::supplierId).collect(Collectors.toSet()));
		for (SubRfqForm sub : form.subRfqs()) {
			CompanyRef company = companies.get(sub.supplierId());
			if (company == null || company.type() != CompanyType.SUPPLIER || !company.active()) {
				throw ApiException.unprocessable("Only active suppliers can be sent parts");
			}
			if (!supplierIds.add(sub.supplierId())) {
				throw ApiException.unprocessable(company.name() + " is listed twice; put all its parts in one sub-RFQ");
			}
			for (Long itemId : sub.itemIds()) {
				if (!partIds.contains(itemId)) {
					throw ApiException.unprocessable("A part does not belong to this RFQ");
				}
				if (taken.contains(itemId) || !chosen.add(itemId)) {
					throw ApiException.unprocessable("A part is already with a supplier");
				}
			}
		}
		rfqs.distributing(rfqId); // 409 / 422 (an incomplete RFQ is listed) before anything is saved
		int subNo = subRfqs.countByRfqId(rfqId);
		for (SubRfqForm sub : form.subRfqs()) {
			SubRfq subRfq = new SubRfq();
			subRfq.rfqId = rfqId;
			subRfq.subNo = ++subNo;
			subRfq.supplierCompanyId = sub.supplierId();
			subRfq.responseDueDate = form.responseDueDate();
			subRfq.sentByUserId = me.userId();
			subRfqs.save(subRfq);
			Invitation first = null;
			for (ItemRef item : rfq.items()) {
				if (!sub.itemIds().contains(item.id())) {
					continue;
				}
				Invitation invitation = new Invitation();
				invitation.rfqId = rfqId;
				invitation.rfqItemId = item.id();
				invitation.subRfqId = subRfq.id;
				invitation.supplierCompanyId = sub.supplierId();
				invitation.assignedByUserId = me.userId();
				invitation.responseDueDate = form.responseDueDate();
				invitation.status = NOT_STARTED;
				invitations.save(invitation);
				first = first == null ? invitation : first;
			}
			notifier.send(NotificationKind.QUOTE_REQUESTED, "quotation", first.id, rfq.number(),
					"Respond by " + form.responseDueDate(), supplierSide(first));
		}
		progress(rfqId);
		return forRfq(rfqId);
	}

	/** A part's status per audience: ERGO the full status, the RFQ's customer where its quotation stands. */
	@Transactional(readOnly = true)
	public List<PartStatusRow> partStatuses(long rfqId) {
		CurrentUser me = CurrentUser.get();
		ModuleSwitch.requireReadable(Module.QUOTATION);
		if (me.companyType() == CompanyType.CUSTOMER) {
			var rfq = rfqs.forCustomer(rfqId); // 404 unless it is this customer's RFQ
			Map<Long, Invitation> byItem = activeByItem(rfqId);
			return rfq.items().stream().map(i -> {
				Invitation inv = byItem.get(i.id());
				return new PartStatusRow(i.id(), i.lineNo(), i.partNumber(), i.revision(),
						(inv == null ? QuoteStatus.NOT_STARTED : inv.status).forCustomer().name());
			}).toList();
		}
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.forbidden("Part status is for ERGO and the RFQ's customer");
		}
		RfqRef rfq = rfqs.forErgo(rfqId);
		Map<Long, Invitation> byItem = activeByItem(rfqId);
		return rfq.items().stream().map(i -> {
			Invitation inv = byItem.get(i.id());
			return new PartStatusRow(i.id(), i.lineNo(), i.partNumber(), i.revision(),
					inv == null ? "UNASSIGNED" : inv.status.name());
		}).toList();
	}

	// ---------------------------------------------------------------- supplier: the parts sent to me

	/**
	 * One page of the supplier's parts, newest first. Filtered by the supplier's own status vocabulary and searched by
	 * RFQ number, part number or description (the RFQ module answers that last part, over this supplier's parts only).
	 */
	@Transactional(readOnly = true)
	public QuoteDtos.SupplierPage<?> mine(int page, int size, String status, String q) {
		CurrentUser me = CurrentUser.get();
		boolean withQuotes = quoteVisible(me); // without Quotation access only the assigned part is listed, never its quote
		int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		int pageNo = Math.min(Math.max(page, 0), Integer.MAX_VALUE / pageSize - 1); // the offset must fit an int, else a 500
		long supplier = me.companyId();
		Specification<Invitation> spec = (root, query, cb) -> cb.and(cb.equal(root.get("supplierCompanyId"), supplier),
				cb.notEqual(root.get("status"), RECALLED));
		if (withQuotes && status != null && !status.isBlank()) {
			QuoteStatus wanted;
			try {
				wanted = QuoteStatus.valueOf(status);
			}
			catch (IllegalArgumentException e) {
				throw ApiException.unprocessable("That status is not one you can filter by");
			}
			Set<QuoteStatus> in = Arrays.stream(QuoteStatus.values()).filter(s -> s.forSupplier() == wanted)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(QuoteStatus.class)));
			spec = spec.and((root, query, cb) -> root.get("status").in(in));
		}
		String needle = q == null ? "" : q.replaceAll("\\p{Cntrl}", "").strip(); // a NUL makes Postgres reject the query
		if (!needle.isEmpty()) {
			Set<Long> hits = rfqs.matchingItems(invitations.itemIdsOf(supplier),
					needle.length() > MAX_SEARCH_LENGTH ? needle.substring(0, MAX_SEARCH_LENGTH) : needle);
			if (hits.isEmpty()) {
				return new QuoteDtos.SupplierPage<>(List.of(), 0, pageNo, pageSize);
			}
			spec = spec.and((root, query, cb) -> root.get("rfqItemId").in(hits));
		}
		Page<Invitation> found = invitations.findAll(spec, PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "id")));
		List<?> rows = withQuotes ? quoteRows(found.getContent()) : assignedRfqs(found.getContent());
		return new QuoteDtos.SupplierPage<>(rows, found.getTotalElements(), pageNo, pageSize);
	}

	private List<Invitation> myInvitations() {
		return invitations.findBySupplierCompanyIdOrderByIdDesc(CurrentUser.get().companyId()).stream()
			.filter(i -> i.status != RECALLED)
			.toList();
	}

	private Map<Long, SubRfq> subsOf(List<Invitation> list) {
		return subRfqs.findByIdIn(list.stream().map(i -> i.subRfqId).collect(Collectors.toSet())).stream()
			.collect(Collectors.toMap(s -> s.id, Function.identity()));
	}

	private List<SupplierAssignedRfqRow> assignedRfqs(List<Invitation> mine) {
		Map<Long, SupplierRfqItem> parts = rfqs.forSupplierItems(mine.stream().map(i -> i.rfqItemId).toList());
		Map<Long, SubRfq> subs = subsOf(mine);
		return mine.stream().filter(i -> parts.containsKey(i.rfqItemId)).map(i -> { // none while the RFQ module is hidden
			SupplierRfqItem p = parts.get(i.rfqItemId);
			return new SupplierAssignedRfqRow(i.id, p.rfqNumber(), supplierSubNo(subs.get(i.subRfqId)), p.partNumber(),
					p.revision(), p.description(), i.responseDueDate, p.quotingOpen());
		}).toList();
	}

	/** The dashboard's view of the supplier's parts: what the row shows and the overdue flag, and nothing else (no quote
	 * versions, sub-RFQ numbers or part detail, which the list rows carry). */
	@Transactional(readOnly = true)
	List<SupplierQuoteItem> dashboardRows() {
		List<Invitation> mine = myInvitations();
		Map<Long, SupplierItemRef> parts = rfqs.supplierItemRefs(mine.stream().map(i -> i.rfqItemId).toList());
		LocalDate today = LocalDate.now();
		return mine.stream().filter(i -> parts.containsKey(i.rfqItemId)).map(i -> { // none while the RFQ module is hidden
			SupplierItemRef p = parts.get(i.rfqItemId);
			return new SupplierQuoteItem(i.id, p.rfqNumber(), p.partNumber(), i.status.forSupplier().name(), i.responseDueDate,
					p.quotingOpen() && i.responseDueDate != null && i.responseDueDate.isBefore(today)
							&& QuoteStatus.EDITABLE.contains(i.status.forSupplier()));
		}).toList();
	}

	private List<SupplierQuoteRow> quoteRows(List<Invitation> mine) {
		Map<Long, SupplierRfqItem> parts = rfqs.forSupplierItems(mine.stream().map(i -> i.rfqItemId).toList());
		Map<Long, SubRfq> subs = subsOf(mine);
		Map<Long, SupplierQuote> byInvitation = reader.byInvitation(mine);
		return mine.stream().filter(i -> parts.containsKey(i.rfqItemId)).map(i -> { // none while the RFQ module is hidden
			SupplierRfqItem p = parts.get(i.rfqItemId);
			SupplierQuote q = byInvitation.get(i.id);
			return new SupplierQuoteRow(i.id, p.rfqNumber(), supplierSubNo(subs.get(i.subRfqId)), p.partNumber(), p.revision(),
					p.description(), i.responseDueDate, i.status.forSupplier(), q == null ? 0 : q.currentVersion,
					q == null ? null : q.submittedAt, p.quotingOpen());
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
				                                     submitted_by_user_id, reopen_reason, reopened_by_user_id, reopened_at,
				                                     acting_admin_user_id)
				VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)""", quote.id, quote.currentVersion,
				json.writeValueAsString(data(quote)), files, me.userId(), quote.reopenReason, quote.reopenedByUserId,
				quote.reopenedAt == null ? null : OffsetDateTime.ofInstant(quote.reopenedAt, ZoneOffset.UTC),
				me.impersonatorId());
		quote.reopenReason = null;
		quote.reopenedByUserId = null;
		quote.reopenedAt = null;
		invitation.status = resubmission ? RESUBMITTED : SUBMITTED;
		invitation.quoteEditOpen = false;
		audit.record(Invitation.class, invitation.id, "QUOTE_SUBMITTED", "version", null,
				String.valueOf(quote.currentVersion));
		progress(invitation.rfqId);
		tellErgo(NotificationKind.QUOTE_SUBMITTED, invitation, resubmission ? "Revised quote" : null);
		return view(me, invitation);
	}

	// ---------------------------------------------------------------- ERGO actions

	/**
	 * Flow doc §4 "Revision required": the quote goes back to the supplier with the required changes or comments (a
	 * reason is required, SRS §14.1); the next submission becomes a new version carrying it.
	 */
	public QuoteView requestRevision(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		require(QuoteAction.REQUEST_REVISION, me, invitation);
		String why = required(comment, "Tell the supplier what to change");
		audit.reason(why);
		SupplierQuote quote = quotes.findByAssignmentId(invitation.id).orElseThrow();
		quote.reopenReason = why;
		quote.reopenedByUserId = me.userId();
		quote.reopenedAt = Instant.now();
		invitation.status = QuoteStatus.REVISION_REQUIRED;
		invitation.quoteEditOpen = true;
		progress(invitation.rfqId);
		notifier.send(NotificationKind.QUOTE_REOPENED, "quotation", invitation.id, quoteLabel(invitation),
				"Revision required: " + partLabel(invitation), supplierSide(invitation));
		return view(me, invitation);
	}

	/** Flow doc §4 "Accepted for customer submission": the part's quote is ready for the customer quotation. */
	public QuoteView accept(long id) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		require(QuoteAction.ACCEPT, me, invitation);
		invitation.status = QuoteStatus.ACCEPTED;
		progress(invitation.rfqId);
		return view(me, invitation);
	}

	/**
	 * ERGO takes a part back from its supplier (a reason is required) so it can be sent to another one; the supplier's
	 * quote and its versions stay on record. Not possible once the part is with the customer or decided.
	 */
	public QuoteView recall(long id, String reason) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		require(QuoteAction.RECALL, me, invitation);
		invitation.recallReason = required(reason, "A reason is required to take a part back from a supplier");
		audit.reason(invitation.recallReason);
		invitation.status = RECALLED;
		invitation.quoteEditOpen = false;
		progress(invitation.rfqId);
		return view(me, invitation);
	}

	// ---------------------------------------------------------------- versions and files

	@Transactional(readOnly = true)
	public List<VersionView> versions(long id) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		SupplierQuote quote = quoteVisible(me) ? quotes.findByAssignmentId(invitation.id).orElse(null) : null;
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
			rfqs.supplierDocuments(invitation.rfqId, Set.of(invitation.rfqItemId)).forEach(d -> result.add(documentView(me, "RFQ", d, false)));
		}
		documents.list(MODULE, invitation.id).stream().filter(d -> quoteVisible(me) && canSee(me, invitation, d, submitted))
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
		// No notification: a supplier's file stays a private draft until its quote is submitted (QuoteService.canSee,
		// security review 6e H1) - QUOTE_SUBMITTED tells ERGO then. ERGO's own files here are internal.
		return documentView(me, "QUOTE",
				documents.store(MODULE, invitation.id, fileName, content, Visibility.INTERNAL), true);
	}

	@Transactional(readOnly = true)
	public DocumentFile download(long id, String source, long documentId) {
		CurrentUser me = CurrentUser.get();
		Invitation invitation = visible(me, id);
		DocumentInfo document = "RFQ".equals(source) && me.companyType() == CompanyType.SUPPLIER
				? rfqs.supplierDocuments(invitation.rfqId, Set.of(invitation.rfqItemId)).stream().filter(d -> d.id() == documentId).findFirst()
					.orElse(null)
				: documents.find(MODULE, invitation.id, documentId)
					.filter(d -> quoteVisible(me) && canSee(me, invitation, d, submittedFiles(invitation)))
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

	/** Dashboard "Quotes pending submission" widget (ARCHITECTURE.md §8, 2026-10-05 Phase 7c security review L2):
	 * same {@code quotingOpen} filter {@link #overdue} already uses, so an invitation on a cancelled/closed RFQ
	 * doesn't inflate the count forever. */
	int pendingSubmissionCount() {
		if (!ModuleSwitch.readable(Module.RFQ)) {
			return 0;
		}
		List<Invitation> editable = invitations.findByStatusIn(QuoteStatus.EDITABLE);
		Map<Long, SupplierRfqItem> parts = rfqs.forSupplierItems(editable.stream().map(i -> i.rfqItemId).toList());
		return (int) editable.stream().filter(i -> parts.containsKey(i.rfqItemId) && parts.get(i.rfqItemId).quotingOpen())
			.count();
	}

	// ---------------------------------------------------------------- notifications (SRS §14.9)

	/** The daily reminder scan: parts past their response date whose supplier can still quote and whose RFQ is
	 * still open for quotes. The supplier and ERGO's RFQ team are reminded; the customer never is. */
	List<com.ergo.connect.common.event.OverdueItem> overdue(LocalDate today) {
		if (!ModuleSwitch.readable(Module.RFQ)) {
			return List.of();
		}
		List<Invitation> late = invitations.findByResponseDueDateBeforeAndStatusIn(today, QuoteStatus.EDITABLE);
		Map<Long, SupplierRfqItem> parts = rfqs.forSupplierItems(late.stream().map(i -> i.rfqItemId).toList());
		return late.stream()
			.filter(i -> parts.containsKey(i.rfqItemId) && parts.get(i.rfqItemId).quotingOpen())
			.map(i -> new com.ergo.connect.common.event.OverdueItem(NotificationKind.OVERDUE_QUOTE, "quotation", i.id,
					parts.get(i.rfqItemId).rfqNumber(), "Response was due " + i.responseDueDate + ": "
							+ parts.get(i.rfqItemId).partNumber(),
					supplierSide(i).and(Recipients.ergo(Role.ERGO_ADMIN, Role.ERGO_RFQ)), "quote:" + i.id))
			.toList();
	}

	/** The RFQ's number, which the supplier already sees on its own quote; never the customer. */
	private String quoteLabel(Invitation invitation) {
		return rfqs.forSupplierItem(invitation.rfqItemId).rfqNumber();
	}

	private String partLabel(Invitation invitation) {
		return rfqs.forSupplierItem(invitation.rfqItemId).partNumber();
	}

	private void tellErgo(NotificationKind kind, Invitation invitation, String detail) {
		String part = partLabel(invitation);
		notifier.send(kind, "quotation", invitation.id, quoteLabel(invitation),
				detail == null ? part : detail + ": " + part, Recipients.ergo(Role.ERGO_ADMIN, Role.ERGO_RFQ));
	}

	private Recipients supplierSide(Invitation invitation) {
		return Recipients.company(invitation.supplierCompanyId, Role.SUPPLIER_RFQ, Role.SUPPLIER_KAM);
	}

	// ---------------------------------------------------------------- rules

	/**
	 * The number a supplier sees for its sub-RFQ: counted among its own sub-RFQs of the RFQ, so it cannot tell how many
	 * other suppliers the RFQ went to.
	 */
	private int supplierSubNo(SubRfq sub) {
		return 1 + (int) subRfqs.findByRfqIdOrderBySubNo(sub.rfqId).stream()
			.filter(s -> s.supplierCompanyId.equals(sub.supplierCompanyId) && s.subNo < sub.subNo)
			.count();
	}

	/** The parts currently with a supplier (a recalled part is free again), by RFQ item id. */
	private Map<Long, Invitation> activeByItem(long rfqId) {
		Map<Long, Invitation> result = new LinkedHashMap<>();
		for (Invitation i : invitations.findByRfqIdOrderById(rfqId)) {
			if (i.status != RECALLED) {
				result.put(i.rfqItemId, i);
			}
		}
		return result;
	}

	private static PartRef partRef(ItemRef item) {
		return new PartRef(item.id(), item.lineNo(), item.partNumber(), item.revision(), item.description());
	}

	/** Record scope. Out-of-scope quotes do not exist for the caller (404). */
	Invitation visible(CurrentUser me, long id) {
		Invitation invitation = invitations.findById(id).orElse(null);
		if (!inScope(me, invitation)) {
			throw ApiException.notFound("Quote");
		}
		return invitation;
	}

	/** The boolean half of {@link #visible}, for a caller that must not throw on "out of scope" (a thrown {@code
	 * ApiException} from a {@code @Transactional} method marks the physical transaction rollback-only the moment it
	 * is thrown, even if the caller catches it immediately afterward). Used by {@code
	 * QuotationApiService#visibleToCaller}, the documents module's non-throwing scope probe (ARCHITECTURE.md §8,
	 * 2026-10-02 Phase 6a) - note the ERGO case below calls {@code RfqApi#visibleToCaller} (itself non-throwing)
	 * instead of {@code RfqApi#forErgo} for the same reason. */
	boolean inScope(CurrentUser me, long id) {
		return inScope(me, invitations.findById(id).orElse(null));
	}

	/** Security review M2: one file of the quote as the caller may see it (see {@code canSee}); drafts stay private. */
	boolean fileVisible(CurrentUser me, long invitationId, long documentId) {
		Invitation invitation = invitations.findById(invitationId).orElse(null);
		return quoteVisible(me) && inScope(me, invitation) && documents.find(MODULE, invitationId, documentId)
			.filter(d -> canSee(me, invitation, d, submittedFiles(invitation)))
			.isPresent();
	}

	/** H4 fix (ARCHITECTURE.md §8, Phase 6a security review): whether this module's own UPLOAD rule currently
	 * permits a new file for this invitation - used by the documents module's generic {@code replace} so it can't
	 * be used to bypass this module's own edit window (e.g. a supplier replacing its quote file after submission,
	 * which {@code quoteEditOpen=false} already forbids). */
	boolean uploadOpenForCaller(CurrentUser me, long invitationId) {
		Invitation invitation = invitations.findById(invitationId).orElse(null);
		return invitation != null && allowed(QuoteAction.UPLOAD, me, invitation, rfqState(me, invitation));
	}

	private boolean inScope(CurrentUser me, Invitation invitation) {
		return invitation != null && invitation.status != RECALLED && switch (me.companyType()) {
			case SUPPLIER -> ModuleAccess.canView(me.role(), Module.RFQ) && invitation.supplierCompanyId == me.companyId();
			case ERGO -> ERGO_VIEWERS.contains(me.role()) && rfqs.visibleToCaller(invitation.rfqId);
			case CUSTOMER -> false; // customers only ever see what ERGO generates for them
		};
	}

	/**
	 * The rule book: SRS §10.5 rights, §14.3 statuses (per part), §17.2 edit rules (edit only while open) and the
	 * flow doc's review actions. Also yields each caller's buttons.
	 */
	static boolean allowed(QuoteAction action, CurrentUser me, Invitation i, RfqState rfq) {
		boolean supplier = SUPPLIER_QUOTERS.contains(me.role());
		boolean buyer = ERGO_BUYERS.contains(me.role());
		boolean evaluator = EVALUATORS.contains(me.role()) && rfq.quotingOpen();
		QuoteStatus status = i.status;
		return switch (action) {
			case EDIT, SUBMIT -> supplier && rfq.quotingOpen() && i.quoteEditOpen && QuoteStatus.EDITABLE.contains(status);
			case REQUEST_REVISION -> buyer && rfq.quotingOpen() && QuoteStatus.RETURNABLE.contains(status)
					&& (!QuoteStatus.READY_FOR_CUSTOMER.contains(status) || EVALUATORS.contains(me.role()));
			case ACCEPT -> evaluator && QuoteStatus.UNDER_REVIEW.contains(status);
			case RECALL -> buyer && rfq.quotingOpen() && status != RECALLED
					&& !QuoteStatus.WITH_CUSTOMER_OPEN.contains(status) && !QuoteStatus.DECIDED.contains(status)
					&& (!QuoteStatus.READY_FOR_CUSTOMER.contains(status) || EVALUATORS.contains(me.role()));
			case UPLOAD -> supplier ? rfq.quotingOpen() && i.quoteEditOpen && QuoteStatus.EDITABLE.contains(status)
					: buyer && status != RECALLED;
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
				? new RfqState(rfqs.forSupplierItem(invitation.rfqItemId).quotingOpen())
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
		if (q.productionPiecePrice == null || q.productionPiecePrice.signum() <= 0) {
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
	 * Reports how the RFQ's parts stand (flow doc: status per part); the RFQ module works its own status out from it.
	 * Recalled parts are not with anyone, so they count as unassigned there.
	 */
	void progress(long rfqId) {
		jdbc.queryForList("SELECT rfq_id FROM rfqs WHERE rfq_id = ? FOR UPDATE", Long.class, rfqId); // one summary at a time
		int withSupplier = 0;
		int underReview = 0;
		int accepted = 0;
		int withCustomer = 0;
		int onHold = 0;
		int correction = 0;
		int approved = 0;
		int rejected = 0;
		int sentBack = 0;
		for (Invitation i : invitations.findByRfqIdOrderById(rfqId)) {
			switch (i.status) {
				case NOT_STARTED, DRAFT_SAVED -> withSupplier++;
				case REVISION_REQUIRED -> {
					withSupplier++;
					sentBack++; // the supplier has answered once: the RFQ can be closed, no longer cancelled
				}
				case SUBMITTED, RESUBMITTED -> underReview++;
				case ACCEPTED -> accepted++;
				case WITH_CUSTOMER -> withCustomer++;
				case CUSTOMER_ON_HOLD -> onHold++;
				case CUSTOMER_CORRECTION -> correction++;
				case CUSTOMER_APPROVED -> approved++;
				case CUSTOMER_REJECTED -> rejected++;
				case RECALLED -> {
				}
			}
		}
		ModuleSwitch.requireWritable(Module.QUOTATION, Module.RFQ); // both locked until commit: no switch change mid-operation
		rfqs.quotationProgress(rfqId, new QuotationSummary(withSupplier, underReview, accepted, withCustomer, onHold,
				correction, approved, rejected, sentBack));
	}

	private QuoteView view(CurrentUser me, Invitation i) {
		SubRfq sub = subRfqs.findById(i.subRfqId).orElseThrow();
		int subNo = me.companyType() == CompanyType.SUPPLIER ? supplierSubNo(sub) : sub.subNo;
		if (me.companyType() == CompanyType.SUPPLIER && !quoteVisible(me)) {
			// A supplier role with RFQ access but none on Quotation sees the assigned part, never the quote itself.
			return new SupplierAssignedRfqView("SUPPLIER_RFQ", i.id, i.responseDueDate, subNo,
					rfqs.forSupplierItem(i.rfqItemId), Set.of());
		}
		SupplierQuote q = quotes.findByAssignmentId(i.id).orElse(null);
		QuoteData data = q == null ? null : data(q);
		int version = q == null ? 0 : q.currentVersion;
		Instant submittedAt = q == null ? null : q.submittedAt;
		String revisionReason = q == null ? null : q.reopenReason;
		if (me.companyType() == CompanyType.SUPPLIER) {
			SupplierRfqItem part = rfqs.forSupplierItem(i.rfqItemId);
			return new SupplierQuoteView("SUPPLIER", i.id, i.status.forSupplier(), i.responseDueDate, subNo, part, data,
					version, submittedAt, revisionReason,
					actions(me, i, new RfqState(part.quotingOpen())));
		}
		RfqRef rfq = rfqs.forErgo(i.rfqId);
		CompanyRef supplier = directory.company(i.supplierCompanyId).orElseThrow();
		Map<Long, UserRef> users = directory.users(List.of(i.assignedByUserId));
		PartRef part = rfq.items().stream().filter(p -> p.id() == i.rfqItemId).findFirst().map(QuoteService::partRef)
			.orElseThrow();
		// ERGO sees the last submitted version, never the supplier's unsent changes
		QuoteData ergoData = q == null ? null : reader.lastSubmitted(List.of(q)).get(q.id);
		return new ErgoQuoteView("ERGO", i.id, i.status, i.responseDueDate, rfq.id(), rfq.number(), subNo,
				new Named(supplier.id(), supplier.code(), supplier.name()), part, userName(users, i.assignedByUserId),
				i.createdAt, ergoData, version, submittedAt, revisionReason, i.recallReason,
				actions(me, i, RfqState.of(rfq)));
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

	private static boolean quoteVisible(CurrentUser me) {
		return ModuleAccess.canView(me.role(), Module.QUOTATION);
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
		return com.ergo.connect.common.validation.Blank.is(value) ? null : value.trim();
	}

	private static String required(String value, String message) {
		String text = text(value);
		if (text == null) {
			throw ApiException.unprocessable(message);
		}
		return text;
	}

}
