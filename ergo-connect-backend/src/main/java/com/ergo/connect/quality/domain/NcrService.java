package com.ergo.connect.quality.domain;

import static com.ergo.connect.common.security.Role.CUSTOMER_KAM;
import static com.ergo.connect.common.security.Role.CUSTOMER_MANAGEMENT;
import static com.ergo.connect.common.security.Role.CUSTOMER_NPD;
import static com.ergo.connect.common.security.Role.CUSTOMER_OFP;
import static com.ergo.connect.common.security.Role.CUSTOMER_QUALITY;
import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;
import static com.ergo.connect.common.security.Role.ERGO_NPD;
import static com.ergo.connect.common.security.Role.ERGO_OFP;
import static com.ergo.connect.common.security.Role.ERGO_QUALITY;
import static com.ergo.connect.common.security.Role.SUPPLIER_KAM;
import static com.ergo.connect.common.security.Role.SUPPLIER_NPD;
import static com.ergo.connect.common.security.Role.SUPPLIER_OFP;
import static com.ergo.connect.common.security.Role.SUPPLIER_QUALITY;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
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
import com.ergo.connect.common.event.NotificationKind;
import com.ergo.connect.common.event.Notifier;
import com.ergo.connect.common.event.Recipients;
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
import com.ergo.connect.npd.api.NpdApi;
import com.ergo.connect.ofp.api.OfpApi;
import com.ergo.connect.quality.domain.QualityDtos.CapaActionForm;
import com.ergo.connect.quality.domain.QualityDtos.CapaActionView;
import com.ergo.connect.quality.domain.QualityDtos.CustomerCapaActionView;
import com.ergo.connect.quality.domain.QualityDtos.CustomerNcrRow;
import com.ergo.connect.quality.domain.QualityDtos.CustomerNcrView;
import com.ergo.connect.quality.domain.QualityDtos.NcrAction;
import com.ergo.connect.quality.domain.QualityDtos.NcrDetail;
import com.ergo.connect.quality.domain.QualityDtos.NcrForm;
import com.ergo.connect.quality.domain.QualityDtos.NcrRow;
import com.ergo.connect.quality.domain.QualityDtos.NcrSummary;
import com.ergo.connect.quality.domain.QualityDtos.NcrUpdateForm;
import com.ergo.connect.quality.domain.QualityDtos.NcrView;
import com.ergo.connect.quality.domain.QualityDtos.ReasonForm;
import com.ergo.connect.quality.domain.QualityDtos.SupplierCapaActionView;
import com.ergo.connect.quality.domain.QualityDtos.SupplierNcrRow;
import com.ergo.connect.quality.domain.QualityDtos.SupplierNcrView;

/**
 * NCRs and CAPA actions (SRS §10.8, §13.6.3/.5, §14.7), Phase 5b - the immediate follow-on to {@link
 * InspectionService}, matching its shape exactly: one service, one rule book ({@link #allowed}), no public {@code
 * setStatus}, record scope resolved per role the same way.
 * <p>
 * Record scope: identical partition to {@code InspectionService} - ERGO Admin/Management/Quality unrestricted; ERGO
 * KAM its assigned customer accounts; ERGO NPD/OFP its own linked module; every supplier role its own company
 * (direct {@code supplierCompanyId} match); every customer role its own company gated by {@code customer_visible}
 * (security review H2 fix - originally built without this gate on the theory that an NCR tagged to a customer is by
 * definition raised for them to see; corrected to match {@code Inspection.customerVisible} instead, since
 * supplier-authored content should reach the customer only after an ERGO editor reviews and shares it, not the
 * instant it exists).
 * <p>
 * <b>Judgment calls (flagged per the build brief, not guessed silently):</b>
 * <ul>
 * <li><b>Internal vs shared fields:</b> {@code containmentAction}/{@code rootCause}/{@code correctiveAction}/
 * {@code preventiveAction} are shared with the linked company in both external views - unlike {@code
 * Inspection.remarks} (dropped from every external view, security review H1), these are exactly what an NCR raised
 * against a party exists to communicate and what a supplier is expected to fill in as its own CAPA response, not an
 * ERGO-internal note. {@code effectivenessStatus} (ERGO's own post-closure judgment of whether the CAPA actually
 * worked) and {@link Ncr#ownerUserId}/{@code CapaAction.ownerUserId} (internal staff-assignment identity) are kept
 * ERGO-only, the same H1 reasoning applied to {@code Inspection.remarks}/{@code inspectedBy}. CAPA evidence
 * (document id/file name) is shared with the supplier but not the customer (security review H1 fix) - a customer
 * must never be able to download the other party's evidence file, the same rule that makes POD ERGO-only.
 * <li><b>Self-approval:</b> security review H3 fix - {@link Ncr#ownerUserId} alone was trivially bypassable (null by
 * default, reassignable mid-flow), so {@link Ncr#closureRequestedByUserId} was added, set atomically by {@link
 * #submitForClosure} the same way {@code Inspection.inspectedByUserId} is set atomically by {@code submit} - the
 * person who actually requested closure, the NCR's original creator, and its current owner (if any) are all blocked
 * from later closing or sending it back.
 * <li><b>Workflow surface beyond the SRS's named states:</b> the linear five-stage path plus {@code SEND_BACK}
 * (PENDING_CLOSURE_APPROVAL -&gt; CAPA_IN_PROGRESS, reason required) and {@code REOPEN} (CLOSED -&gt; OPEN, reason
 * required) mirror {@code InspectionService}'s own reopen-only-reverse-path precedent and CLAUDE.md's "reopen, reject
 * and send-back require a reason" non-negotiable; the SRS names the five forward states but not these two reverse
 * transitions explicitly.
 * </ul>
 */
@Service
@Transactional
public class NcrService {

	private static final String CAPA_MODULE = "QUALITY_CAPA";

	/** SRS §10.8 Create/Edit/Submit, ERGO side - same set as {@code InspectionService.EDITORS}. */
	private static final Set<Role> EDITORS = EnumSet.of(ERGO_ADMIN, ERGO_QUALITY);

	/** Supplier Quality only, scoped to NCRs linked to its own company (security review L4 - see {@link #editor}) -
	 * same as {@code InspectionService.SUPPLIER_EDITOR}. */
	private static final Role SUPPLIER_EDITOR = SUPPLIER_QUALITY;

	/** SRS §10.8 Approve, its own authority separate from edit: ERGO Management has no create/edit/submit right at
	 * all but decides closure/send-back/reopen. Supplier Quality is deliberately absent. */
	private static final Set<Role> APPROVERS = EnumSet.of(ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_QUALITY);

	private static final Set<Role> SUPPLIER_VIEWERS = EnumSet.of(SUPPLIER_QUALITY, SUPPLIER_NPD, SUPPLIER_OFP,
			SUPPLIER_KAM);

	private static final Set<Role> CUSTOMER_VIEWERS = EnumSet.of(CUSTOMER_QUALITY, CUSTOMER_NPD, CUSTOMER_OFP,
			CUSTOMER_KAM, CUSTOMER_MANAGEMENT);

	private final NcrRepository ncrs;

	private final CapaActionRepository capaActions;

	private final Directory directory;

	private final AuditLogService audit;

	private final NpdApi npdApi;

	private final OfpApi ofpApi;

	private final DocumentStore documents;

	private final JdbcTemplate jdbc;

	private final Notifier notifier;

	NcrService(NcrRepository ncrs, CapaActionRepository capaActions, Directory directory, AuditLogService audit,
			NpdApi npdApi, OfpApi ofpApi, DocumentStore documents, JdbcTemplate jdbc, Notifier notifier) {
		this.notifier = notifier;
		this.ncrs = ncrs;
		this.capaActions = capaActions;
		this.directory = directory;
		this.audit = audit;
		this.npdApi = npdApi;
		this.ofpApi = ofpApi;
		this.documents = documents;
		this.jdbc = jdbc;
	}

	// ---------------------------------------------------------------- reads

	@Transactional(readOnly = true)
	public List<? extends NcrSummary> list() {
		CurrentUser me = CurrentUser.get();
		if (SUPPLIER_VIEWERS.contains(me.role())) {
			return ncrs.findBySupplierCompanyIdOrderByIdDesc(me.companyId()).stream()
				.map(n -> new SupplierNcrRow(n.id, n.ncrNumber, n.linkedModule, n.linkedRecordId, n.issueCategory,
						n.currentStatus, n.targetClosureDate))
				.toList();
		}
		if (CUSTOMER_VIEWERS.contains(me.role())) {
			return ncrs.findByCustomerCompanyIdAndCustomerVisibleTrueOrderByIdDesc(me.companyId()).stream()
				.map(n -> new CustomerNcrRow(n.id, n.ncrNumber, n.linkedModule, n.linkedRecordId, n.issueCategory,
						n.currentStatus, n.targetClosureDate))
				.toList();
		}
		List<Ncr> visible = switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_QUALITY -> ncrs.findAllByOrderByIdDesc();
			case ERGO_KAM -> {
				Set<Long> accounts = directory.accountsOf(me.userId());
				yield accounts.isEmpty() ? List.of() : ncrs.findByCustomerCompanyIdInOrderByIdDesc(accounts);
			}
			case ERGO_NPD -> ncrs.findByLinkedModuleOrderByIdDesc(LinkedModule.NPD);
			case ERGO_OFP -> ncrs.findByLinkedModuleOrderByIdDesc(LinkedModule.OFP);
			default -> List.of(); // SRS §10.8 X: ERGO RFQ and every other role not handled above/below
		};
		Map<Long, CompanyRef> customers = directory.companies(ids(visible, n -> n.customerCompanyId));
		Map<Long, CompanyRef> suppliers = directory.companies(ids(visible, n -> n.supplierCompanyId));
		return visible.stream()
			.map(n -> new NcrRow(n.id, n.ncrNumber, n.linkedModule, n.linkedRecordId, name(customers, n.customerCompanyId),
					name(suppliers, n.supplierCompanyId), n.issueCategory, n.currentStatus, n.targetClosureDate))
			.toList();
	}

	@Transactional(readOnly = true)
	public NcrDetail get(long id) {
		CurrentUser me = CurrentUser.get();
		return view(me, visible(me, id));
	}

	// ---------------------------------------------------------------- create / edit

	public NcrDetail create(NcrForm form) {
		CurrentUser me = CurrentUser.get();
		boolean ergoEditor = EDITORS.contains(me.role());
		boolean supplierEditor = me.role() == SUPPLIER_EDITOR;
		if (!ergoEditor && !supplierEditor) {
			throw ApiException.forbidden("Only ERGO Quality/Admin or Supplier Quality may raise an NCR");
		}
		Ncr n = new Ncr();
		n.linkedModule = form.linkedModule();
		n.linkedRecordId = form.linkedRecordId();
		n.issueCategory = form.issueCategory().trim();
		n.issueDescription = form.issueDescription().trim();
		n.targetClosureDate = form.targetClosureDate();
		if (form.linkedModule() == LinkedModule.NPD || form.linkedModule() == LinkedModule.OFP) {
			// Security review M5 precedent: companies are always derived from the linked NPD/OFP record server-side,
			// never trusted from the client - see applyLinkedCompanies.
			applyLinkedCompanies(n, form.linkedModule(), form.linkedRecordId(), supplierEditor ? me.companyId() : null);
		}
		else if (supplierEditor) {
			// Two-way anonymity: a supplier's own NCR is tagged to its own company only.
			n.supplierCompanyId = me.companyId();
			n.customerCompanyId = null;
		}
		else {
			n.customerCompanyId = requireCompanyIfGiven(form.customerCompanyId(), CompanyType.CUSTOMER, "Customer");
			n.supplierCompanyId = requireCompanyIfGiven(form.supplierCompanyId(), CompanyType.SUPPLIER, "Supplier");
		}
		// ownerUserId is ERGO-only to set (judgment call, class comment): a supplier's own create leaves it null.
		// Security review M3: validated, not trusted verbatim - see resolveNcrOwner.
		n.ownerUserId = ergoEditor ? resolveNcrOwner(me, form.ownerUserId()) : null;
		// Security review H2: never trusted from the client at all (no field on NcrForm) and never defaulted true on
		// create, even for an ERGO creator - customer visibility is something an ERGO editor turns on later, once
		// there is something reviewed worth sharing.
		n.customerVisible = false;
		n.currentStatus = NcrStatus.OPEN;
		n.createdByUserId = me.userId();
		n.createdByCompanyId = me.companyId();
		n.ncrNumber = nextNcrNumber();
		ncrs.save(n);
		// SRS §14.9 record assignment: a supplier-raised NCR is for ERGO; an ERGO-raised one is for the supplier it is against
		// (and the named owner). The customer is not told at creation - an NCR starts hidden from the customer.
		notifier.send(NotificationKind.NCR_RAISED, "ncr", n.id, n.ncrNumber, n.issueCategory,
				ergoEditor ? QualityNotices.supplier(n.supplierCompanyId).and(n.ownerUserId == null ? Recipients.none()
						: Recipients.users(java.util.Set.of(n.ownerUserId))) : QualityNotices.ergo());
		return view(me, n);
	}

	private String nextNcrNumber() {
		return "NCR-%d-%05d".formatted(Year.now().getValue(),
				jdbc.queryForObject("SELECT nextval('ncr_number_seq')", Long.class));
	}

	/** Security review M5 precedent: resolves an NPD/OFP-linked NCR's companies through {@code npd.api}/{@code
	 * ofp.api} instead of trusting the client, and - when the creator is a supplier - verifies the linked record is
	 * actually theirs before any company id from it is ever returned to them. Security review L5: "not found" and
	 * "belongs to a different supplier" share one generic message - two different messages would let a supplier
	 * enumerate valid NPD/OFP ids by probing which response they get back. */
	private void applyLinkedCompanies(Ncr n, LinkedModule module, long linkedRecordId, Long supplierCallerCompanyId) {
		long customerCompanyId;
		long supplierCompanyId;
		if (module == LinkedModule.NPD) {
			NpdApi.ProjectRef ref = npdApi.projectRef(linkedRecordId)
				.orElseThrow(() -> ApiException.unprocessable("Linked record not found or not accessible"));
			customerCompanyId = ref.customerCompanyId();
			supplierCompanyId = ref.supplierCompanyId();
		}
		else {
			OfpApi.OrderRef ref = ofpApi.orderRef(linkedRecordId)
				.orElseThrow(() -> ApiException.unprocessable("Linked record not found or not accessible"));
			customerCompanyId = ref.customerCompanyId();
			supplierCompanyId = ref.supplierCompanyId();
		}
		if (supplierCallerCompanyId != null && !supplierCallerCompanyId.equals(supplierCompanyId)) {
			throw ApiException.unprocessable("Linked record not found or not accessible");
		}
		n.customerCompanyId = customerCompanyId;
		n.supplierCompanyId = supplierCompanyId;
	}

	public NcrDetail edit(long id, NcrUpdateForm form) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, id);
		require(NcrAction.EDIT, me, n);
		applyUpdate(me, n, form, ergoEditor(me));
		return view(me, n);
	}

	/** Applies every progressively-filled field, mirroring {@code InspectionService.applyUpdate}'s "who may set
	 * what" split and its reassignment-clears-content handling (security review M6 precedent). */
	private void applyUpdate(CurrentUser me, Ncr n, NcrUpdateForm form, boolean ergoEditor) {
		String previousContent = describeContent(n);
		n.issueCategory = form.issueCategory().trim();
		n.issueDescription = form.issueDescription().trim();
		n.targetClosureDate = form.targetClosureDate();
		// Shared content fields (class comment): both an ERGO editor and the NCR's own supplier editor may fill
		// these in - this is the point of raising/responding to an NCR, unlike Inspection.remarks.
		n.containmentAction = text(form.containmentAction());
		n.rootCause = text(form.rootCause());
		n.correctiveAction = text(form.correctiveAction());
		n.preventiveAction = text(form.preventiveAction());
		boolean reassigned = false;
		if (ergoEditor) {
			// ERGO-internal fields (class comment): never trusted from a supplier's own edit.
			n.effectivenessStatus = text(form.effectivenessStatus());
			// Security review M3: validated, not trusted verbatim - see resolveNcrOwner.
			n.ownerUserId = resolveNcrOwner(me, form.ownerUserId());
			// Security review H2: ERGO-only toggle, full-replace like every other field here (NcrUpdateForm javadoc).
			n.customerVisible = form.customerVisible();
			// Security review M5 precedent: an NPD/OFP-linked NCR's companies come only from applyLinkedCompanies at
			// creation, never a later edit.
			if (n.linkedModule != LinkedModule.NPD && n.linkedModule != LinkedModule.OFP) {
				// Security review M2(a): a company field omitted from the PUT (null) means "leave it unchanged", not
				// "clear it" - only a form that actually supplies a value is validated/applied.
				Long newCustomerCompanyId = form.customerCompanyId() == null ? n.customerCompanyId
						: requireCompanyIfGiven(form.customerCompanyId(), CompanyType.CUSTOMER, "Customer");
				Long newSupplierCompanyId = form.supplierCompanyId() == null ? n.supplierCompanyId
						: requireCompanyIfGiven(form.supplierCompanyId(), CompanyType.SUPPLIER, "Supplier");
				reassigned = !Objects.equals(n.customerCompanyId, newCustomerCompanyId)
						|| !Objects.equals(n.supplierCompanyId, newSupplierCompanyId);
				// Security review M2(b): an actual reassignment - as opposed to merely resending the existing values
				// (the full-replace convention every other edit already relies on) - resets progress and deletes CAPA
				// actions with no reason required, so it is only allowed while the NCR is still OPEN.
				if (reassigned && n.currentStatus != NcrStatus.OPEN) {
					throw ApiException.conflict("Companies can only be reassigned while the NCR is OPEN");
				}
				n.customerCompanyId = newCustomerCompanyId;
				n.supplierCompanyId = newSupplierCompanyId;
			}
		}
		if (reassigned) {
			clearAuthoredContentOnReassignment(n, previousContent);
		}
	}

	private static String describeContent(Ncr n) {
		return "containment=" + n.containmentAction + "; rootCause=" + n.rootCause + "; corrective="
				+ n.correctiveAction + "; preventive=" + n.preventiveAction + "; effectiveness=" + n.effectivenessStatus;
	}

	/** Security review M6 precedent: a reassignment clears the progressively-filled fields outright - including
	 * whatever this same request also tried to set for them - and resets the workflow to OPEN, since progress made
	 * describing/investigating the wrong party is invalid the moment the party changes; {@code issueCategory}/{@code
	 * issueDescription} are left as-is (NOT NULL columns describing the underlying fact pattern, not party-specific
	 * judgment) - a narrower clear than {@code InspectionService}'s equivalent, flagged here rather than guessed. */
	private void clearAuthoredContentOnReassignment(Ncr n, String previousContent) {
		List<CapaAction> existingCapaActions = capaActions.findByNcrIdOrderById(n.id);
		n.containmentAction = null;
		n.rootCause = null;
		n.correctiveAction = null;
		n.preventiveAction = null;
		n.effectivenessStatus = null;
		n.currentStatus = NcrStatus.OPEN;
		n.closureDate = null;
		audit.record(Ncr.class, n.id, "REASSIGNED_CONTENT_CLEARED", "content", previousContent
				+ "; capaActions=" + existingCapaActions.size(), null);
		if (!existingCapaActions.isEmpty()) {
			capaActions.deleteByNcrId(n.id);
		}
	}

	// ---------------------------------------------------------------- workflow (SRS §14.7)

	/** OPEN -> CONTAINMENT_IN_PROGRESS. */
	public NcrDetail moveToContainment(long id, NcrUpdateForm form) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, id);
		require(NcrAction.MOVE_TO_CONTAINMENT, me, n);
		applyUpdate(me, n, form, ergoEditor(me));
		n.currentStatus = NcrStatus.CONTAINMENT_IN_PROGRESS;
		told(n, "Containment in progress");
		return view(me, n);
	}

	/** CONTAINMENT_IN_PROGRESS -> ROOT_CAUSE_IN_PROGRESS: containment must be on record first. */
	public NcrDetail moveToRootCause(long id, NcrUpdateForm form) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, id);
		require(NcrAction.MOVE_TO_ROOT_CAUSE, me, n);
		applyUpdate(me, n, form, ergoEditor(me));
		if (n.containmentAction == null) {
			throw ApiException.unprocessable("Containment action is required before moving to root cause investigation");
		}
		n.currentStatus = NcrStatus.ROOT_CAUSE_IN_PROGRESS;
		told(n, "Root cause investigation");
		return view(me, n);
	}

	/** ROOT_CAUSE_IN_PROGRESS -> CAPA_IN_PROGRESS. Hard gate (SRS §17.6): root cause must be recorded before CAPA can
	 * begin. */
	public NcrDetail moveToCapa(long id, NcrUpdateForm form) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, id);
		require(NcrAction.MOVE_TO_CAPA, me, n);
		applyUpdate(me, n, form, ergoEditor(me));
		requireRootCause(n);
		n.currentStatus = NcrStatus.CAPA_IN_PROGRESS;
		told(n, "CAPA in progress");
		return view(me, n);
	}

	/** CAPA_IN_PROGRESS -> PENDING_CLOSURE_APPROVAL: the corrective/preventive plan must be on record before it can
	 * be sent for approval (business validation, same shape as Inspection's submit-time gate). */
	public NcrDetail submitForClosure(long id, NcrUpdateForm form) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, id);
		require(NcrAction.SUBMIT_FOR_CLOSURE, me, n);
		applyUpdate(me, n, form, ergoEditor(me));
		if (n.correctiveAction == null || n.preventiveAction == null) {
			throw ApiException.unprocessable(
					"Corrective and preventive actions must be recorded before requesting closure");
		}
		// Security review H3: who actually moved this into PENDING_CLOSURE_APPROVAL, set atomically by this same act
		// (unlike ownerUserId, never a separately-editable assignment field) - closes the self-approval gap.
		n.closureRequestedByUserId = me.userId();
		n.currentStatus = NcrStatus.PENDING_CLOSURE_APPROVAL;
		notifier.send(NotificationKind.NCR_CLOSURE_REQUESTED, "ncr", n.id, n.ncrNumber, null,
				Recipients.ergo(Role.ERGO_ADMIN, Role.ERGO_QUALITY, Role.ERGO_MANAGEMENT));
		return view(me, n);
	}

	/** PENDING_CLOSURE_APPROVAL -> CLOSED. Hard gates (SRS §17.6): root cause on record (defensive re-check - already
	 * required to reach CAPA_IN_PROGRESS) and at least one CAPA action with evidence attached. */
	public NcrDetail close(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, id);
		require(NcrAction.CLOSE, me, n);
		requireRootCause(n);
		if (capaActions.findByNcrIdOrderById(n.id).stream().noneMatch(a -> a.evidenceDocumentId != null)) {
			throw ApiException.unprocessable(
					"At least one CAPA action must have evidence attached before the NCR can be closed");
		}
		reasonIfGiven(form.reason());
		n.currentStatus = NcrStatus.CLOSED;
		n.closureDate = LocalDate.now();
		told(n, "Closed");
		return view(me, n);
	}

	/** PENDING_CLOSURE_APPROVAL -> CAPA_IN_PROGRESS: an approver is not satisfied the CAPA is complete. Reason
	 * required (CLAUDE.md non-negotiable). */
	public NcrDetail sendBack(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, id);
		require(NcrAction.SEND_BACK, me, n);
		audit.reason(required(form.reason(), "A reason is required to send an NCR back for more CAPA work"));
		n.currentStatus = NcrStatus.CAPA_IN_PROGRESS;
		told(n, "Sent back for more CAPA work");
		return view(me, n);
	}

	/** CLOSED -> OPEN, the only reopen path. Reason required (CLAUDE.md non-negotiable). */
	public NcrDetail reopen(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, id);
		require(NcrAction.REOPEN, me, n);
		audit.reason(required(form.reason(), "A reason is required to reopen an NCR"));
		n.currentStatus = NcrStatus.OPEN;
		n.closureDate = null;
		told(n, "Reopened");
		return view(me, n);
	}

	private void requireRootCause(Ncr n) {
		if (n.rootCause == null) {
			throw ApiException.unprocessable("Root cause must be recorded before CAPA can begin");
		}
	}

	// ---------------------------------------------------------------- CAPA actions

	public NcrDetail createCapaAction(long ncrId, CapaActionForm form) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, ncrId);
		requireCapaEditable(me, n);
		CapaAction a = new CapaAction();
		a.ncrId = n.id;
		a.actionType = form.actionType();
		a.actionDescription = form.actionDescription().trim();
		a.ownerUserId = resolveCapaOwner(me, n, form.ownerUserId());
		a.targetDate = form.targetDate();
		a.status = CapaActionStatus.OPEN;
		capaActions.save(a);
		return view(me, n);
	}

	public NcrDetail editCapaAction(long ncrId, long capaActionId, CapaActionForm form) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, ncrId);
		CapaAction a = capaAction(n, capaActionId);
		requireCapaEditable(me, n);
		if (a.status == CapaActionStatus.CLOSED) {
			throw ApiException.conflict("This CAPA action is closed and can no longer be edited");
		}
		a.actionType = form.actionType();
		a.actionDescription = form.actionDescription().trim();
		a.ownerUserId = resolveCapaOwner(me, n, form.ownerUserId());
		a.targetDate = form.targetDate();
		return view(me, n);
	}

	/** Hard gate (SRS §17.6): a CAPA action cannot be closed without its parent NCR's root cause on record. */
	public NcrDetail closeCapaAction(long ncrId, long capaActionId) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, ncrId);
		CapaAction a = capaAction(n, capaActionId);
		requireCapaEditable(me, n);
		if (a.status == CapaActionStatus.CLOSED) {
			throw ApiException.conflict("This CAPA action is already closed");
		}
		requireRootCause(n);
		a.status = CapaActionStatus.CLOSED;
		a.closedAt = Instant.now();
		return view(me, n);
	}

	/** Evidence upload (SRS §17.6): reuses the Documents module, same pattern as {@code OrderService.uploadPod}.
	 * Security review M4: stored as {@code SUPPLIER} when the uploader is the supplier editor, {@code INTERNAL} when
	 * ERGO uploads - a best-effort tightening of Documents' own coarse flag for when it grows a caller-facing listing
	 * of its own; the real gate stays {@link #visible}/{@link #downloadEvidence}'s own party-specific checks. */
	public NcrDetail uploadEvidence(long ncrId, long capaActionId, String fileName, byte[] content) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, ncrId);
		CapaAction a = capaAction(n, capaActionId);
		requireCapaEditable(me, n);
		if (a.status == CapaActionStatus.CLOSED) {
			throw ApiException.conflict("This CAPA action is closed and can no longer be edited");
		}
		Visibility visibility = me.role() == SUPPLIER_EDITOR ? Visibility.SUPPLIER : Visibility.INTERNAL;
		DocumentInfo info = documents.store(CAPA_MODULE, a.id, fileName, content, visibility);
		a.evidenceDocumentId = info.id();
		// Evidence is never shown to customers; a supplier's file is for ERGO, ERGO's own is internal.
		if (me.role() == SUPPLIER_EDITOR) {
			notifier.send(NotificationKind.DOCUMENT_UPLOADED, "document", info.id(), n.ncrNumber, info.fileName(),
					QualityNotices.ergo());
		}
		return view(me, n);
	}

	public record EvidenceFile(DocumentInfo info, byte[] content) {
	}

	@Transactional(readOnly = true)
	public EvidenceFile downloadEvidence(long ncrId, long capaActionId) {
		CurrentUser me = CurrentUser.get();
		Ncr n = visible(me, ncrId);
		// Security review H1: a customer must never download CAPA evidence (letterhead/signatures of the other
		// party) - full stop, regardless of who uploaded it. ERGO (every role visible() already scopes) and the
		// NCR's own supplier company (the only supplier visible() lets through) may download.
		if (CUSTOMER_VIEWERS.contains(me.role())) {
			throw ApiException.notFound("Evidence document");
		}
		CapaAction a = capaAction(n, capaActionId);
		if (a.evidenceDocumentId == null) {
			throw ApiException.notFound("Evidence document");
		}
		DocumentInfo info = documents.find(CAPA_MODULE, a.id, a.evidenceDocumentId)
			.orElseThrow(() -> ApiException.notFound("Evidence document"));
		return new EvidenceFile(info, documents.content(info));
	}

	private CapaAction capaAction(Ncr n, long capaActionId) {
		CapaAction a = capaActions.findById(capaActionId).orElse(null);
		// Long != Long is reference equality, not value equality - unbox one side deliberately.
		if (a == null || a.ncrId == null || a.ncrId.longValue() != n.id.longValue()) {
			throw ApiException.notFound("CAPA action");
		}
		return a;
	}

	/** CAPA action create/edit/close/evidence-upload share the same gate: an editor (ERGO or the NCR's own supplier)
	 * may work on them any time before the parent NCR is closed or pending closure approval - looser than the NCR's
	 * own state machine ({@link #allowed}) since CAPA actions are the working detail underneath a single {@code
	 * CAPA_IN_PROGRESS}-and-beyond stage, not a state machine of their own (V18 migration comment). Security review
	 * M1: also frozen during {@code PENDING_CLOSURE_APPROVAL} - content and evidence must not change while an
	 * approver is mid-review, after the same content has already been submitted for closure. */
	private void requireCapaEditable(CurrentUser me, Ncr n) {
		if (!editor(me, n) || n.currentStatus == NcrStatus.CLOSED
				|| n.currentStatus == NcrStatus.PENDING_CLOSURE_APPROVAL) {
			throw ApiException.conflict("CAPA actions cannot be changed on this NCR right now");
		}
	}

	/** SRS §14.9 status change: everyone on the NCR; the customer is dropped at delivery unless it is customer-visible.
	 * The detail is the new stage only - never a send-back or reopen reason. */
	private void told(Ncr n, String detail) {
		notifier.send(NotificationKind.NCR_UPDATED, "ncr", n.id, n.ncrNumber, detail,
				QualityNotices.everyone(n.customerCompanyId, n.supplierCompanyId));
	}

	// ---------------------------------------------------------------- rules

	/** Record scope. Out-of-scope NCRs do not exist for the caller (404, not 403). */
	Ncr visible(CurrentUser me, long id) {
		Ncr n = ncrs.findById(id).orElse(null);
		boolean inScope = n != null && switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_QUALITY -> true;
			case ERGO_KAM -> n.customerCompanyId != null && directory.accountsOf(me.userId()).contains(n.customerCompanyId);
			case ERGO_NPD -> n.linkedModule == LinkedModule.NPD;
			case ERGO_OFP -> n.linkedModule == LinkedModule.OFP;
			case SUPPLIER_QUALITY, SUPPLIER_NPD, SUPPLIER_OFP, SUPPLIER_KAM ->
				n.supplierCompanyId != null && me.companyId() == n.supplierCompanyId;
			case CUSTOMER_QUALITY, CUSTOMER_NPD, CUSTOMER_OFP, CUSTOMER_KAM, CUSTOMER_MANAGEMENT ->
				n.customerVisible && n.customerCompanyId != null && me.companyId() == n.customerCompanyId;
			default -> false; // ERGO_RFQ, CUSTOMER_RFQ, SUPPLIER_RFQ: SRS §10.8 X
		};
		if (!inScope) {
			throw ApiException.notFound("NCR");
		}
		return n;
	}

	private boolean ergoEditor(CurrentUser me) {
		return EDITORS.contains(me.role());
	}

	/** Security review L4: scoped to NCRs linked to the supplier's own company (SRS §10.8 "NCRs ... linked to that
	 * supplier"), not to NCRs the supplier's own company happened to create - a supplier must be able to respond to
	 * an NCR ERGO raised against it (the common real-world case: ERGO finds a defect, raises the NCR, the supplier
	 * fills in root cause/CAPA), not only one it filed itself. Company reassignment/{@code effectivenessStatus}/
	 * {@code ownerUserId} stay ERGO-only regardless (applyUpdate's own {@code ergoEditor} gate). */
	private boolean editor(CurrentUser me, Ncr n) {
		boolean ownSupplier = me.role() == SUPPLIER_EDITOR && n.supplierCompanyId != null
				&& me.companyId() == n.supplierCompanyId;
		return ergoEditor(me) || ownSupplier;
	}

	/** The rule book: SRS §10.8 rights combined with the §14.7 status. */
	private boolean allowed(NcrAction action, CurrentUser me, Ncr n) {
		boolean editor = editor(me, n);
		boolean approver = APPROVERS.contains(me.role());
		// Security review H3: the NCR's assigned owner (if any), its original creator, and whoever actually
		// requested closure are all blocked from later closing or sending it back - ownerUserId alone was trivially
		// bypassable (null by default, reassignable mid-flow); createdByUserId/closureRequestedByUserId close that.
		boolean selfApproval = (n.ownerUserId != null && me.userId() == n.ownerUserId) || me.userId() == n.createdByUserId
				|| (n.closureRequestedByUserId != null && me.userId() == n.closureRequestedByUserId);
		NcrStatus s = n.currentStatus;
		return switch (action) {
			// Security review M1: frozen during PENDING_CLOSURE_APPROVAL too - content must not change while an
			// approver is mid-review, after it has already been submitted for closure.
			case EDIT -> editor && s != NcrStatus.CLOSED && s != NcrStatus.PENDING_CLOSURE_APPROVAL;
			case MOVE_TO_CONTAINMENT -> editor && s == NcrStatus.OPEN;
			case MOVE_TO_ROOT_CAUSE -> editor && s == NcrStatus.CONTAINMENT_IN_PROGRESS;
			case MOVE_TO_CAPA -> editor && s == NcrStatus.ROOT_CAUSE_IN_PROGRESS;
			case SUBMIT_FOR_CLOSURE -> editor && s == NcrStatus.CAPA_IN_PROGRESS;
			case CLOSE -> approver && !selfApproval && s == NcrStatus.PENDING_CLOSURE_APPROVAL;
			case SEND_BACK -> approver && !selfApproval && s == NcrStatus.PENDING_CLOSURE_APPROVAL;
			case REOPEN -> approver && s == NcrStatus.CLOSED;
		};
	}

	private Set<NcrAction> actions(CurrentUser me, Ncr n) {
		return Arrays.stream(NcrAction.values()).filter(a -> allowed(a, me, n))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(NcrAction.class)));
	}

	private void require(NcrAction action, CurrentUser me, Ncr n) {
		if (!allowed(action, me, n)) {
			throw ApiException.conflict("This action is not available for this NCR right now");
		}
	}

	private Long requireCompanyIfGiven(Long companyId, CompanyType type, String label) {
		if (companyId == null) {
			return null;
		}
		return directory.company(companyId).filter(c -> c.type() == type && c.active())
			.orElseThrow(() -> ApiException.unprocessable(label + " not found or not active"))
			.id();
	}

	/** Security review M3: an NCR owner (ERGO-only field) must resolve to a real ERGO user, not an arbitrary integer
	 * - same existence-plus-company-match check as {@code OrderService.resolveOwner}'s established precedent for an
	 * ERGO-only owner field (Directory.UserRef carries no "active" flag to check against, so neither does this). */
	private Long resolveNcrOwner(CurrentUser me, Long ownerUserId) {
		if (ownerUserId == null) {
			return null;
		}
		UserRef owner = directory.users(Set.of(ownerUserId)).get(ownerUserId);
		if (owner == null || owner.companyId() != me.companyId()) {
			throw ApiException.unprocessable("The NCR owner must be an ERGO user");
		}
		return ownerUserId;
	}

	/** Security review M3: a CAPA action owner must resolve to a real user of ERGO or this NCR's own supplier
	 * company - never an arbitrary integer (which would otherwise 500 on the FK constraint and could misattribute a
	 * CAPA action to whoever that id happens to belong to). When a supplier editor is the one setting it, it must
	 * additionally be a user of the caller's own company. */
	private Long resolveCapaOwner(CurrentUser me, Ncr n, Long ownerUserId) {
		if (ownerUserId == null) {
			return null;
		}
		UserRef owner = directory.users(Set.of(ownerUserId)).get(ownerUserId);
		if (owner == null) {
			throw ApiException.unprocessable("CAPA action owner not found");
		}
		boolean ownerIsErgo = directory.company(owner.companyId()).map(c -> c.type() == CompanyType.ERGO).orElse(false);
		boolean ownerIsNcrSupplier = n.supplierCompanyId != null && owner.companyId() == n.supplierCompanyId;
		if (!ownerIsErgo && !ownerIsNcrSupplier) {
			throw ApiException.unprocessable("CAPA action owner must belong to ERGO or this NCR's supplier company");
		}
		if (me.role() == SUPPLIER_EDITOR && owner.companyId() != me.companyId()) {
			throw ApiException.unprocessable("CAPA action owner must belong to your own company");
		}
		return ownerUserId;
	}

	// ---------------------------------------------------------------- view assembly

	/** The single per-audience view builder, same shape as {@code InspectionService.view}: a supplier caller gets
	 * {@link SupplierNcrView}, a customer caller gets {@link CustomerNcrView}, everyone else reaching here is ERGO
	 * ({@link #visible} already 404s every other role). */
	private NcrDetail view(CurrentUser me, Ncr n) {
		List<CapaAction> rows = capaActions.findByNcrIdOrderById(n.id);

		if (SUPPLIER_VIEWERS.contains(me.role())) {
			List<SupplierCapaActionView> capaViews = rows.stream()
				.map(a -> new SupplierCapaActionView(a.id, a.actionType, a.actionDescription, a.targetDate, a.status,
						a.evidenceDocumentId, evidenceFileName(a), a.closedAt, a.createdAt))
				.toList();
			return new SupplierNcrView(n.id, n.ncrNumber, n.linkedModule, n.linkedRecordId, n.issueCategory,
					n.issueDescription, n.containmentAction, n.rootCause, n.correctiveAction, n.preventiveAction,
					n.targetClosureDate, n.closureDate, n.currentStatus, capaViews, actions(me, n), n.createdAt,
					n.updatedAt);
		}

		if (CUSTOMER_VIEWERS.contains(me.role())) {
			// Security review H1: no evidenceDocumentId/evidenceFileName - a customer must never learn the other
			// party's evidence file name or be able to download it, only whether evidence exists.
			List<CustomerCapaActionView> capaViews = rows.stream()
				.map(a -> new CustomerCapaActionView(a.id, a.actionType, a.actionDescription, a.targetDate, a.status,
						a.evidenceDocumentId != null, a.closedAt, a.createdAt))
				.toList();
			// Customer Quality (and the rest of CUSTOMER_VIEWERS) is view-only (same judgment call as Inspection's):
			// actions() naturally comes back empty since none of the switch arms in allowed() grant a customer role
			// anything, kept explicit here only for readability, not as a second gate.
			return new CustomerNcrView(n.id, n.ncrNumber, n.linkedModule, n.linkedRecordId, n.issueCategory,
					n.issueDescription, n.containmentAction, n.rootCause, n.correctiveAction, n.preventiveAction,
					n.targetClosureDate, n.closureDate, n.currentStatus, capaViews, actions(me, n), n.createdAt,
					n.updatedAt);
		}

		// Explicit guard (matches OFP/Inspection's own M3-class fix): this branch must only ever be reached for ERGO
		// roles - visible() already 404s every other role, but a future widening should not silently fall through.
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.forbidden("Access denied");
		}
		Map<Long, UserRef> capaOwners = directory.users(ids(rows, r -> r.ownerUserId));
		List<CapaActionView> capaViews = rows.stream()
			.map(a -> new CapaActionView(a.id, a.actionType, a.actionDescription, a.ownerUserId,
					userName(capaOwners, a.ownerUserId), a.targetDate, a.status, a.evidenceDocumentId,
					evidenceFileName(a), a.closedAt, a.createdAt))
			.toList();
		String customerName = n.customerCompanyId == null ? null
				: name(directory.companies(Set.of(n.customerCompanyId)), n.customerCompanyId);
		String supplierName = n.supplierCompanyId == null ? null
				: name(directory.companies(Set.of(n.supplierCompanyId)), n.supplierCompanyId);
		String ownerName = n.ownerUserId == null ? null : userName(directory.users(Set.of(n.ownerUserId)), n.ownerUserId);
		return new NcrView(n.id, n.ncrNumber, n.linkedModule, n.linkedRecordId, n.customerCompanyId, customerName,
				n.supplierCompanyId, supplierName, n.issueCategory, n.issueDescription, n.containmentAction,
				n.rootCause, n.correctiveAction, n.preventiveAction, n.ownerUserId, ownerName, n.targetClosureDate,
				n.closureDate, n.effectivenessStatus, n.currentStatus, n.customerVisible, capaViews, actions(me, n), n.createdAt,
				n.updatedAt);
	}

	private String evidenceFileName(CapaAction a) {
		return a.evidenceDocumentId == null ? null
				: documents.find(CAPA_MODULE, a.id, a.evidenceDocumentId).map(DocumentInfo::fileName).orElse(null);
	}

	// ---------------------------------------------------------------- small helpers

	@SafeVarargs
	private static <T> Set<Long> ids(Collection<T> items, Function<T, Long>... fields) {
		return items.stream().flatMap(i -> Arrays.stream(fields).map(f -> f.apply(i))).filter(Objects::nonNull)
			.collect(Collectors.toSet());
	}

	private static String name(Map<Long, CompanyRef> names, Long id) {
		CompanyRef c = id == null ? null : names.get(id);
		return c == null ? null : c.name();
	}

	private static String userName(Map<Long, UserRef> users, Long id) {
		UserRef user = id == null ? null : users.get(id);
		return user == null ? null : user.name();
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
