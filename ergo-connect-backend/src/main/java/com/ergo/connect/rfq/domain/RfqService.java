package com.ergo.connect.rfq.domain;

import static com.ergo.connect.common.security.Role.CUSTOMER_KAM;
import static com.ergo.connect.common.security.Role.CUSTOMER_RFQ;
import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_RFQ;
import static com.ergo.connect.rfq.domain.RfqStatus.CANCELLED;
import static com.ergo.connect.rfq.domain.RfqStatus.CLARIFICATION_REQUIRED;
import static com.ergo.connect.rfq.domain.RfqStatus.CLOSED;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_APPROVED;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_ON_HOLD;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_REVISION_REQUESTED;
import static com.ergo.connect.rfq.domain.RfqStatus.DRAFT;
import static com.ergo.connect.rfq.domain.RfqStatus.QUOTES_COMPLETE;
import static com.ergo.connect.rfq.domain.RfqStatus.QUOTES_PARTIAL;
import static com.ergo.connect.rfq.domain.RfqStatus.SENT_TO_CUSTOMER;
import static com.ergo.connect.rfq.domain.RfqStatus.SENT_TO_SUPPLIERS;
import static com.ergo.connect.rfq.domain.RfqStatus.SUBMITTED;
import static com.ergo.connect.rfq.domain.RfqStatus.UNDER_REVIEW;

import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.audit.AuditLogService.AuditEntry;
import com.ergo.connect.common.event.NotificationKind;
import com.ergo.connect.common.event.Notifier;
import com.ergo.connect.common.event.Recipients;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.ModuleAccess;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.documents.api.DocumentStore;
import com.ergo.connect.documents.api.DocumentStore.DocumentInfo;
import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;
import com.ergo.connect.masterdata.api.MasterDataApi;
import com.ergo.connect.masterdata.api.MasterDataApi.Ref;
import com.ergo.connect.rfq.domain.RfqDtos.CustomerRfqRow;
import com.ergo.connect.rfq.domain.RfqDtos.CustomerRfqView;
import com.ergo.connect.rfq.domain.RfqDtos.DocumentView;
import com.ergo.connect.rfq.domain.RfqDtos.ErgoRfqRow;
import com.ergo.connect.rfq.domain.RfqDtos.ErgoRfqView;
import com.ergo.connect.rfq.domain.RfqDtos.ItemForm;
import com.ergo.connect.rfq.domain.RfqDtos.ItemView;
import com.ergo.connect.rfq.domain.RfqDtos.Named;
import com.ergo.connect.rfq.domain.RfqDtos.RfqAction;
import com.ergo.connect.rfq.domain.RfqDtos.RfqForm;
import com.ergo.connect.rfq.domain.RfqDtos.RfqRow;
import com.ergo.connect.rfq.domain.RfqDtos.RfqView;
import com.ergo.connect.rfq.domain.RfqDtos.StepView;
import com.ergo.connect.rfq.domain.RfqDtos.TargetsView;

/**
 * RFQ lifecycle up to distribution to suppliers (SRS §5, §14.2; flow doc §1-2): the customer's RFQ with its items,
 * ERGO's review and changes, then ERGO distributes the parts to suppliers (the quotation module). Record scope
 * (SRS §10.4, §8.2): ERGO RFQ / Admin / Management see all, ERGO KAMs their assigned customers, ERGO NPD approved
 * work, customers their own company. {@link #allowed} is the single rule book: it enforces every action and lists
 * the caller's buttons.
 */
@Service
@Transactional
public class RfqService {

	static final String MODULE = "rfq";

	/** The role sheet (ModuleAccess): who creates / edits / submits ("A" and up) ... */
	private static final Set<Role> ERGO_EDITORS = ModuleAccess.actors(Module.RFQ, CompanyType.ERGO);

	/** ... and who gives the final approval ("O"): start the review, close. */
	private static final Set<Role> ERGO_REVIEWERS = ModuleAccess.approvers(Module.RFQ, CompanyType.ERGO);

	private static final Set<Role> CUSTOMER_EDITORS = ModuleAccess.actors(Module.RFQ, CompanyType.CUSTOMER);

	/** SRS §10.4 "RV" for ERGO NPD: approved RFQs heading to NPD. */
	private static final Set<RfqStatus> NPD_VISIBLE = EnumSet.of(CUSTOMER_APPROVED);

	/** While the customer may still change the RFQ. */
	private static final Set<RfqStatus> CUSTOMER_OPEN = EnumSet.of(DRAFT, SUBMITTED, CLARIFICATION_REQUIRED);

	/** Before any supplier has seen it: files may still be removed, ERGO may still cancel. */
	private static final Set<RfqStatus> BEFORE_FLOAT = EnumSet.of(DRAFT, SUBMITTED, UNDER_REVIEW,
			CLARIFICATION_REQUIRED);

	/** SRS §17.1: cancel only before any supplier response. */
	private static final Set<RfqStatus> ERGO_CANCELLABLE = EnumSet.of(DRAFT, SUBMITTED, UNDER_REVIEW,
			CLARIFICATION_REQUIRED, SENT_TO_SUPPLIERS);

	/** Once suppliers have answered, an RFQ that goes no further is closed (with a reason), not cancelled. */
	private static final Set<RfqStatus> CLOSABLE = EnumSet.of(QUOTES_PARTIAL, QUOTES_COMPLETE, SENT_TO_CUSTOMER,
			CUSTOMER_ON_HOLD, CUSTOMER_REVISION_REQUESTED);

	/** A downloadable file: metadata plus content. */
	public record DocumentFile(DocumentInfo info, byte[] content) {
	}

	private final RfqRepository rfqs;

	private final RfqItemRepository items;

	private final Directory directory;

	private final MasterDataApi masterData;

	private final DocumentStore documents;

	private final AuditLogService audit;

	private final JdbcTemplate jdbc;

	private final Notifier notifier;

	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager entityManager;

	RfqService(RfqRepository rfqs, RfqItemRepository items, Directory directory, MasterDataApi masterData,
			DocumentStore documents, AuditLogService audit, JdbcTemplate jdbc, Notifier notifier) {
		this.notifier = notifier;
		this.rfqs = rfqs;
		this.items = items;
		this.directory = directory;
		this.masterData = masterData;
		this.documents = documents;
		this.audit = audit;
		this.jdbc = jdbc;
	}

	// ---------------------------------------------------------------- reads

	@Transactional(readOnly = true)
	public List<RfqRow> list() {
		CurrentUser me = CurrentUser.get();
		List<Rfq> visible = !ModuleAccess.canView(me.role(), Module.RFQ) ? List.of() : switch (me.companyType()) {
			case ERGO -> switch (me.role()) {
				case ERGO_KAM -> {
					Set<Long> accounts = directory.accountsOf(me.userId());
					yield accounts.isEmpty() ? List.of() : rfqs.findByCustomerCompanyIdInOrderByIdDesc(accounts);
				}
				case ERGO_NPD -> rfqs.findByStatusInOrderByIdDesc(NPD_VISIBLE);
				default -> rfqs.findAllByOrderByIdDesc();
			};
			case CUSTOMER -> rfqs.findByCustomerCompanyIdOrderByIdDesc(me.companyId());
			case SUPPLIER -> List.of(); // suppliers see RFQs only as the part quotes ERGO asks them for (QuoteService)
		};
		Map<Long, List<String>> parts = partNumbers(visible);
		if (!isErgo(me)) {
			return visible.stream()
				.<RfqRow>map(r -> new CustomerRfqRow(r.id, r.number, r.status.forCustomer(),
						parts.getOrDefault(r.id, List.of()), r.targetQuoteDate, r.priority, r.createdAt))
				.toList();
		}
		Map<Long, CompanyRef> customers = directory.companies(ids(visible, r -> r.customerCompanyId));
		Map<Long, UserRef> owners = directory.users(ids(visible, r -> r.ownerUserId));
		return visible.stream()
			.<RfqRow>map(r -> new ErgoRfqRow(r.id, r.number, r.status, customers.get(r.customerCompanyId).name(),
					parts.getOrDefault(r.id, List.of()), r.targetQuoteDate, r.priority,
					userName(owners, r.ownerUserId), r.createdAt))
			.toList();
	}

	@Transactional(readOnly = true)
	public RfqView get(long id) {
		CurrentUser me = CurrentUser.get();
		return view(visible(me, id), me);
	}

	/**
	 * SRS §15.5 audit trail tab. ERGO only: it contains internal notes and internal steps. Includes the changes made
	 * to the RFQ's parts, each labelled with its part number.
	 */
	@Transactional(readOnly = true)
	public List<AuditEntry> history(long id) {
		CurrentUser me = CurrentUser.get();
		visible(me, id);
		if (!isErgo(me)) {
			throw ApiException.forbidden("The audit trail is internal to ERGO");
		}
		List<AuditEntry> result = new ArrayList<>(audit.history(MODULE, "Rfq", id));
		for (RfqItem item : items.findByRfqIdOrderByLineNo(id)) {
			for (AuditEntry e : audit.history(MODULE, "RfqItem", item.id)) {
				String field = e.field() == null ? "Part " + item.partNumber : "Part " + item.partNumber + ": " + e.field();
				result.add(new AuditEntry(e.at(), e.user(), e.role(), e.event(), field, e.oldValue(), e.newValue(),
						e.reason(), e.channel()));
			}
		}
		result.sort(Comparator.comparing(AuditEntry::at).reversed());
		return result;
	}

	// ---------------------------------------------------------------- create and edit

	public RfqView create(RfqForm form) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = new Rfq();
		rfq.customerCompanyId = customerFor(me, form.customerCompanyId());
		rfq.number = "RFQ-%d-%05d".formatted(Year.now().getValue(),
				jdbc.queryForObject("SELECT nextval('rfq_number_seq')", Long.class));
		rfq.sourceType = isErgo(me) ? SourceType.ERGO : SourceType.CUSTOMER;
		rfq.createdByUserId = me.userId();
		rfq.status = DRAFT;
		applyHeader(rfq, form, me);
		rfqs.save(rfq);
		syncItems(rfq, form.items());
		return view(rfq, me);
	}

	/** ERGO may change anything while reviewing (flow doc §2), the customer while the RFQ is still its own. */
	public RfqView update(long id, RfqForm form) {
		CurrentUser me = CurrentUser.get();
		jdbc.queryForList("SELECT rfq_id FROM rfqs WHERE rfq_id = ? FOR UPDATE", Long.class, id); // not while ERGO distributes
		entityManager.clear();
		Rfq rfq = visible(me, id);
		require(RfqAction.EDIT, me, rfq);
		applyHeader(rfq, form, me);
		syncItems(rfq, form.items());
		return view(rfq, me);
	}

	public RfqView updateNotes(long id, String internalNotes) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.EDIT_NOTES, me, rfq);
		rfq.internalNotes = text(internalNotes);
		return view(rfq, me);
	}

	/** A new draft with the same content, for re-quotes or variants (SRS §12.6.3 "Clone RFQ"). */
	public RfqView cloneRfq(long id) {
		CurrentUser me = CurrentUser.get();
		Rfq source = visible(me, id);
		require(RfqAction.CLONE, me, source);
		Rfq copy = new Rfq();
		copy.number = "RFQ-%d-%05d".formatted(Year.now().getValue(),
				jdbc.queryForObject("SELECT nextval('rfq_number_seq')", Long.class));
		copy.sourceType = isErgo(me) ? SourceType.ERGO : SourceType.CUSTOMER;
		copy.customerCompanyId = source.customerCompanyId;
		copy.createdByUserId = me.userId();
		copy.status = DRAFT;
		copy.priority = source.priority;
		copy.currencyId = source.currencyId;
		copy.paymentTermId = source.paymentTermId;
		copy.customerRemarks = source.customerRemarks;
		rfqs.save(copy);
		for (RfqItem from : items.findByRfqIdOrderByLineNo(source.id)) {
			RfqItem to = new RfqItem();
			to.rfqId = copy.id;
			to.lineNo = from.lineNo;
			to.partNumber = from.partNumber;
			to.revision = from.revision;
			to.description = from.description;
			to.eau = from.eau;
			to.netWeightKg = from.netWeightKg;
			to.grossWeightKg = from.grossWeightKg;
			to.materialId = from.materialId;
			to.materialSpec = from.materialSpec;
			to.coatingId = from.coatingId;
			to.coatingSpec = from.coatingSpec;
			to.targetToolingCost = from.targetToolingCost;
			to.targetPpapPiecePrice = from.targetPpapPiecePrice;
			to.targetProductionPiecePrice = from.targetProductionPiecePrice;
			to.materialLeadTimeWeeks = from.materialLeadTimeWeeks;
			to.toolingLeadTimeDays = from.toolingLeadTimeDays;
			to.sampleLeadTimeDays = from.sampleLeadTimeDays;
			to.productionLeadTimeDays = from.productionLeadTimeDays;
			to.moq = from.moq;
			to.processSteps.addAll(from.processSteps);
			items.save(to);
		}
		audit.record(Rfq.class, copy.id, "CLONED_FROM", "rfq", null, source.number);
		return view(copy, me);
	}

	// ---------------------------------------------------------------- workflow (SRS §14.2)

	/** Draft → Submitted, or (answering a clarification) → Under ERGO Review. */
	public RfqView submit(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.SUBMIT, me, rfq);
		requireComplete(rfq, items.findByRfqIdOrderByLineNo(rfq.id));
		if (rfq.status == CLARIFICATION_REQUIRED) {
			rfq.clarificationAnswer = required(comment, "Answer the clarification question before resubmitting");
			audit.reason(rfq.clarificationAnswer);
			rfq.status = UNDER_REVIEW;
			tellErgo(NotificationKind.RFQ_SUBMITTED, rfq, "Clarification answered");
		}
		else {
			reasonIfGiven(comment);
			rfq.status = SUBMITTED;
			tellErgo(NotificationKind.RFQ_SUBMITTED, rfq, null);
		}
		return view(rfq, me);
	}

	public RfqView startReview(long id) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.START_REVIEW, me, rfq);
		rfq.status = UNDER_REVIEW;
		if (rfq.ownerUserId == null) {
			rfq.ownerUserId = me.userId();
		}
		statusChanged(me, rfq, "Under review");
		return view(rfq, me);
	}

	public RfqView requestClarification(long id, String question) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.REQUEST_CLARIFICATION, me, rfq);
		rfq.clarificationQuestion = required(question, "Write the question for the customer");
		rfq.clarificationAnswer = null;
		audit.reason(rfq.clarificationQuestion);
		rfq.status = CLARIFICATION_REQUIRED;
		tellCustomer(NotificationKind.RFQ_CLARIFICATION_REQUESTED, rfq, null);
		return view(rfq, me);
	}

	/** An RFQ that will not go further after suppliers have been involved (SRS §14.2 Closed). */
	public RfqView close(long id, String reason) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.CLOSE, me, rfq);
		audit.reason(required(reason, "A reason is required to close an RFQ"));
		rfq.status = CLOSED;
		statusChanged(me, rfq, "Closed");
		return view(rfq, me);
	}

	/** SRS §17.1: only before any supplier response. */
	public RfqView cancel(long id, String reason) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.CANCEL, me, rfq);
		audit.reason(required(reason, "A reason is required to cancel an RFQ"));
		rfq.status = CANCELLED;
		statusChanged(me, rfq, "Cancelled");
		return view(rfq, me);
	}

	// ---------------------------------------------------------------- notifications (SRS §14.9)

	private void tellCustomer(NotificationKind kind, Rfq rfq, String detail) {
		notifier.send(kind, MODULE, rfq.id, rfq.number, detail,
				Recipients.company(rfq.customerCompanyId, CUSTOMER_RFQ, CUSTOMER_KAM));
	}

	private void tellErgo(NotificationKind kind, Rfq rfq, String detail) {
		notifier.send(kind, MODULE, rfq.id, rfq.number, detail, Recipients.ergo(ERGO_ADMIN, ERGO_RFQ));
	}

	/** Whoever did not cause the change hears about it: the customer when ERGO acted, ERGO when the customer did. */
	private void statusChanged(CurrentUser me, Rfq rfq, String detail) {
		if (isErgo(me)) {
			tellCustomer(NotificationKind.RFQ_STATUS_CHANGED, rfq, detail);
		}
		else {
			tellErgo(NotificationKind.RFQ_STATUS_CHANGED, rfq, detail);
		}
	}

	// ---------------------------------------------------------------- documents (drawings etc.)

	@Transactional(readOnly = true)
	public List<DocumentView> documents(long id) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		return documents.list(MODULE, id).stream().filter(d -> canSee(me, d)).map(d -> docView(me, rfq, d)).toList();
	}

	/** The parts a file is shared with suppliers for (ERGO sees it; nobody else needs it). */
	private List<Long> partsOf(long documentId) {
		return jdbc.queryForList("SELECT rfq_item_id FROM rfq_document_parts WHERE document_id = ? ORDER BY rfq_item_id",
				Long.class, documentId);
	}

	private DocumentView docView(CurrentUser me, Rfq rfq, DocumentInfo d) {
		return documentView(me, rfq, d, isErgo(me) ? partsOf(d.id()) : List.of());
	}

	/**
	 * A file shared with suppliers is shared per part (flow doc: a supplier sees only the parts sent to it, so a
	 * drawing of one part must not reach the supplier of another). Any other visibility shares it for no part.
	 */
	private void sharePerPart(Rfq rfq, long documentId, Visibility visibility, Collection<Long> itemIds) {
		jdbc.update("DELETE FROM rfq_document_parts WHERE document_id = ?", documentId);
		if (visibility != Visibility.SUPPLIER && visibility != Visibility.ALL) {
			return;
		}
		Set<Long> chosen = itemIds == null ? Set.of() : new HashSet<>(itemIds);
		if (chosen.isEmpty()) {
			throw ApiException.unprocessable("Choose the parts this file is shared with suppliers for");
		}
		Set<Long> own = items.findByRfqIdOrderByLineNo(rfq.id).stream().map(i -> i.id).collect(Collectors.toSet());
		if (!own.containsAll(chosen)) {
			throw ApiException.unprocessable("A part does not belong to this RFQ");
		}
		for (Long itemId : chosen) {
			jdbc.update("INSERT INTO rfq_document_parts (document_id, rfq_item_id) VALUES (?, ?)", documentId, itemId);
		}
	}

	/**
	 * Customers' uploads are visible to ERGO and themselves; only ERGO decides what suppliers see (client decision:
	 * the customer's identity is hidden from suppliers, so ERGO may share a cleaned copy instead).
	 */
	public DocumentView upload(long id, String fileName, byte[] content, Visibility requested, Collection<Long> itemIds) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.UPLOAD, me, rfq);
		Visibility visibility = isErgo(me) ? (requested == null ? Visibility.INTERNAL : requested) : Visibility.CUSTOMER;
		DocumentInfo stored = documents.store(MODULE, id, fileName, content, visibility);
		sharePerPart(rfq, stored.id(), visibility, itemIds);
		// SRS §14.9 "document upload": ERGO hears about a customer's file; the customer hears about a file ERGO shares.
		// Linked to the document itself, so delivery re-checks the file's own visibility, not just the RFQ's. Files ERGO
		// shares with suppliers are not announced (suppliers have no RFQ screen; they see shared drawings on their quote).
		Recipients to = !isErgo(me) ? Recipients.ergo(ERGO_ADMIN, ERGO_RFQ)
				: visibility == Visibility.CUSTOMER || visibility == Visibility.ALL
						? Recipients.company(rfq.customerCompanyId, CUSTOMER_RFQ, CUSTOMER_KAM) : Recipients.none();
		notifier.send(NotificationKind.DOCUMENT_UPLOADED, "document", stored.id(), rfq.number, stored.fileName(), to);
		return docView(me, rfq, stored);
	}

	@Transactional(readOnly = true)
	public DocumentFile download(long id, long documentId) {
		CurrentUser me = CurrentUser.get();
		visible(me, id);
		DocumentInfo document = document(me, id, documentId);
		return new DocumentFile(document, documents.content(document));
	}

	public DocumentView setVisibility(long id, long documentId, Visibility visibility, Collection<Long> itemIds) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		if (!ERGO_EDITORS.contains(me.role())) {
			throw ApiException.forbidden("Only ERGO decides who sees a document");
		}
		DocumentInfo document = document(me, id, documentId);
		documents.setVisibility(document, visibility); // refuses a locked document first
		sharePerPart(rfq, documentId, visibility, itemIds);
		return docView(me, rfq, document(me, id, documentId));
	}

	public void deleteDocument(long id, long documentId) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		DocumentInfo document = document(me, id, documentId);
		if (!canDelete(me, rfq, document)) {
			throw ApiException.conflict("This document can no longer be removed; upload a new version instead");
		}
		documents.delete(document);
	}

	// ---------------------------------------------------------------- rules

	/** Record scope. Out-of-scope RFQs do not exist for the caller (404, not 403). {@code CUSTOMER_NPD}/
	 * {@code CUSTOMER_QUALITY} are read-only here (SRS §10.6 NPD, not §10.4 RFQ rights): {@link #allowed} still never
	 * grants them an RFQ action, this only lets NPD's own {@code RfqApi.forCustomer} calls on their own project's RFQ
	 * resolve instead of always 404ing (npd.domain.NpdService, ARCHITECTURE.md §8 2026-09-26 3b). */
	Rfq visible(CurrentUser me, long id) {
		Rfq rfq = rfqs.findById(id).orElse(null);
		if (!inScope(me, rfq)) {
			throw ApiException.notFound("RFQ");
		}
		return rfq;
	}

	/** The boolean half of {@link #visible}, for a caller that must not throw on "out of scope" (a thrown {@code
	 * ApiException} from a {@code @Transactional} method marks the physical transaction rollback-only the moment it
	 * is thrown, even if the caller catches it immediately afterward - Spring's default propagation does not care
	 * that the exception never escapes the caller). Used by {@code RfqApiService#visibleToCaller}, the documents
	 * module's non-throwing scope probe (ARCHITECTURE.md §8, 2026-10-02 Phase 6a). */
	boolean inScope(CurrentUser me, long id) {
		return inScope(me, rfqs.findById(id).orElse(null));
	}

	/** H4 fix (ARCHITECTURE.md §8, Phase 6a security review): whether this module's own UPLOAD rule currently
	 * permits a new file for this RFQ - used by the documents module's generic {@code replace} so it can't be used
	 * to bypass this module's own edit window (e.g. a customer replacing a drawing after the RFQ has moved past the
	 * open/under-review window this rule actually checks). */
	boolean uploadOpen(CurrentUser me, long rfqId) {
		Rfq rfq = rfqs.findById(rfqId).orElse(null);
		return rfq != null && allowed(RfqAction.UPLOAD, me, rfq);
	}

	private boolean inScope(CurrentUser me, Rfq rfq) {
		return rfq != null && ModuleAccess.canView(me.role(), Module.RFQ) && switch (me.companyType()) {
			case ERGO -> switch (me.role()) {
				case ERGO_KAM -> directory.accountsOf(me.userId()).contains(rfq.customerCompanyId);
				case ERGO_NPD -> NPD_VISIBLE.contains(rfq.status);
				default -> true;
			};
			case CUSTOMER -> rfq.customerCompanyId == me.companyId();
			case SUPPLIER -> false;
		};
	}

	/** The rule book: SRS §10.4 rights combined with the §14.2 status and the §17.1 page logic. */
	private static boolean allowed(RfqAction action, CurrentUser me, Rfq rfq) {
		Role role = me.role();
		boolean customer = CUSTOMER_EDITORS.contains(role);
		boolean ergo = ERGO_EDITORS.contains(role);
		boolean reviewer = ERGO_REVIEWERS.contains(role);
		RfqStatus status = rfq.status;
		return switch (action) {
			case EDIT -> CUSTOMER_OPEN.contains(status) ? customer || ergo : status == UNDER_REVIEW && ergo;
			case EDIT_NOTES -> ergo && !RfqStatus.FINAL.contains(status);
			case SUBMIT -> (status == DRAFT || status == CLARIFICATION_REQUIRED) && (customer || ergo);
			case START_REVIEW -> status == SUBMITTED && reviewer;
			case REQUEST_CLARIFICATION -> status == UNDER_REVIEW && ergo;
			case CANCEL -> customer ? CUSTOMER_OPEN.contains(status) : ergo && ERGO_CANCELLABLE.contains(status);
			case CLOSE -> reviewer && CLOSABLE.contains(status);
			case CLONE -> customer || ergo;
			case UPLOAD -> customer ? CUSTOMER_OPEN.contains(status) || status == UNDER_REVIEW
					: ergo && !RfqStatus.FINAL.contains(status);
		};
	}

	private static Set<RfqAction> actions(CurrentUser me, Rfq rfq) {
		return Arrays.stream(RfqAction.values()).filter(action -> allowed(action, me, rfq))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(RfqAction.class)));
	}

	private static void require(RfqAction action, CurrentUser me, Rfq rfq) {
		if (!allowed(action, me, rfq)) {
			throw ApiException.conflict("This action is not available for this RFQ right now");
		}
	}

	/**
	 * SRS §17.1 mandatory fields; every part needs a process flow before suppliers can quote it. Also checked when
	 * ERGO distributes the RFQ (flow doc §2), after any changes ERGO made.
	 */
	static void requireComplete(Rfq rfq, List<RfqItem> parts) {
		List<String> missing = new ArrayList<>();
		if (rfq.targetQuoteDate == null) {
			missing.add("target quote date");
		}
		if (parts.isEmpty()) {
			missing.add("at least one part");
		}
		for (RfqItem part : parts) {
			List<String> partMissing = new ArrayList<>();
			if (part.description == null) {
				partMissing.add("description");
			}
			if (part.materialId == null && part.materialSpec == null) {
				partMissing.add("material");
			}
			if (part.processSteps.isEmpty()) {
				partMissing.add("process flow (at least one step)");
			}
			if (!partMissing.isEmpty()) {
				missing.add("part " + part.partNumber + ": " + String.join(", ", partMissing));
			}
		}
		if (!missing.isEmpty()) {
			throw ApiException.unprocessable("Please complete: " + String.join("; ", missing));
		}
	}

	private long customerFor(CurrentUser me, Long requested) {
		if (me.companyType() == CompanyType.CUSTOMER) {
			return me.companyId(); // customers always create for their own company, never a client-sent id
		}
		if (requested == null) {
			throw ApiException.unprocessable("Choose the customer");
		}
		CompanyRef customer = directory.company(requested)
			.filter(c -> c.type() == CompanyType.CUSTOMER && c.active())
			.orElseThrow(() -> ApiException.unprocessable("Customer not found or not active"));
		if (me.role() == ERGO_KAM && !directory.accountsOf(me.userId()).contains(customer.id())) {
			throw ApiException.unprocessable("This customer is not one of your accounts");
		}
		return customer.id();
	}

	/** Newly chosen master entries must be active; entries that were already set may since have been retired. */
	private void applyHeader(Rfq rfq, RfqForm form, CurrentUser me) {
		if (!Objects.equals(rfq.currencyId, form.currencyId())) {
			masterData.requireActive(Ref.CURRENCY, form.currencyId(), "Currency");
		}
		if (!Objects.equals(rfq.paymentTermId, form.paymentTermId())) {
			masterData.requireActive(Ref.PAYMENT_TERM, form.paymentTermId(), "Payment term");
		}
		rfq.targetQuoteDate = form.targetQuoteDate();
		rfq.priority = form.priority() == null ? Priority.NORMAL : form.priority();
		rfq.currencyId = form.currencyId();
		rfq.paymentTermId = form.paymentTermId();
		rfq.customerRemarks = text(form.customerRemarks());
		if (isErgo(me)) {
			rfq.internalNotes = text(form.internalNotes()); // never written by customers, whatever they send
		}
	}

	/**
	 * Makes the RFQ's parts what the form says: parts with an id are updated in place (their history stays with
	 * them), parts without one are added, parts left out are removed. Line numbers follow the form's order. Only
	 * possible before distribution (see {@link #allowed}), so no supplier has a quote on a removed part.
	 */
	private void syncItems(Rfq rfq, List<ItemForm> forms) {
		List<RfqItem> existing = items.findByRfqIdOrderByLineNo(rfq.id);
		Map<Long, RfqItem> byId = existing.stream().collect(Collectors.toMap(i -> i.id, Function.identity()));
		Set<String> seen = new HashSet<>();
		for (ItemForm form : forms) {
			String revision = text(form.revision());
			if (!seen.add(form.partNumber().trim().toLowerCase() + "|" + (revision == null ? "" : revision.toLowerCase()))) {
				throw ApiException.unprocessable("Part " + form.partNumber().trim() + " is listed twice");
			}
			if (form.id() != null && !byId.containsKey(form.id())) {
				throw ApiException.unprocessable("Unknown part in this RFQ");
			}
		}
		for (RfqItem item : existing) {
			if (forms.stream().noneMatch(f -> Objects.equals(f.id(), item.id))) {
				items.delete(item);
				audit.record(Rfq.class, rfq.id, "PART_REMOVED", "part", item.partNumber, null);
			}
		}
		int lineNo = 0;
		for (ItemForm form : forms) {
			lineNo++;
			RfqItem item = form.id() == null ? new RfqItem() : byId.get(form.id());
			if (form.id() == null) {
				item.rfqId = rfq.id;
			}
			List<ProcessStep> before = List.copyOf(item.processSteps);
			applyItem(item, form, lineNo);
			items.save(item);
			if (!before.equals(item.processSteps)) {
				audit.record(RfqItem.class, item.id, "UPDATE", "processFlow", route(before), route(item.processSteps));
			}
		}
	}

	private void applyItem(RfqItem item, ItemForm form, int lineNo) {
		if (!Objects.equals(item.materialId, form.materialId())) {
			masterData.requireActive(Ref.MATERIAL, form.materialId(), "Material");
		}
		if (!Objects.equals(item.coatingId, form.coatingId())) {
			masterData.requireActive(Ref.COATING, form.coatingId(), "Coating");
		}
		List<ProcessStep> steps = form.processSteps() == null ? List.of()
				: form.processSteps().stream().map(RfqService::clean).toList();
		Set<Long> knownProcesses = item.processSteps.stream().map(ProcessStep::processId).filter(Objects::nonNull)
			.collect(Collectors.toSet());
		steps.stream().map(ProcessStep::processId).filter(p -> p != null && !knownProcesses.contains(p))
			.forEach(p -> masterData.requireActive(Ref.PROCESS, p, "Process"));

		item.lineNo = lineNo;
		item.partNumber = form.partNumber().trim();
		item.revision = text(form.revision());
		item.description = text(form.description());
		item.eau = form.eau();
		item.netWeightKg = form.netWeightKg();
		item.grossWeightKg = form.grossWeightKg();
		item.materialId = form.materialId();
		item.materialSpec = text(form.materialSpec());
		item.coatingId = form.coatingId();
		item.coatingSpec = text(form.coatingSpec());
		item.targetToolingCost = form.targetToolingCost();
		item.targetPpapPiecePrice = form.targetPpapPiecePrice();
		item.targetProductionPiecePrice = form.targetProductionPiecePrice();
		item.materialLeadTimeWeeks = form.materialLeadTimeWeeks();
		item.toolingLeadTimeDays = form.toolingLeadTimeDays();
		item.sampleLeadTimeDays = form.sampleLeadTimeDays();
		item.productionLeadTimeDays = form.productionLeadTimeDays();
		item.moq = form.moq();
		if (!item.processSteps.equals(steps)) {
			item.processSteps.clear();
			item.processSteps.addAll(steps);
		}
	}

	private static String route(List<ProcessStep> steps) {
		return steps.isEmpty() ? null : steps.stream().map(ProcessStep::stepName).collect(Collectors.joining(" > "));
	}

	private static ProcessStep clean(ProcessStep step) {
		return new ProcessStep(step.processId(), step.stepName().trim(), step.cycleTimeSeconds(), step.sourcingType(),
				step.sourcingType() == ProcessStep.SourcingType.OUTSOURCED ? text(step.outsourcedSupplierName()) : null,
				text(step.remarks()));
	}

	private DocumentInfo document(CurrentUser me, long rfqId, long documentId) {
		return documents.find(MODULE, rfqId, documentId).filter(d -> canSee(me, d))
			.orElseThrow(() -> ApiException.notFound("Document"));
	}

	/** Visibility on top of RFQ access: ERGO sees all, uploaders their own, others what is shared with their side. */
	private static boolean canSee(CurrentUser me, DocumentInfo document) {
		if (me.companyType() == CompanyType.ERGO || document.uploadedByCompanyId() == me.companyId()) {
			return true;
		}
		return switch (me.companyType()) {
			case CUSTOMER -> document.visibility() == Visibility.CUSTOMER || document.visibility() == Visibility.ALL;
			case SUPPLIER -> document.visibility() == Visibility.SUPPLIER || document.visibility() == Visibility.ALL;
			case ERGO -> true;
		};
	}

	/** SRS §11.2: own uploads only, and never once suppliers may have quoted against the file. */
	private static boolean canDelete(CurrentUser me, Rfq rfq, DocumentInfo document) {
		if (!BEFORE_FLOAT.contains(rfq.status)) {
			return false;
		}
		return ERGO_EDITORS.contains(me.role())
				|| CUSTOMER_EDITORS.contains(me.role()) && document.uploadedByCompanyId() == me.companyId();
	}

	private RfqView view(Rfq r, CurrentUser me) {
		List<ItemView> itemViews = itemViews(items.findByRfqIdOrderByLineNo(r.id));
		Map<Long, MasterDataApi.Named> currencies = masterData.describe(Ref.CURRENCY, List.of(r.currencyId));
		Map<Long, MasterDataApi.Named> terms = masterData.describe(Ref.PAYMENT_TERM, ids(List.of(r), x -> x.paymentTermId));
		Set<RfqAction> actions = actions(me, r);

		if (!isErgo(me)) {
			return new CustomerRfqView("CUSTOMER", r.id, r.number, r.status.forCustomer(), r.targetQuoteDate,
					r.priority, named(currencies, r.currencyId), named(terms, r.paymentTermId), itemViews,
					r.customerRemarks, r.clarificationQuestion, r.clarificationAnswer, actions, r.createdAt,
					r.updatedAt);
		}
		CompanyRef customer = directory.company(r.customerCompanyId).orElseThrow();
		Map<Long, UserRef> users = directory.users(ids(List.of(r), x -> x.createdByUserId, x -> x.ownerUserId));
		return new ErgoRfqView("ERGO", r.id, r.number, r.status, r.sourceType,
				new Named(customer.id(), customer.code(), customer.name()), userName(users, r.createdByUserId),
				userName(users, r.ownerUserId), r.targetQuoteDate, r.priority, named(currencies, r.currencyId),
				named(terms, r.paymentTermId), itemViews, r.customerRemarks, r.internalNotes, r.clarificationQuestion,
				r.clarificationAnswer, actions, r.createdAt, r.updatedAt);
	}

	/** The parts as ERGO and the RFQ's own customer see them (targets included; suppliers never get this view). */
	private List<ItemView> itemViews(List<RfqItem> parts) {
		Map<Long, MasterDataApi.Named> materials = masterData.describe(Ref.MATERIAL,
				parts.stream().map(p -> p.materialId).filter(Objects::nonNull).collect(Collectors.toSet()));
		Map<Long, MasterDataApi.Named> coatings = masterData.describe(Ref.COATING,
				parts.stream().map(p -> p.coatingId).filter(Objects::nonNull).collect(Collectors.toSet()));
		Map<Long, MasterDataApi.Named> processes = masterData.describe(Ref.PROCESS, parts.stream()
			.flatMap(p -> p.processSteps.stream().map(ProcessStep::processId)).filter(Objects::nonNull)
			.collect(Collectors.toSet()));
		return parts.stream()
			.map(p -> new ItemView(p.id, p.lineNo, p.partNumber, p.revision, p.description, p.eau, p.netWeightKg,
					p.grossWeightKg, named(materials, p.materialId), p.materialSpec, named(coatings, p.coatingId),
					p.coatingSpec,
					new TargetsView(p.targetToolingCost, p.targetPpapPiecePrice, p.targetProductionPiecePrice,
							p.materialLeadTimeWeeks, p.toolingLeadTimeDays, p.sampleLeadTimeDays,
							p.productionLeadTimeDays, p.moq),
					p.processSteps.stream()
						.map(s -> new StepView(named(processes, s.processId()), s.stepName(), s.cycleTimeSeconds(),
								s.sourcingType(), s.outsourcedSupplierName(), s.remarks()))
						.toList()))
			.toList();
	}

	private Map<Long, List<String>> partNumbers(Collection<Rfq> list) {
		if (list.isEmpty()) {
			return Map.of();
		}
		return items.findByRfqIdInOrderByRfqIdAscLineNoAsc(list.stream().map(r -> r.id).toList()).stream()
			.collect(Collectors.groupingBy(i -> i.rfqId, Collectors.mapping(i -> i.partNumber, Collectors.toList())));
	}

	private static DocumentView documentView(CurrentUser me, Rfq rfq, DocumentInfo d, List<Long> itemIds) {
		return new DocumentView(d.id(), d.fileName(), d.size(), d.visibility(),
				d.uploadedByCompanyId() == me.companyId(), canDelete(me, rfq, d), d.createdAt(), itemIds);
	}

	// ---------------------------------------------------------------- small helpers

	private static boolean isErgo(CurrentUser me) {
		return me.companyType() == CompanyType.ERGO;
	}

	@SafeVarargs
	private static Set<Long> ids(Collection<Rfq> rfqs, Function<Rfq, Long>... fields) {
		return rfqs.stream().flatMap(r -> Arrays.stream(fields).map(f -> f.apply(r))).filter(Objects::nonNull)
			.collect(Collectors.toSet());
	}

	private static Named named(Map<Long, MasterDataApi.Named> names, Long id) {
		MasterDataApi.Named n = id == null ? null : names.get(id);
		return n == null ? null : new Named(n.id(), n.code(), n.name());
	}

	private static String userName(Map<Long, UserRef> users, Long id) {
		UserRef user = id == null ? null : users.get(id);
		return user == null ? null : user.name();
	}

	private static String text(String value) {
		return com.ergo.connect.common.validation.Blank.is(value) ? null : value.trim();
	}

	private static String required(String value, String message) {
		String text = text(value);
		if (text == null) {
			throw ApiException.unprocessable(message);
		}
		return text;
	}

	private void reasonIfGiven(String comment) {
		String text = text(comment);
		if (text != null) {
			audit.reason(text);
		}
	}

}
