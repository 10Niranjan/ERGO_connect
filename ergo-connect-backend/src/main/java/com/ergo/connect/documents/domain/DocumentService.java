package com.ergo.connect.documents.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.documents.api.ApprovalStatus;
import com.ergo.connect.documents.api.DocumentStore;
import com.ergo.connect.documents.api.DocumentStore.DocumentInfo;
import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.documents.api.LockStatus;
import com.ergo.connect.documents.domain.DocumentDtos.CategoryOption;
import com.ergo.connect.documents.domain.DocumentDtos.DocumentAction;
import com.ergo.connect.documents.domain.DocumentDtos.DocumentDetail;
import com.ergo.connect.documents.domain.DocumentDtos.DocumentEditForm;
import com.ergo.connect.documents.domain.DocumentDtos.LibraryPage;
import com.ergo.connect.documents.domain.DocumentDtos.LibraryRow;
import com.ergo.connect.documents.domain.DocumentDtos.ReasonForm;
import com.ergo.connect.documents.domain.DocumentDtos.SecondaryLink;
import com.ergo.connect.documents.domain.DocumentDtos.VersionSummary;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.ofp.api.OfpApi;
import com.ergo.connect.quality.api.QualityApi;
import com.ergo.connect.quotation.api.QuotationApi;
import com.ergo.connect.rfq.api.RfqApi;

/**
 * Document Library/Detail, edit, versioning and lock/approval (SRS §10.9, §12.11, §14 docs states), Phase 6a - the
 * cross-cutting concerns Document Management has its own permission row for, on top of the Phase 2a upload/download
 * surface each of the 4 owning modules (RFQ, Quotation, OFP, Quality) keeps on its own existing endpoints (client
 * decision, ARCHITECTURE.md §8 2026-10-02: create/submit stay there, never a new generic "create" endpoint here).
 * <p>
 * Record scope is two layers, same shape as every other module's own {@code visible()} (SRS §8.2), but this module
 * owns none of the linked records itself: (1) {@link #recordVisible} resolves whether the caller may know the
 * document's linked RFQ/invitation/shipment/CAPA action exists at all, by calling into that module's own {@code api}
 * facade ({@code RfqApi}/{@code QuotationApi}/{@code OfpApi}/{@code QualityApi}) - a second deliberate exception to
 * "the later-built module reaches into the earlier one, never the reverse" (first was Phase 5d's {@code QualityApi}),
 * accepted because {@code ModuleBoundaryTest} only polices the {@code <module>.api} boundary itself, not a build
 * order, and because reusing each module's own scope rules avoids duplicating or drifting from them; (2) {@link
 * #documentVisible} applies this document's own {@code visibility} flag on top, the exact same rule {@code
 * RfqService.canSee} already uses: ERGO sees everything, the uploader always sees its own upload regardless of
 * visibility (e.g. a supplier's own quote attachment is always stored INTERNAL - {@code QuoteService.upload} - and
 * must still be visible to that supplier here), and INTERNAL otherwise reaches no external role at all.
 * <p>
 * <b>Judgment calls (flagged per the build brief, not guessed silently):</b>
 * <ul>
 * <li><b>{@code "rfq"}-linked documents shared with suppliers:</b> {@code RfqApi} has no supplier-invitation data of
 * its own (that lives in {@code quotation.domain}'s {@code Invitation}), so a supplier's scope for a {@code "rfq"}-
 * linked document is resolved by also asking {@code QuotationApi#supplierInvitedToRfq} - not one of the four facades
 * literally named in the build brief, but the only module that actually holds this fact; {@code QuotationApi} was
 * already going to be a dependency here regardless, since {@code linked_module = "quotation"} documents (supplier
 * quote attachments) are themselves keyed by invitation id, not RFQ id, and belong to the quotation module, not RFQ.
 * <li><b>One unified DTO per screen, not per audience:</b> see {@link DocumentDtos}'s own class comment - this module
 * carries no forbidden field (target price, margin, internal note/score, competitor quote) to keep off an external
 * DTO; the only per-audience difference is {@code uploadedBy}, resolved to a caller-safe label rather than exposed
 * as a raw company id/name of the other external party.
 * <li><b>Visibility changes via {@code PATCH}:</b> ERGO may set any value; a Customer/Supplier editor may set only
 * its own party's value (CUSTOMER / SUPPLIER), never ALL, INTERNAL or the other party's (security review H1,
 * ARCHITECTURE.md §8 2026-10-02 "Phase 6a security-review fixes").
 * <li><b>Replace does not accept a visibility override</b>, only an optional category override: the build brief's
 * versioning rule says a new version keeps "the same visibility... unless explicitly changed", and {@code PATCH}
 * already exists as the one place visibility changes - splitting that into two endpoints would mean two code paths
 * deciding the same thing.
 * </ul>
 */
@Service
@Transactional
public class DocumentService {

	private static final int MAX_PAGE_SIZE = 100;

	private final DocumentRepository documents;

	private final DocumentCategoryRepository categories;

	private final DocumentLinkRepository links;

	private final DocumentStorage storage;

	private final DocumentStore documentStore;

	private final RfqApi rfqApi;

	private final QuotationApi quotationApi;

	private final OfpApi ofpApi;

	private final QualityApi qualityApi;

	private final Directory directory;

	private final AuditLogService audit;

	public record DocumentFile(String fileName, String contentType, byte[] content) {
	}

	DocumentService(DocumentRepository documents, DocumentCategoryRepository categories,
			DocumentLinkRepository links, DocumentStorage storage, DocumentStore documentStore, RfqApi rfqApi,
			QuotationApi quotationApi, OfpApi ofpApi, QualityApi qualityApi, Directory directory,
			AuditLogService audit) {
		this.documents = documents;
		this.categories = categories;
		this.links = links;
		this.storage = storage;
		this.documentStore = documentStore;
		this.rfqApi = rfqApi;
		this.quotationApi = quotationApi;
		this.ofpApi = ofpApi;
		this.qualityApi = qualityApi;
		this.directory = directory;
		this.audit = audit;
	}

	// ---------------------------------------------------------------- categories (SRS §13.2.13)

	@Transactional(readOnly = true)
	public List<CategoryOption> categories() {
		return categories.findByActiveFlagTrueOrderByName().stream()
			.map(c -> new CategoryOption(c.id, c.name))
			.toList();
	}

	// ---------------------------------------------------------------- library (SRS §12.11.1)

	@Transactional(readOnly = true)
	public LibraryPage library(String linkedModule, Long categoryId, Long uploaderCompanyId, Instant from,
			Instant to, int page, int size) {
		CurrentUser me = CurrentUser.get();
		// M1: for an external caller the uploader filter would be an oracle for who uploaded a shared file, so it is
		// honoured only for its own company; any other value is ignored, making the result independent of the guess.
		Long uploaderFilter = me.companyType() == CompanyType.ERGO || me.companyId() == (uploaderCompanyId == null ? -1L : uploaderCompanyId)
				? uploaderCompanyId : null;
		List<Document> candidates = documents.findFiltered(linkedModule, categoryId, uploaderFilter);
		Map<String, Boolean> recordScopeCache = new HashMap<>();
		List<Document> visible = candidates.stream()
			.filter(d -> (from == null || !d.createdAt.isBefore(from)) && (to == null || !d.createdAt.isAfter(to)))
			.filter(d -> documentVisible(me, d)
					&& recordScopeCache.computeIfAbsent(
							d.linkedModule + ":" + d.linkedRecordId + ("quotation".equals(d.linkedModule) ? ":" + d.id : ""),
							k -> recordVisible(d)))
			.toList();
		size = Math.min(Math.max(size, 1), MAX_PAGE_SIZE); // L1: no unbounded pages
		page = Math.max(page, 0);
		int from0 = (int) Math.min((long) page * size, visible.size());
		int to0 = Math.min(from0 + size, visible.size());
		List<Document> slice = visible.subList(from0, to0);
		Map<Long, CompanyRef> companyNames = directory.companies(ids(slice, d -> d.uploadedByCompanyId));
		Map<Long, String> categoryNames = categories.findByIdIn(ids(slice, d -> d.documentCategoryId)).stream()
			.collect(Collectors.toMap(c -> c.id, c -> c.name));
		List<LibraryRow> rows = slice.stream().map(d -> row(me, d, companyNames, categoryNames)).toList();
		return new LibraryPage(rows, page, size, visible.size());
	}

	// ---------------------------------------------------------------- detail/download

	@Transactional(readOnly = true)
	public DocumentDetail get(long id) {
		CurrentUser me = CurrentUser.get();
		Document d = visibleOrThrow(me, id);
		return detail(me, d);
	}

	@Transactional(readOnly = true)
	public DocumentFile content(long id) {
		Document d = visibleOrThrow(CurrentUser.get(), id);
		return new DocumentFile(d.fileName, d.contentType, storage.get(d.id));
	}

	// ---------------------------------------------------------------- edit / replace (SRS §10.9)

	public DocumentDetail edit(long id, DocumentEditForm form) {
		CurrentUser me = CurrentUser.get();
		Document d = visibleOrThrow(me, id);
		requireEditable(me, d);
		requireModuleReplaceOpen(me, d);
		requireLatest(d);
		if (form.documentCategoryId() != null) {
			d.documentCategoryId = requireActiveCategory(form.documentCategoryId());
		}
		if (form.visibility() != null) {
			// Security review H1 fix (ARCHITECTURE.md §8, Phase 6a): a non-ERGO caller may only set visibility to the
			// single value that concerns its own company type (CUSTOMER for a customer, SUPPLIER for a supplier) -
			// never INTERNAL (hides a caller's own upload from itself), never ALL and never the OTHER party's value,
			// since both would expose the file to an external party ERGO never vetted it for (e.g. a customer widening
			// its own RFQ drawing to ALL, or a supplier widening CAPA evidence to CUSTOMER, which NcrService forbids
			// outright). Only ERGO may choose ALL/INTERNAL/the other party's value.
			if (me.companyType() != CompanyType.ERGO) {
				Visibility ownOnly = me.companyType() == CompanyType.CUSTOMER ? Visibility.CUSTOMER : Visibility.SUPPLIER;
				if (form.visibility() != ownOnly) {
					throw ApiException.unprocessable("Only ERGO may set this visibility");
				}
			}
			d.visibility = form.visibility();
		}
		return detail(me, d);
	}

	/** Versioning = supersede (client decision, ARCHITECTURE.md §8 2026-10-02): a new {@code documents} row, content
	 * copied via {@link DocumentStorage}, the old row untouched. Reuses {@code DocumentStore.store} for the actual
	 * insert (same file-type whitelist, name cleaning, sha256 and CREATE audit as every other upload in this
	 * codebase), then stamps the Phase 6a version-chain columns on the new row via JPA so that part is audited too. */
	public DocumentDetail replace(long id, String fileName, byte[] content, Long newCategoryId) {
		CurrentUser me = CurrentUser.get();
		Document d = visibleOrThrow(me, id);
		requireEditable(me, d);
		requireModuleReplaceOpen(me, d);
		documents.findByIdForReplace(d.id);
		if (documents.findBySupersedesDocumentId(d.id).isPresent()) {
			throw ApiException.conflict("A newer version already exists; replace that version instead");
		}
		// H2 fix (ARCHITECTURE.md §8, Phase 6a security review): a non-ERGO replace resets to its own-default
		// visibility, never carrying over a wider value ERGO may have granted the prior, reviewed version - new,
		// unreviewed content must not inherit trust it hasn't earned; ERGO re-widens it after reviewing the new file,
		// same as a brand-new upload starts narrow.
		Visibility newVisibility = me.companyType() == CompanyType.ERGO ? d.visibility
				: me.companyType() == CompanyType.CUSTOMER ? Visibility.CUSTOMER : Visibility.SUPPLIER;
		DocumentInfo created = documentStore.store(d.linkedModule, d.linkedRecordId, fileName, content, newVisibility);
		Document newRow = documents.findById(created.id()).orElseThrow();
		newRow.documentCategoryId = newCategoryId != null ? requireActiveCategory(newCategoryId) : d.documentCategoryId;
		newRow.versionNo = (d.versionNo == null ? 1 : d.versionNo) + 1;
		newRow.supersedesDocumentId = d.id;
		// H4 fix: keep the owning module's own single-document FK pointed at the latest version, so its own
		// view/download endpoint (OrderController#downloadPod, NcrService#downloadEvidence) doesn't keep serving the
		// superseded file while the Library shows the new one. "rfq"/"quotation" documents have no such FK (the
		// owning module lists by linked_record_id, so the new row simply appears alongside the old).
		switch (d.linkedModule) {
			case "OFP_SHIPMENT" -> ofpApi.podDocumentReplaced(d.linkedRecordId, newRow.id);
			case "QUALITY_CAPA" -> qualityApi.capaEvidenceReplaced(d.linkedRecordId, newRow.id);
			default -> {
				// no owning-module FK to repoint
			}
		}
		return detail(me, newRow);
	}

	/** H4 fix (ARCHITECTURE.md §8, Phase 6a security review): a non-ERGO replace must also respect the owning
	 * module's own upload/edit-window rule, not just this module's generic lock/approval state - otherwise the
	 * Document Library's generic replace becomes a side door around e.g. {@code quoteEditOpen=false} or a closed
	 * CAPA action. Applies to ERGO too (second review N1): each module limits uploads to specific ERGO roles. */
	/** Second security review L6: edit and lock/approve/reject act on the current version only. */
	private void requireLatest(Document d) {
		if (documents.findBySupersedesDocumentId(d.id).isPresent()) {
			throw ApiException.conflict("A newer version exists; act on that version instead");
		}
	}

	private boolean moduleWindowOpen(Document d) {
		return switch (d.linkedModule) {
			case "rfq" -> rfqApi.replaceOpenForCaller(d.linkedRecordId);
			case "quotation" -> quotationApi.replaceOpenForCaller(d.linkedRecordId);
			case "OFP_SHIPMENT" -> ofpApi.podReplaceOpenForCaller(d.linkedRecordId);
			case "QUALITY_CAPA" -> qualityApi.capaReplaceOpenForCaller(d.linkedRecordId);
			default -> false;
		};
	}

	private void requireModuleReplaceOpen(CurrentUser me, Document d) {
		if (!moduleWindowOpen(d)) {
			throw ApiException.conflict("This document cannot be replaced right now");
		}
	}

	// ---------------------------------------------------------------- lock / approve (ERGO Admin only, SRS §10.9)

	public DocumentDetail lock(long id) {
		CurrentUser me = CurrentUser.get();
		requireAdmin(me);
		Document d = visibleOrThrow(me, id);
		requireLatest(d);
		if (d.lockStatus == LockStatus.LOCKED) {
			throw ApiException.conflict("This document is already locked");
		}
		d.lockStatus = LockStatus.LOCKED;
		return detail(me, d);
	}

	/** Unlocking an {@code APPROVED} document resets it to {@code PENDING} (client decision, ARCHITECTURE.md §8
	 * 2026-10-02) and, being a reopen, requires a reason (CLAUDE.md non-negotiable; security review M4). */
	public DocumentDetail unlock(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		requireAdmin(me);
		Document d = visibleOrThrow(me, id);
		if (d.lockStatus == LockStatus.UNLOCKED) {
			throw ApiException.conflict("This document is already unlocked");
		}
		if (d.approvalStatus == ApprovalStatus.APPROVED) {
			audit.reason(required(form == null ? null : form.reason(), "A reason is required to reopen an approved document"));
			d.approvalStatus = ApprovalStatus.PENDING;
		}
		d.lockStatus = LockStatus.UNLOCKED;
		return detail(me, d);
	}

	/** Approving also locks the document (client decision, ARCHITECTURE.md §8 2026-10-02: an approved document is
	 * final) - both set in this one transaction. Comment is optional (audited if given), unlike {@link #reject}. */
	public DocumentDetail approve(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		requireAdmin(me);
		Document d = visibleOrThrow(me, id);
		requireLatest(d);
		if (d.approvalStatus != ApprovalStatus.PENDING) {
			throw ApiException.conflict("This document is not pending approval");
		}
		reasonIfGiven(form.reason());
		d.approvalStatus = ApprovalStatus.APPROVED;
		d.lockStatus = LockStatus.LOCKED;
		return detail(me, d);
	}

	/** Rejecting does not lock (client decision: a rejected document can be replaced/resubmitted). Reason required
	 * (CLAUDE.md non-negotiable). */
	public DocumentDetail reject(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		requireAdmin(me);
		Document d = visibleOrThrow(me, id);
		requireLatest(d);
		if (d.approvalStatus != ApprovalStatus.PENDING) {
			throw ApiException.conflict("This document is not pending approval");
		}
		audit.reason(required(form.reason(), "A reason is required to reject a document"));
		d.approvalStatus = ApprovalStatus.REJECTED;
		return detail(me, d);
	}

	// ---------------------------------------------------------------- record scope (SRS §8.2)

	private Document visibleOrThrow(CurrentUser me, long id) {
		Document d = documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
		if (!documentVisible(me, d) || !recordVisible(d)) {
			throw ApiException.notFound("Document");
		}
		return d;
	}

	/** Layer 1: this document's own {@code visibility} flag - the exact same rule {@code RfqService.canSee} already
	 * uses (SRS: ALL = customer + supplier + ERGO; ERGO always sees every document regardless of the stored value;
	 * the uploader always sees its own upload regardless of visibility - e.g. {@code QuoteService.upload} always
	 * stores a supplier's own quote attachment as INTERNAL, and that supplier must still see it here exactly as it
	 * already can through the quotation module's own listing). INTERNAL is excluded for every other external
	 * caller, since it is in none of the three visibility checks below. */
	private static boolean documentVisible(CurrentUser me, Document d) {
		if (me.companyType() == CompanyType.ERGO || d.uploadedByCompanyId.equals(me.companyId())) {
			return true;
		}
		return switch (me.companyType()) {
			case CUSTOMER -> d.visibility == Visibility.CUSTOMER || d.visibility == Visibility.ALL;
			case SUPPLIER -> d.visibility == Visibility.SUPPLIER || d.visibility == Visibility.ALL;
			case ERGO -> true;
		};
	}

	/** Layer 2: does the caller even know the linked record exists (ARCHITECTURE.md §8 2026-10-02 Phase 6a Library
	 * decision) - resolved by the owning module's own {@code api} facade. {@link #library} caches this per (module,
	 * id) pair within one list call so a page of documents sharing one RFQ/order doesn't re-ask the same question
	 * per row. */
	private boolean recordVisible(Document d) {
		long linkedRecordId = d.linkedRecordId;
		return switch (d.linkedModule) {
			case "rfq" -> rfqApi.visibleToCaller(linkedRecordId) || quotationApi.supplierInvitedToRfq(linkedRecordId);
			// M2: per file, not per invitation - a supplier's unsubmitted draft files stay private from ERGO.
			case "quotation" -> quotationApi.fileVisibleToCaller(linkedRecordId, d.id);
			case "OFP_SHIPMENT" -> ofpApi.shipmentVisibleToCaller(linkedRecordId);
			case "QUALITY_CAPA" -> qualityApi.capaActionVisibleToCaller(linkedRecordId);
			// ponytail: an unrecognised linked_module hides the document rather than 500s the caller; extend the
			// mapping above if a 5th linked_module string is ever introduced (ARCHITECTURE.md §8).
			default -> false;
		};
	}

	// ---------------------------------------------------------------- edit permission (SRS §10.9)

	/** Security review H3 fix (ARCHITECTURE.md §8, Phase 6a): SRS §10.9 gives ERGO Management {@code V X X X A} - view
	 * and (optional dual-control) approve only, no Edit - unlike every other ERGO role on that row, which all get
	 * {@code E}. */
	private static boolean editable(CurrentUser me, Document d) {
		// M7/L4 (client decision, ARCHITECTURE.md §8): a locked or approved document is final for everyone, ERGO Admin
		// included - Admin must unlock it (with a reason) first.
		if (d.lockStatus == LockStatus.LOCKED || d.approvalStatus == ApprovalStatus.APPROVED) {
			return false;
		}
		if (me.companyType() == CompanyType.ERGO) {
			return me.role() != Role.ERGO_MANAGEMENT;
		}
		return d.uploadedByCompanyId.equals(me.companyId());
	}

	private static void requireEditable(CurrentUser me, Document d) {
		if (!editable(me, d)) {
			throw ApiException.conflict("This document cannot be edited right now");
		}
	}

	private static void requireAdmin(CurrentUser me) {
		if (me.role() != Role.ERGO_ADMIN) {
			throw ApiException.forbidden("Only ERGO Admin may do this");
		}
	}

	private Long requireActiveCategory(long categoryId) {
		DocumentCategory c = categories.findById(categoryId).orElse(null);
		if (c == null || !c.activeFlag) {
			throw ApiException.unprocessable("Category not found or not active");
		}
		return c.id;
	}

	// ---------------------------------------------------------------- view assembly

	private Set<DocumentAction> actions(CurrentUser me, Document d, boolean hasLaterVersion) {
		Set<DocumentAction> result = EnumSet.noneOf(DocumentAction.class);
		if (editable(me, d) && !hasLaterVersion && moduleWindowOpen(d)) {
			result.add(DocumentAction.EDIT);
			result.add(DocumentAction.REPLACE);
		}
		if (me.role() == Role.ERGO_ADMIN) {
			result.add(d.lockStatus == LockStatus.UNLOCKED ? DocumentAction.LOCK : DocumentAction.UNLOCK);
			if (d.approvalStatus == ApprovalStatus.PENDING) {
				result.add(DocumentAction.APPROVE);
				result.add(DocumentAction.REJECT);
			}
		}
		return result;
	}

	private LibraryRow row(CurrentUser me, Document d, Map<Long, CompanyRef> companies, Map<Long, String> categoryNames) {
		boolean hasLaterVersion = documents.findBySupersedesDocumentId(d.id).isPresent();
		return new LibraryRow(d.id, d.fileName, d.contentType, d.fileSize, d.documentCategoryId,
				categoryNames.get(d.documentCategoryId), d.visibility, uploadedByLabel(me, d, companies), d.createdAt,
				d.updatedAt, versionNo(d), hasLaterVersion, d.lockStatus, d.approvalStatus, d.linkedModule,
				d.linkedRecordId, actions(me, d, hasLaterVersion));
	}

	private DocumentDetail detail(CurrentUser me, Document d) {
		Map<Long, CompanyRef> companies = directory.companies(List.of(d.uploadedByCompanyId));
		// Not Map.of()/get(null): an immutable Map.of() rejects a null-key lookup outright, and documentCategoryId
		// is commonly null (no category assigned).
		String categoryName = d.documentCategoryId == null ? null
				: categories.findById(d.documentCategoryId).map(c -> c.name).orElse(null);
		boolean hasLaterVersion = documents.findBySupersedesDocumentId(d.id).isPresent();
		return new DocumentDetail(d.id, d.fileName, d.contentType, d.fileSize, d.documentCategoryId, categoryName,
				d.visibility, uploadedByLabel(me, d, companies), d.createdAt, d.updatedAt, versionNo(d),
				visiblePredecessor(me, d), versionHistory(me, d), d.lockStatus, d.approvalStatus, d.linkedModule,
				d.linkedRecordId, secondaryLinks(me, d.id), actions(me, d, hasLaterVersion));
	}

	/** Walks the version chain both ways from {@code d}: every ancestor via {@code supersedesDocumentId}, then every
	 * descendant via {@link DocumentRepository#findBySupersedesDocumentId} - the chain is linear by construction
	 * ({@link #replace} refuses to supersede a document that already has a later version), so neither walk needs a
	 * recursive query. Security review H6 fix: an earlier/later version can carry a narrower {@code visibility} than
	 * {@code d} itself (e.g. an INTERNAL draft later shared as ALL, or a customer-only version superseded by one
	 * ERGO also shared with the supplier) - each version is filtered through {@link #documentVisible} exactly like
	 * any other document, so a caller never learns even the filename of a version it could not open directly. */
	private List<VersionSummary> versionHistory(CurrentUser me, Document d) {
		List<Document> chain = new ArrayList<>();
		Document cursor = d;
		while (cursor.supersedesDocumentId != null) {
			cursor = documents.findById(cursor.supersedesDocumentId).orElseThrow();
			chain.add(0, cursor);
		}
		chain.add(d);
		Document tail = d;
		while (true) {
			Document next = documents.findBySupersedesDocumentId(tail.id).orElse(null);
			if (next == null) {
				break;
			}
			chain.add(next);
			tail = next;
		}
		return chain.stream()
			.filter(v -> documentVisible(me, v) && recordVisible(v))
			.map(v -> new VersionSummary(v.id, versionNo(v), v.fileName, v.createdAt, v.id.equals(d.id)))
			.toList();
	}

	private Long visiblePredecessor(CurrentUser me, Document d) {
		return d.supersedesDocumentId == null ? null
				: documents.findById(d.supersedesDocumentId).filter(v -> documentVisible(me, v) && recordVisible(v))
					.map(v -> v.id).orElse(null);
	}

	/** Links have no write path yet (6a); until they do and each target is scope-checked, only ERGO is shown them. */
	private List<SecondaryLink> secondaryLinks(CurrentUser me, long documentId) {
		if (me.companyType() != CompanyType.ERGO) {
			return List.of();
		}
		return links.findByDocumentId(documentId).stream()
			.map(l -> new SecondaryLink(l.linkedModule, l.linkedRecordId))
			.toList();
	}

	/** Two-way anonymity (SRS, same rule as RFQ/NPD/OFP): ERGO always sees the real uploader; an external caller sees
	 * "ERGO" for an ERGO upload, its own company's name for its own upload, and {@code null} (never disclosed) for
	 * the other external party's upload. */
	private static String uploadedByLabel(CurrentUser me, Document d, Map<Long, CompanyRef> companies) {
		CompanyRef uploader = companies.get(d.uploadedByCompanyId);
		if (me.companyType() == CompanyType.ERGO) {
			return uploader == null ? null : uploader.name();
		}
		if (uploader != null && uploader.type() == CompanyType.ERGO) {
			return "ERGO";
		}
		if (d.uploadedByCompanyId.equals(me.companyId())) {
			return uploader == null ? "You" : uploader.name();
		}
		return null;
	}

	private static int versionNo(Document d) {
		return d.versionNo == null ? 1 : d.versionNo;
	}

	private static Set<Long> ids(Collection<Document> docs, Function<Document, Long> field) {
		return docs.stream().map(field).filter(Objects::nonNull).collect(Collectors.toSet());
	}

	private static String text(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String required(String value, String message) {
		String t = text(value);
		if (t == null) {
			throw ApiException.unprocessable(message);
		}
		return t;
	}

	private void reasonIfGiven(String comment) {
		String t = text(comment);
		if (t != null) {
			audit.reason(t);
		}
	}

}
