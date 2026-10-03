package com.ergo.connect.communication.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.event.NotificationKind;
import com.ergo.connect.common.event.Notifier;
import com.ergo.connect.common.event.Recipients;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.record.RecordParties;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.communication.api.CommentType;
import com.ergo.connect.communication.domain.CommentDtos.AttachmentView;
import com.ergo.connect.communication.domain.CommentDtos.CommentAction;
import com.ergo.connect.communication.domain.CommentDtos.CommentEditForm;
import com.ergo.connect.communication.domain.CommentDtos.CommentForm;
import com.ergo.connect.communication.domain.CommentDtos.CommentView;
import com.ergo.connect.communication.domain.CommentDtos.InboxPage;
import com.ergo.connect.communication.domain.CommentDtos.MentionOption;
import com.ergo.connect.communication.domain.CommentDtos.MentionView;
import com.ergo.connect.communication.domain.CommentDtos.ReasonForm;
import com.ergo.connect.documents.api.DocumentStore;
import com.ergo.connect.documents.api.DocumentStore.DocumentInfo;
import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;

/**
 * Threaded per-record comments (SRS §10.10, §12.12.1, §13.7.3), Phase 6c. This module owns none of the records
 * comments hang off, so record scope is resolved by each owning module's own {@code api} facade ({@code
 * partiesVisibleToCaller}: empty = the record doesn't exist for this caller, so the comment doesn't either) - the same
 * shape as the Document Library (ARCHITECTURE.md §8, 2026-10-02). On top of record scope sits the comment's audience
 * (its {@link CommentType}); a comment the caller may not read is never loaded into a DTO.
 * <p>
 * <b>Rules (client decisions, ARCHITECTURE.md §8 2026-10-02 "Phase 6c"):</b>
 * <ul>
 * <li>Customers and suppliers never share a thread. A customer posts only {@code EXTERNAL_CUSTOMER}, a supplier only
 * {@code EXTERNAL_SUPPLIER}; ERGO posts any type the record supports. Per record kind: an RFQ has a customer side
 * only, a supplier quote (invitation) a supplier side only (several suppliers share one RFQ, so supplier
 * conversation lives on each supplier's own quote), everything else both.
 * <li>One level of replies; a reply always takes its parent's type.
 * <li>The author - and only the author, whatever their role - may edit, until anyone replies or the query is
 * resolved. Comments are never deleted.
 * <li>A top-level comment may be flagged a query; its author or any ERGO user resolves it (reason optional), reopening
 * needs a reason. Only ERGO escalates a thread; de-escalating needs a reason.
 * <li>Mentions: ERGO may mention ERGO users and the comment's audience company; an external user only its own
 * company's users (ERGO staff names are never listed to externals).
 * <li>Attachments are Documents ({@code linked_module = "comment"}), visible to exactly the comment's audience, added
 * by the author while the comment is still editable.
 * </ul>
 */
@Service
@Transactional
public class CommentService {

	static final int MAX_ATTACHMENTS = 5;

	private static final int INBOX_WINDOW = 1000;

	private static final int MAX_PAGE_SIZE = 100;

	private static final String ATTACHMENT_MODULE = "comment";

	private static final Set<CommentType> ALL_TYPES = EnumSet.allOf(CommentType.class);

	/** The record kinds comments can hang off (V21's CHECK list) and which audiences each supports. */
	private enum Target {

		RFQ("rfq", EnumSet.of(CommentType.INTERNAL, CommentType.EXTERNAL_CUSTOMER)),
		QUOTATION("quotation", EnumSet.of(CommentType.INTERNAL, CommentType.EXTERNAL_SUPPLIER)),
		NPD("npd", ALL_TYPES), OFP("ofp", ALL_TYPES), INSPECTION("inspection", ALL_TYPES), NCR("ncr", ALL_TYPES),
		DEVIATION("deviation", ALL_TYPES);

		final String key;

		final Set<CommentType> types;

		Target(String key, Set<CommentType> types) {
			this.key = key;
			this.types = types;
		}

		static Target of(String key) {
			for (Target t : values()) {
				if (t.key.equals(key)) {
					return t;
				}
			}
			throw ApiException.unprocessable("Unknown record type: " + key);
		}

	}

	private record Loaded(Comment comment, RecordParties parties) {
	}

	public record AttachmentFile(DocumentInfo info, byte[] content) {
	}

	private final CommentRepository comments;

	private final RecordScope scope;

	private final Directory directory;

	private final DocumentStore documents;

	private final AuditLogService audit;

	private final Notifier notifier;

	CommentService(CommentRepository comments, RecordScope scope, Directory directory, DocumentStore documents,
			AuditLogService audit, Notifier notifier) {
		this.notifier = notifier;
		this.comments = comments;
		this.scope = scope;
		this.directory = directory;
		this.documents = documents;
		this.audit = audit;
	}

	// ---------------------------------------------------------------- reads

	/** One record's threads (top-level comments with their replies) the caller may read. */
	@Transactional(readOnly = true)
	public List<CommentView> thread(String linkedModule, long linkedRecordId) {
		CurrentUser me = CurrentUser.get();
		Target target = Target.of(linkedModule);
		RecordParties parties = parties(target, linkedRecordId).orElseThrow(() -> ApiException.notFound("Record"));
		List<Comment> tops = comments
			.findByLinkedModuleAndLinkedRecordIdAndParentCommentIdIsNullOrderById(target.key, linkedRecordId)
			.stream()
			.filter(c -> visible(me, c, parties))
			.toList();
		return views(me, tops, c -> parties);
	}

	/** Communication Center (SRS §12.12.1): threads across every record the caller can see, optionally only open
	 * queries and/or escalated ones, newest first. */
	@Transactional(readOnly = true)
	public InboxPage inbox(String linkedModule, boolean openQueriesOnly, boolean escalatedOnly, int page, int size) {
		CurrentUser me = CurrentUser.get();
		String module = linkedModule == null || linkedModule.isBlank() ? null : Target.of(linkedModule).key;
		Map<String, Optional<RecordParties>> cache = new HashMap<>();
		Map<Long, RecordParties> partiesOf = new HashMap<>();
		List<Comment> visible = comments
			// An external caller's candidates are scoped to its own audience in SQL (security review M4), so other
			// companies' newer comments cannot push its threads out of the capped window.
			.findInbox(module, openQueriesOnly, escalatedOnly,
					me.companyType() == CompanyType.ERGO ? null : me.companyId(), PageRequest.of(0, INBOX_WINDOW))
			.stream()
			.filter(c -> {
				Optional<RecordParties> p = cache.computeIfAbsent(c.linkedModule + ":" + c.linkedRecordId,
						k -> parties(Target.of(c.linkedModule), c.linkedRecordId));
				if (p.isPresent() && visible(me, c, p.get())) {
					partiesOf.put(c.id, p.get());
					return true;
				}
				return false;
			})
			.toList();
		int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		int pageNo = Math.max(page, 0);
		int from = (int) Math.min((long) pageNo * pageSize, visible.size());
		int to = Math.min(from + pageSize, visible.size());
		return new InboxPage(views(me, visible.subList(from, to), c -> partiesOf.get(c.id)), pageNo, pageSize,
				visible.size());
	}

	/** The people the caller may mention on a comment of this audience on this record. */
	@Transactional(readOnly = true)
	public List<MentionOption> mentionable(String linkedModule, long linkedRecordId, CommentType requestedType) {
		CurrentUser me = CurrentUser.get();
		Target target = Target.of(linkedModule);
		RecordParties parties = parties(target, linkedRecordId).orElseThrow(() -> ApiException.notFound("Record"));
		CommentType type = chooseType(me, requestedType);
		return mentionCandidates(me, type, parties, target, linkedRecordId, audienceOf(type, parties)).stream()
			.map(u -> new MentionOption(u.id(), u.name()))
			.toList();
	}

	@Transactional(readOnly = true)
	public AttachmentFile downloadAttachment(long id, long documentId) {
		Loaded l = visibleOrThrow(CurrentUser.get(), id);
		DocumentInfo info = documents.find(ATTACHMENT_MODULE, l.comment.id, documentId)
			.filter(d -> attachmentVisible(CurrentUser.get(), d))
			.orElseThrow(() -> ApiException.notFound("Attachment"));
		return new AttachmentFile(info, documents.content(info));
	}

	// ---------------------------------------------------------------- writes

	public CommentView create(CommentForm form) {
		CurrentUser me = CurrentUser.get();
		Target target = Target.of(form.linkedModule());
		RecordParties parties = parties(target, form.linkedRecordId())
			.orElseThrow(() -> ApiException.notFound("Record"));

		Comment parent = null;
		CommentType type;
		if (form.parentCommentId() != null) {
			parent = visibleOrThrow(me, form.parentCommentId()).comment;
			comments.findByIdForReply(parent.id);
			if (parent.parentCommentId != null) {
				throw ApiException.unprocessable("A reply cannot itself be replied to");
			}
			if (!parent.linkedModule.equals(target.key) || !parent.linkedRecordId.equals(form.linkedRecordId())) {
				throw ApiException.unprocessable("A reply belongs to the same record as the comment it answers");
			}
			type = parent.commentType;
			if (form.commentType() != null && form.commentType() != type) {
				throw ApiException.unprocessable("A reply is visible to the same people as the comment it answers");
			}
			if (form.query()) {
				throw ApiException.unprocessable("Only a new comment can be marked as a query");
			}
		}
		else {
			type = chooseType(me, form.commentType());
		}
		if (!target.types.contains(type)) {
			throw ApiException.unprocessable("This kind of comment is not available on this record");
		}
		if (!postable(me, type) || !typeVisible(me, type, parties)) {
			throw ApiException.unprocessable("You can only post to your own side of the conversation");
		}
		if (type == CommentType.EXTERNAL_CUSTOMER && parties.customerCompanyId() == null
				|| type == CommentType.EXTERNAL_SUPPLIER && parties.supplierCompanyId() == null) {
			throw ApiException.unprocessable("This record has no company on that side");
		}

		Comment c = new Comment();
		c.linkedModule = target.key;
		c.linkedRecordId = form.linkedRecordId();
		c.commentType = type;
		c.audienceCompanyId = audienceOf(type, parties);
		c.parentCommentId = parent == null ? null : parent.id;
		c.commentText = required(form.text());
		c.createdByUserId = me.userId();
		c.createdByCompanyId = me.companyId();
		c.queryFlag = form.query();
		c.mentionedUserIds = mentions(me, type, parties, target, form.linkedRecordId(), c.audienceCompanyId,
				form.mentionedUserIds());
		comments.save(c);
		notifyAfterCreate(me, target, c, parent);
		if (!c.mentionedUserIds.isEmpty()) {
			audit.record("communication", "Comment", c.id, "UPDATE", "mentions", null, mentionList(c.mentionedUserIds));
		}
		return views(me, List.of(c), x -> parties).get(0);
	}

	public CommentView edit(long id, CommentEditForm form) {
		CurrentUser me = CurrentUser.get();
		Loaded l = visibleOrThrow(me, id);
		Comment c = l.comment;
		requireEditable(me, c);
		if (form.query() && c.parentCommentId != null) {
			throw ApiException.unprocessable("Only a new comment can be marked as a query");
		}
		c.commentText = required(form.text());
		c.queryFlag = form.query();
		Set<Long> mentions = mentions(me, c.commentType, l.parties, Target.of(c.linkedModule), c.linkedRecordId,
				c.audienceCompanyId, form.mentionedUserIds());
		if (!mentions.equals(c.mentionedUserIds)) {
			Set<Long> added = new HashSet<>(mentions);
			added.removeAll(c.mentionedUserIds);
			notifyMentioned(Target.of(c.linkedModule), c, added);
			// the entity listener skips collections (AuditEntityListener), so mention changes are audited explicitly (M1)
			audit.record("communication", "Comment", c.id, "UPDATE", "mentions", mentionList(c.mentionedUserIds),
					mentionList(mentions));
			c.mentionedUserIds.retainAll(mentions);
			c.mentionedUserIds.addAll(mentions);
		}
		return views(me, List.of(c), x -> l.parties).get(0);
	}

	public CommentView resolve(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Loaded l = visibleOrThrow(me, id);
		Comment c = l.comment;
		requireAuthorOrErgo(me, c);
		if (!c.queryFlag || c.resolvedAt != null) {
			throw ApiException.conflict("This is not an open query");
		}
		reasonIfGiven(form == null ? null : form.reason());
		c.resolvedAt = Instant.now();
		c.resolvedByUserId = me.userId();
		return views(me, List.of(c), x -> l.parties).get(0);
	}

	public CommentView reopen(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Loaded l = visibleOrThrow(me, id);
		Comment c = l.comment;
		requireAuthorOrErgo(me, c);
		if (!c.queryFlag || c.resolvedAt == null) {
			throw ApiException.conflict("This query is not resolved");
		}
		audit.reason(required(form == null ? null : form.reason(), "A reason is required to reopen a query"));
		c.resolvedAt = null;
		c.resolvedByUserId = null;
		return views(me, List.of(c), x -> l.parties).get(0);
	}

	public CommentView escalate(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Loaded l = visibleOrThrow(me, id);
		Comment c = l.comment;
		requireErgo(me);
		if (c.parentCommentId != null || c.escalatedFlag) {
			throw ApiException.conflict("Only a thread that is not already escalated can be escalated");
		}
		reasonIfGiven(form == null ? null : form.reason());
		c.escalatedFlag = true;
		c.escalatedAt = Instant.now();
		c.escalatedByUserId = me.userId();
		notifier.send(NotificationKind.THREAD_ESCALATED, c.linkedModule, c.linkedRecordId,
				recordLabel(Target.of(c.linkedModule), c.linkedRecordId), null, Recipients.users(Set.of(c.createdByUserId)));
		return views(me, List.of(c), x -> l.parties).get(0);
	}

	public CommentView deescalate(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Loaded l = visibleOrThrow(me, id);
		Comment c = l.comment;
		requireErgo(me);
		if (!c.escalatedFlag) {
			throw ApiException.conflict("This thread is not escalated");
		}
		audit.reason(required(form == null ? null : form.reason(), "A reason is required to lower an escalation"));
		c.escalatedFlag = false;
		c.escalatedAt = null;
		c.escalatedByUserId = null;
		return views(me, List.of(c), x -> l.parties).get(0);
	}

	public CommentView attach(long id, String fileName, byte[] content) {
		CurrentUser me = CurrentUser.get();
		Loaded l = visibleOrThrow(me, id);
		Comment c = l.comment;
		requireEditable(me, c);
		if (currentAttachments(c.id).size() >= MAX_ATTACHMENTS) {
			throw ApiException.conflict("A comment can have at most " + MAX_ATTACHMENTS + " attachments");
		}
		documents.store(ATTACHMENT_MODULE, c.id, fileName, content, attachmentVisibility(c.commentType));
		return views(me, List.of(c), x -> l.parties).get(0);
	}

	public CommentView removeAttachment(long id, long documentId) {
		CurrentUser me = CurrentUser.get();
		Loaded l = visibleOrThrow(me, id);
		Comment c = l.comment;
		requireEditable(me, c);
		DocumentInfo info = documents.find(ATTACHMENT_MODULE, c.id, documentId)
			.orElseThrow(() -> ApiException.notFound("Attachment"));
		documents.delete(info);
		return views(me, List.of(c), x -> l.parties).get(0);
	}

	// ---------------------------------------------------------------- non-throwing probes (CommunicationApi)

	/** A thrown {@code ApiException} marks the surrounding transaction rollback-only even if the caller catches it,
	 * so the documents module's probes use these boolean halves instead of {@link #visibleOrThrow}. */
	@Transactional(readOnly = true)
	boolean visibleToCaller(long id) {
		return load(CurrentUser.get(), id) != null;
	}

	@Transactional(readOnly = true)
	boolean attachmentWindowOpen(long id) {
		CurrentUser me = CurrentUser.get();
		Loaded l = load(me, id);
		return l != null && editable(me, l.comment);
	}

	// ---------------------------------------------------------------- record scope and audience

	private Optional<RecordParties> parties(Target target, long id) {
		return scope.parties(target.key, id);
	}

	/** Layer 2: the comment's audience. ERGO reads every type (within the record scope the facade already applied);
	 * an external caller reads only its own side, and only when it is that side's company on the record. */
	private static boolean visible(CurrentUser me, Comment c, RecordParties p) {
		if (!typeVisible(me, c.commentType, p)) {
			return false;
		}
		// Security review H1: the audience is fixed when the comment is written. A customer/supplier reassigned onto
		// the record later is not that audience, and the old party keeps no access either.
		return me.companyType() == CompanyType.ERGO || Objects.equals(c.audienceCompanyId, me.companyId());
	}

	private static boolean typeVisible(CurrentUser me, CommentType type, RecordParties p) {
		return switch (me.companyType()) {
			case ERGO -> true;
			case CUSTOMER -> type == CommentType.EXTERNAL_CUSTOMER && Objects.equals(p.customerCompanyId(), me.companyId());
			case SUPPLIER -> type == CommentType.EXTERNAL_SUPPLIER && Objects.equals(p.supplierCompanyId(), me.companyId());
		};
	}

	private static boolean postable(CurrentUser me, CommentType type) {
		return switch (me.companyType()) {
			case ERGO -> true;
			case CUSTOMER -> type == CommentType.EXTERNAL_CUSTOMER;
			case SUPPLIER -> type == CommentType.EXTERNAL_SUPPLIER;
		};
	}

	private static CommentType chooseType(CurrentUser me, CommentType requested) {
		return switch (me.companyType()) {
			case ERGO -> requested == null ? CommentType.INTERNAL : requested;
			case CUSTOMER -> requested == null ? CommentType.EXTERNAL_CUSTOMER : requested;
			case SUPPLIER -> requested == null ? CommentType.EXTERNAL_SUPPLIER : requested;
		};
	}

	private static Visibility attachmentVisibility(CommentType type) {
		return switch (type) {
			case INTERNAL -> Visibility.INTERNAL;
			case EXTERNAL_CUSTOMER -> Visibility.CUSTOMER;
			case EXTERNAL_SUPPLIER -> Visibility.SUPPLIER;
		};
	}

	private Loaded load(CurrentUser me, long id) {
		Comment c = comments.findById(id).orElse(null);
		if (c == null) {
			return null;
		}
		Target target = Target.of(c.linkedModule);
		Optional<RecordParties> p = parties(target, c.linkedRecordId);
		if (p.isEmpty() || !visible(me, c, p.get())) {
			return null;
		}
		return new Loaded(c, p.get());
	}

	/** Out-of-scope comments do not exist for the caller (404, not 403). */
	private Loaded visibleOrThrow(CurrentUser me, long id) {
		Loaded l = load(me, id);
		if (l == null) {
			throw ApiException.notFound("Comment");
		}
		return l;
	}

	// ---------------------------------------------------------------- edit rules

	private static boolean isAuthor(CurrentUser me, Comment c) {
		return c.createdByUserId.equals(me.userId());
	}

	/** SRS §10.10 / §11.2: own comment, until someone replies or the query is resolved. */
	private boolean editable(CurrentUser me, Comment c) {
		return isAuthor(me, c) && c.resolvedAt == null && !comments.existsByParentCommentId(c.id);
	}

	private void requireEditable(CurrentUser me, Comment c) {
		if (!editable(me, c)) {
			throw ApiException.conflict("This comment can no longer be changed");
		}
	}

	private static void requireAuthorOrErgo(CurrentUser me, Comment c) {
		if (!isAuthor(me, c) && me.companyType() != CompanyType.ERGO) {
			throw ApiException.forbidden("Only the author or ERGO may do this");
		}
	}

	private static void requireErgo(CurrentUser me) {
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.forbidden("Only ERGO may do this");
		}
	}

	// ---------------------------------------------------------------- mentions

	private static Long audienceOf(CommentType type, RecordParties p) {
		return switch (type) {
			case INTERNAL -> null;
			case EXTERNAL_CUSTOMER -> p.customerCompanyId();
			case EXTERNAL_SUPPLIER -> p.supplierCompanyId();
		};
	}

	/** People the caller may mention: the right companies' active users (see class comment), further limited to those
	 * who could actually read this record and this comment - so a mention can never disclose a record, or the fact
	 * that a thread exists, to someone outside its scope (security review M3). */
	private List<UserRef> mentionCandidates(CurrentUser me, CommentType type, RecordParties p, Target target,
			long recordId, Long audienceCompanyId) {
		List<UserRef> result = new ArrayList<>(directory.activeUsersOf(me.companyId()));
		if (me.companyType() == CompanyType.ERGO) {
			if (type == CommentType.EXTERNAL_CUSTOMER && p.customerCompanyId() != null) {
				result.addAll(directory.activeUsersOf(p.customerCompanyId()));
			}
			if (type == CommentType.EXTERNAL_SUPPLIER && p.supplierCompanyId() != null) {
				result.addAll(directory.activeUsersOf(p.supplierCompanyId()));
			}
		}
		Map<Long, Set<Role>> roles = directory.rolesOf(result.stream().map(UserRef::id).toList());
		Map<Long, CompanyRef> companies = directory
			.companies(result.stream().map(UserRef::companyId).collect(Collectors.toSet()));
		return result.stream()
			.filter(u -> canRead(u, companies.get(u.companyId()), roles.getOrDefault(u.id(), Set.of()), target,
					recordId, type, audienceCompanyId))
			.toList();
	}

	/** True if the person, in any role they hold, passes the same record scope and audience rules a real request from
	 * them would (the owning modules' own probes, run as them). */
	private boolean canRead(UserRef u, CompanyRef company, Set<Role> roles, Target target, long recordId,
			CommentType type, Long audienceCompanyId) {
		if (company == null) {
			return false;
		}
		if (company.type() != CompanyType.ERGO && !Objects.equals(audienceCompanyId, u.companyId())) {
			return false;
		}
		for (Role role : roles) {
			CurrentUser as = new CurrentUser(u.id(), u.companyId(), company.type(), role, 0L);
			boolean readable = CurrentUser.runAs(as,
					() -> parties(target, recordId).filter(p -> typeVisible(as, type, p)).isPresent());
			if (readable) {
				return true;
			}
		}
		return false;
	}

	private Set<Long> mentions(CurrentUser me, CommentType type, RecordParties p, Target target, long recordId,
			Long audienceCompanyId, Collection<Long> requested) {
		Set<Long> ids = requested == null ? new LinkedHashSet<>() : new LinkedHashSet<>(requested);
		if (ids.isEmpty()) {
			return ids;
		}
		Set<Long> allowed = mentionCandidates(me, type, p, target, recordId, audienceCompanyId).stream()
			.map(UserRef::id)
			.collect(Collectors.toSet());
		if (!allowed.containsAll(ids)) {
			throw ApiException.unprocessable("You can only mention people who can read this comment");
		}
		return ids;
	}

	/** Latest version of each attachment: a Library replace leaves the superseded row linked to the comment too. */
	private List<DocumentInfo> currentAttachments(long commentId) {
		List<DocumentInfo> all = documents.list(ATTACHMENT_MODULE, commentId);
		Set<Long> superseded = all.stream().map(DocumentInfo::supersedesDocumentId).filter(Objects::nonNull)
			.collect(Collectors.toSet());
		return all.stream().filter(d -> !superseded.contains(d.id())).toList();
	}

	/** The document's own visibility still applies on this path (security review M2), exactly as in the Library: ERGO
	 * and the uploading company always, otherwise only the side the file was shared with. */
	private static boolean attachmentVisible(CurrentUser me, DocumentInfo d) {
		if (me.companyType() == CompanyType.ERGO || d.uploadedByCompanyId() == me.companyId()) {
			return true;
		}
		return switch (me.companyType()) {
			case CUSTOMER -> d.visibility() == Visibility.CUSTOMER || d.visibility() == Visibility.ALL;
			case SUPPLIER -> d.visibility() == Visibility.SUPPLIER || d.visibility() == Visibility.ALL;
			case ERGO -> true;
		};
	}

	/** SRS §14.9 "comment mention" (plus replies): never the comment text, only that it happened on this record.
	 * Recipients are re-checked against the record at delivery, so a stale mention notifies nobody. */
	private void notifyAfterCreate(CurrentUser me, Target target, Comment c, Comment parent) {
		String label = recordLabel(target, c.linkedRecordId);
		if (parent != null && !parent.createdByUserId.equals(me.userId())) {
			notifier.send(NotificationKind.COMMENT_REPLY, target.key, c.linkedRecordId, label, null,
					Recipients.users(Set.of(parent.createdByUserId)));
		}
		notifyMentioned(target, c, c.mentionedUserIds);
	}

	private void notifyMentioned(Target target, Comment c, Set<Long> mentioned) {
		if (!mentioned.isEmpty()) {
			notifier.send(NotificationKind.MENTIONED, target.key, c.linkedRecordId,
					recordLabel(target, c.linkedRecordId), null, Recipients.users(mentioned));
		}
	}

	private static String recordLabel(Target target, long id) {
		String kind = switch (target) {
			case RFQ -> "RFQ";
			case QUOTATION -> "Supplier quote";
			case NPD -> "NPD project";
			case OFP -> "Order";
			case INSPECTION -> "Inspection";
			case NCR -> "NCR";
			case DEVIATION -> "Deviation";
		};
		return kind + " #" + id;
	}

	private static String mentionList(Collection<Long> ids) {
		return ids.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
	}

	// ---------------------------------------------------------------- view assembly

	private List<CommentView> views(CurrentUser me, List<Comment> tops, Function<Comment, RecordParties> partiesOf) {
		if (tops.isEmpty()) {
			return List.of();
		}
		Map<Long, List<Comment>> replies = comments
			.findByParentCommentIdInOrderById(tops.stream().map(c -> c.id).toList())
			.stream()
			.collect(Collectors.groupingBy(c -> c.parentCommentId));
		List<Comment> all = new ArrayList<>(tops);
		replies.values().forEach(all::addAll);

		Set<Long> userIds = new HashSet<>();
		for (Comment c : all) {
			userIds.add(c.createdByUserId);
			if (c.resolvedByUserId != null) {
				userIds.add(c.resolvedByUserId);
			}
			if (c.escalatedByUserId != null) {
				userIds.add(c.escalatedByUserId);
			}
			userIds.addAll(c.mentionedUserIds);
		}
		Map<Long, UserRef> users = directory.users(userIds);
		Map<Long, CompanyRef> companies = directory
			.companies(users.values().stream().map(UserRef::companyId).collect(Collectors.toSet()));

		return tops.stream().map(top -> {
			List<CommentView> replyViews = replies.getOrDefault(top.id, List.of())
				.stream()
				.map(r -> view(me, r, partiesOf.apply(top), users, companies, List.of()))
				.toList();
			return view(me, top, partiesOf.apply(top), users, companies, replyViews);
		}).toList();
	}

	private CommentView view(CurrentUser me, Comment c, RecordParties parties, Map<Long, UserRef> users,
			Map<Long, CompanyRef> companies, List<CommentView> replies) {
		List<MentionView> mentions = c.mentionedUserIds.stream()
			.sorted()
			.map(id -> new MentionView(discloseId(me, id, users, companies) ? id : null, person(me, id, users, companies)))
			.filter(m -> m.name() != null)
			.toList();
		List<AttachmentView> attachments = currentAttachments(c.id)
			.stream()
			.filter(d -> attachmentVisible(me, d))
			.map(d -> new AttachmentView(d.id(), d.fileName(), d.size(), d.uploadedByUserId() == me.userId()))
			.toList();
		return new CommentView(c.id, c.linkedModule, c.linkedRecordId, c.commentType, c.parentCommentId,
				c.commentText, person(me, c.createdByUserId, users, companies), isAuthor(me, c), c.createdAt,
				c.updatedAt, c.queryFlag, c.resolvedAt != null, c.resolvedAt,
				c.resolvedByUserId == null ? null : person(me, c.resolvedByUserId, users, companies), c.escalatedFlag,
				c.escalatedAt, c.escalatedByUserId == null ? null : person(me, c.escalatedByUserId, users, companies),
				mentions, attachments, replies, actions(me, c));
	}

	/** Two-way anonymity, same rule as documents/RFQ/NPD/OFP: ERGO sees the real person and company; an external
	 * caller sees "ERGO" for any ERGO staff member and a colleague's name for its own company, nothing else. */
	private static String person(CurrentUser me, long userId, Map<Long, UserRef> users, Map<Long, CompanyRef> companies) {
		UserRef u = users.get(userId);
		if (u == null) {
			return null;
		}
		CompanyRef company = companies.get(u.companyId());
		if (me.companyType() == CompanyType.ERGO) {
			return company == null ? u.name() : u.name() + " (" + company.name() + ")";
		}
		if (company != null && company.type() == CompanyType.ERGO) {
			return "ERGO";
		}
		return u.companyId() == me.companyId() ? u.name() : null;
	}

	private static boolean discloseId(CurrentUser me, long userId, Map<Long, UserRef> users, Map<Long, CompanyRef> companies) {
		UserRef u = users.get(userId);
		CompanyRef company = u == null ? null : companies.get(u.companyId());
		return me.companyType() == CompanyType.ERGO || company == null || company.type() != CompanyType.ERGO;
	}

	private Set<CommentAction> actions(CurrentUser me, Comment c) {
		Set<CommentAction> result = EnumSet.noneOf(CommentAction.class);
		boolean top = c.parentCommentId == null;
		boolean ergo = me.companyType() == CompanyType.ERGO;
		if (top) {
			result.add(CommentAction.REPLY);
		}
		if (editable(me, c)) {
			result.add(CommentAction.EDIT);
			result.add(CommentAction.ATTACH);
		}
		if (c.queryFlag && (isAuthor(me, c) || ergo)) {
			result.add(c.resolvedAt == null ? CommentAction.RESOLVE : CommentAction.REOPEN);
		}
		if (top && ergo) {
			result.add(c.escalatedFlag ? CommentAction.DEESCALATE : CommentAction.ESCALATE);
		}
		return result;
	}

	// ---------------------------------------------------------------- small helpers

	private static String text(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String required(String value) {
		return required(value, "A comment cannot be empty");
	}

	private static String required(String value, String message) {
		String t = text(value);
		if (t == null) {
			throw ApiException.unprocessable(message);
		}
		return t;
	}

	private void reasonIfGiven(String reason) {
		String t = text(reason);
		if (t != null) {
			audit.reason(t);
		}
	}

}
