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

import java.math.BigDecimal;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;
import com.ergo.connect.npd.api.NpdApi;
import com.ergo.connect.ofp.api.OfpApi;
import com.ergo.connect.quality.domain.DeviationDtos.CustomerDeviationRow;
import com.ergo.connect.quality.domain.DeviationDtos.CustomerDeviationView;
import com.ergo.connect.quality.domain.DeviationDtos.DeviationAction;
import com.ergo.connect.quality.domain.DeviationDtos.DeviationDetail;
import com.ergo.connect.quality.domain.DeviationDtos.DeviationForm;
import com.ergo.connect.quality.domain.DeviationDtos.DeviationRow;
import com.ergo.connect.quality.domain.DeviationDtos.DeviationSummary;
import com.ergo.connect.quality.domain.DeviationDtos.DeviationUpdateForm;
import com.ergo.connect.quality.domain.DeviationDtos.DeviationView;
import com.ergo.connect.quality.domain.DeviationDtos.ErgoDeviationApprovalView;
import com.ergo.connect.quality.domain.DeviationDtos.ExternalDeviationApprovalView;
import com.ergo.connect.quality.domain.DeviationDtos.SupplierDeviationRow;
import com.ergo.connect.quality.domain.DeviationDtos.SupplierDeviationView;
import com.ergo.connect.quality.domain.QualityDtos.ReasonForm;

import tools.jackson.databind.json.JsonMapper;

/**
 * Deviations (SRS §10.8, §13.6.4, §14.7), Phase 5c - a deviation request raised against an NPD sample or OFP order,
 * with dual approval (ERGO Quality always, Customer Quality only when {@code customerApprovalRequiredFlag} is set)
 * and an automatic expiry once {@code validityEndDate} passes. Same shape as {@link NcrService}: one service, one
 * rule book ({@link #allowed}), no public {@code setStatus}, record scope resolved the same way.
 * <p>
 * Record scope (SRS §8.2): ERGO Admin/Management/Quality unrestricted; ERGO KAM its assigned customer accounts; ERGO
 * NPD/OFP its own linked module; every supplier role its own company gated by {@code supplierVisible}; every customer
 * role its own company gated by {@code customerVisible} - unlike {@code NcrService} (unfiltered supplier access),
 * Deviations gate both directions symmetrically (security review H3 fix: a deviation a customer raises itself must
 * not reach the supplier before ERGO reviews it, the same reason the supplier side was already gated) - generalized
 * to include Customer Quality as a third create/edit/submit/approve role alongside ERGO Quality/Admin and Supplier
 * Quality (SRS §10.8's "A*" footnote: Customer Quality's own approve right is narrowed to deciding only its own
 * {@code CUSTOMER_QUALITY} row, never ERGO's).
 * <p>
 * <b>Judgment calls (flagged per the build brief, not guessed silently):</b>
 * <ul>
 * <li><b>{@code customerVisible} at creation:</b> unlike {@code Ncr}/{@code Inspection} (always {@code false} at
 * creation, an ERGO editor must review and flip it on), a deviation Customer Quality creates itself starts {@code
 * true} - the gate exists to stop supplier-authored content reaching a customer unreviewed, which does not apply
 * to a customer's own record of its own company. An ERGO- or supplier-created deviation still starts {@code false}.
 * Whenever the deviation enters {@code CUSTOMER_APPROVAL_PENDING} it is forced {@code true} server-side regardless -
 * the customer being asked to approve something must always be able to see it. Security review H3 fix adds the
 * mirror image for the supplier: {@code supplierVisible} starts {@code false} only for a customer's own creation
 * (stopping that customer's free text reaching the supplier before ERGO reviews it) and {@code true} for every other
 * creator - an ERGO- or supplier-created deviation is visible to its own supplier immediately, unchanged from
 * before, since nothing in that path exposes unreviewed content across the boundary.
 * <li><b>{@code customerApprovalRequiredFlag} is locked after creation</b> ({@link Deviation}'s own javadoc): toggling
 * it later would mean retroactively creating/removing the {@code CUSTOMER_QUALITY} approval row underneath a review
 * already in progress, which the brief does not describe a rule for.
 * <li><b>{@code requestedByUserId} is always the creator</b>, never a separate "requested on behalf of" field - the
 * brief names no form field for the latter and {@code createdByUserId} would otherwise duplicate it exactly.
 * <li><b>No reopen action:</b> the brief's own workflow description names REQUESTED/UNDER_REVIEW/
 * CUSTOMER_APPROVAL_PENDING/APPROVED/REJECTED/EXPIRED/CLOSED and exactly the transitions between them (including
 * SEND_BACK) but, unlike {@code NcrService}'s CLOSED -&gt; OPEN reopen, never describes a path out of CLOSED or
 * REJECTED - so none is implemented here rather than guessed.
 * <li><b>No version snapshot on CLOSE or automatic EXPIRE:</b> the brief limits {@code deviation_versions} writes to
 * "every approval decision ... and on initial submission" - EXPIRE in particular runs with no authenticated actor
 * (a {@code @Scheduled} job), and {@code deviation_versions.recorded_by_user_id} is {@code NOT NULL}, so it could not
 * write one even if the brief had asked for it.
 * </ul>
 */
@Service
@Transactional
public class DeviationService {

	/** SRS §10.8 Create/Edit/Submit: ERGO Admin/Quality, Customer Quality and Supplier Quality. */
	private static final Set<Role> EDITORS = EnumSet.of(ERGO_ADMIN, ERGO_QUALITY, CUSTOMER_QUALITY, SUPPLIER_QUALITY);

	/** REQUESTED -&gt; UNDER_REVIEW ("ERGO Quality starts review", brief item 3) - narrower than {@link #EDITORS}. */
	private static final Set<Role> REVIEW_STARTERS = EnumSet.of(ERGO_ADMIN, ERGO_QUALITY);

	/** APPROVED/EXPIRED -&gt; CLOSED (brief item 5): the same two roles as {@link #REVIEW_STARTERS}. */
	private static final Set<Role> CLOSERS = EnumSet.of(ERGO_ADMIN, ERGO_QUALITY);

	private static final Set<Role> SUPPLIER_VIEWERS = EnumSet.of(SUPPLIER_QUALITY, SUPPLIER_NPD, SUPPLIER_OFP,
			SUPPLIER_KAM);

	private static final Set<Role> CUSTOMER_VIEWERS = EnumSet.of(CUSTOMER_QUALITY, CUSTOMER_NPD, CUSTOMER_OFP,
			CUSTOMER_KAM, CUSTOMER_MANAGEMENT);

	private final DeviationRepository deviations;

	private final DeviationApprovalRepository approvals;

	private final InspectionRepository inspections;

	private final Directory directory;

	private final AuditLogService audit;

	private final NpdApi npdApi;

	private final OfpApi ofpApi;

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	DeviationService(DeviationRepository deviations, DeviationApprovalRepository approvals,
			InspectionRepository inspections, Directory directory, AuditLogService audit, NpdApi npdApi, OfpApi ofpApi,
			JdbcTemplate jdbc, JsonMapper json) {
		this.deviations = deviations;
		this.approvals = approvals;
		this.inspections = inspections;
		this.directory = directory;
		this.audit = audit;
		this.npdApi = npdApi;
		this.ofpApi = ofpApi;
		this.jdbc = jdbc;
		this.json = json;
	}

	// ---------------------------------------------------------------- reads

	@Transactional(readOnly = true)
	public List<? extends DeviationSummary> list() {
		CurrentUser me = CurrentUser.get();
		if (SUPPLIER_VIEWERS.contains(me.role())) {
			return deviations.findBySupplierCompanyIdAndSupplierVisibleTrueOrderByIdDesc(me.companyId()).stream()
				.map(d -> new SupplierDeviationRow(d.id, d.deviationNumber, d.linkedModule, d.linkedRecordId,
						d.partNumber, d.currentStatus, d.customerApprovalRequiredFlag, d.validityEndDate))
				.toList();
		}
		if (CUSTOMER_VIEWERS.contains(me.role())) {
			return deviations.findByCustomerCompanyIdAndCustomerVisibleTrueOrderByIdDesc(me.companyId()).stream()
				.map(d -> new CustomerDeviationRow(d.id, d.deviationNumber, d.linkedModule, d.linkedRecordId,
						d.partNumber, d.currentStatus, d.customerApprovalRequiredFlag, d.validityEndDate))
				.toList();
		}
		List<Deviation> visible = switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_QUALITY -> deviations.findAllByOrderByIdDesc();
			case ERGO_KAM -> {
				Set<Long> accounts = directory.accountsOf(me.userId());
				yield accounts.isEmpty() ? List.of() : deviations.findByCustomerCompanyIdInOrderByIdDesc(accounts);
			}
			case ERGO_NPD -> deviations.findByLinkedModuleOrderByIdDesc(LinkedModule.NPD);
			case ERGO_OFP -> deviations.findByLinkedModuleOrderByIdDesc(LinkedModule.OFP);
			default -> List.of(); // SRS §10.8 X: ERGO RFQ and every other role not handled above/below
		};
		Map<Long, CompanyRef> customers = directory.companies(ids(visible, d -> d.customerCompanyId));
		Map<Long, CompanyRef> suppliers = directory.companies(ids(visible, d -> d.supplierCompanyId));
		return visible.stream()
			.map(d -> new DeviationRow(d.id, d.deviationNumber, d.linkedModule, d.linkedRecordId,
					name(customers, d.customerCompanyId), name(suppliers, d.supplierCompanyId), d.partNumber,
					d.currentStatus, d.customerApprovalRequiredFlag, d.validityEndDate))
			.toList();
	}

	@Transactional(readOnly = true)
	public DeviationDetail get(long id) {
		CurrentUser me = CurrentUser.get();
		return view(me, visible(me, id));
	}

	// ---------------------------------------------------------------- create / edit

	public DeviationDetail create(DeviationForm form) {
		CurrentUser me = CurrentUser.get();
		if (!EDITORS.contains(me.role())) {
			throw ApiException.forbidden("Only ERGO Quality/Admin, Customer Quality or Supplier Quality may raise a deviation");
		}
		if (form.linkedModule() != LinkedModule.NPD && form.linkedModule() != LinkedModule.OFP) {
			throw ApiException.unprocessable("A deviation can only be linked to an NPD project or an OFP order");
		}
		Deviation d = new Deviation();
		d.linkedModule = form.linkedModule();
		d.linkedRecordId = form.linkedRecordId();
		applyLinkedCompanies(d, me, form.linkedModule(), form.linkedRecordId());
		d.inspectionId = resolveInspectionId(form.inspectionId(), form.linkedModule(), form.linkedRecordId());
		d.partNumber = form.partNumber().trim();
		d.deviationDescription = form.deviationDescription().trim();
		d.qualityImpact = text(form.qualityImpact());
		d.technicalImpact = text(form.technicalImpact());
		d.customerApprovalRequiredFlag = form.customerApprovalRequiredFlag();
		d.validityQuantity = form.validityQuantity();
		d.validityEndDate = form.validityEndDate();
		d.requestedByUserId = me.userId();
		// Judgment call (class comment): true only for a customer's own creation of its own record.
		d.customerVisible = me.role() == CUSTOMER_QUALITY;
		// Security review H3 fix (class comment): false only for a customer's own creation, true otherwise.
		d.supplierVisible = me.role() != CUSTOMER_QUALITY;
		d.currentStatus = DeviationStatus.REQUESTED;
		d.createdByUserId = me.userId();
		d.createdByCompanyId = me.companyId();
		d.deviationNumber = nextDeviationNumber();
		deviations.save(d);
		createApprovalRow(d.id, Role.ERGO_QUALITY);
		if (d.customerApprovalRequiredFlag) {
			createApprovalRow(d.id, Role.CUSTOMER_QUALITY);
		}
		writeVersionSnapshot(d);
		return view(me, d);
	}

	private String nextDeviationNumber() {
		return "DEV-%d-%05d".formatted(Year.now().getValue(),
				jdbc.queryForObject("SELECT nextval('deviation_number_seq')", Long.class));
	}

	/** Security review M5 precedent ({@code NcrService.applyLinkedCompanies}), generalized to also verify a customer
	 * creator (not only a supplier one, since Customer Quality may create here but never could for an NCR): both
	 * companies always come from the linked NPD/OFP record itself, never the client, and a non-ERGO creator must own
	 * the side of that record matching its own company type. "Not found" and "belongs to someone else" share one
	 * generic message (security review L5 precedent) so a caller cannot enumerate valid ids by probing the response. */
	private void applyLinkedCompanies(Deviation d, CurrentUser me, LinkedModule module, long linkedRecordId) {
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
		if (me.companyType() == CompanyType.SUPPLIER && me.companyId() != supplierCompanyId
				|| me.companyType() == CompanyType.CUSTOMER && me.companyId() != customerCompanyId) {
			throw ApiException.unprocessable("Linked record not found or not accessible");
		}
		d.customerCompanyId = customerCompanyId;
		d.supplierCompanyId = supplierCompanyId;
	}

	/** Security review M1 fix: an {@code inspectionId} must belong to the very same linked NPD/OFP record this
	 * deviation is itself being raised against, not merely exist - otherwise a caller could link to (and, via the
	 * differing error, probe the existence of) another tenant's inspection. "Not found" and "belongs to a different
	 * linked record" share one generic message (same L5 precedent as {@link #applyLinkedCompanies}) so a caller
	 * cannot enumerate valid inspection ids by probing the response. */
	private Long resolveInspectionId(Long inspectionId, LinkedModule linkedModule, long linkedRecordId) {
		if (inspectionId == null) {
			return null;
		}
		Inspection inspection = inspections.findById(inspectionId).orElse(null);
		if (inspection == null || inspection.linkedModule != linkedModule
				|| !Objects.equals(inspection.linkedRecordId, linkedRecordId)) {
			throw ApiException.unprocessable("Linked inspection not found");
		}
		return inspectionId;
	}

	public DeviationDetail edit(long id, DeviationUpdateForm form) {
		CurrentUser me = CurrentUser.get();
		Deviation d = visible(me, id);
		require(DeviationAction.EDIT, me, d);
		d.partNumber = form.partNumber().trim();
		d.deviationDescription = form.deviationDescription().trim();
		d.qualityImpact = text(form.qualityImpact());
		d.technicalImpact = text(form.technicalImpact());
		d.validityQuantity = form.validityQuantity();
		d.validityEndDate = form.validityEndDate();
		if (ergoEditor(me)) {
			// Security review H2 precedent (NcrUpdateForm.customerVisible): ERGO-only, full-replace - a non-ERGO
			// editor's own value is ignored. Security review H3 fix: supplierVisible follows the same rule.
			d.customerVisible = form.customerVisible();
			d.supplierVisible = form.supplierVisible();
		}
		return view(me, d);
	}

	// ---------------------------------------------------------------- workflow (SRS §14.7)

	/** REQUESTED -&gt; UNDER_REVIEW. (Re-)arms the ERGO_QUALITY row to PENDING - a no-op the first time, the actual
	 * reset after an ERGO-side SEND_BACK routed the deviation back here (class comment: the row stays SENT_BACK,
	 * historical, until review is started again). */
	public DeviationDetail startReview(long id) {
		CurrentUser me = CurrentUser.get();
		Deviation d = visible(me, id);
		require(DeviationAction.START_REVIEW, me, d);
		d.currentStatus = DeviationStatus.UNDER_REVIEW;
		resetRow(d.id, Role.ERGO_QUALITY);
		return view(me, d);
	}

	public DeviationDetail approve(long id, ReasonForm form) {
		return decide(id, DeviationAction.APPROVE, form);
	}

	public DeviationDetail reject(long id, ReasonForm form) {
		return decide(id, DeviationAction.REJECT, form);
	}

	public DeviationDetail sendBack(long id, ReasonForm form) {
		return decide(id, DeviationAction.SEND_BACK, form);
	}

	/** The single decide entry point for APPROVE/REJECT/SEND_BACK: resolves which row the caller's own role may
	 * decide ({@link #myApprovalRole}), so an ERGO approver can only ever decide the {@code ERGO_QUALITY} row and a
	 * Customer Quality approver only ever the {@code CUSTOMER_QUALITY} row (brief item 4) - enforced by {@link
	 * #canDecide} requiring the deviation to be in exactly the status that row's role decides. Reason required for
	 * REJECT/SEND_BACK (CLAUDE.md non-negotiable), optional for APPROVE (an audit comment, same shape as {@code
	 * NcrService.close}). */
	private DeviationDetail decide(long id, DeviationAction action, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Deviation d = visible(me, id);
		Map<Role, DeviationApproval> rows = rowsByRole(d.id);
		require(action, me, d, rows);
		DeviationApproval row = rows.get(myApprovalRole(me.role()));
		String comment = switch (action) {
			case REJECT -> {
				String reason = required(form.reason(), "A reason is required to reject a deviation");
				audit.reason(reason);
				yield reason;
			}
			case SEND_BACK -> {
				String reason = required(form.reason(), "A reason is required to send a deviation back");
				audit.reason(reason);
				yield reason;
			}
			case APPROVE -> {
				// Security review L1: a deviation whose validity has already lapsed cannot be approved, even if it is
				// still mid-review when the date passes (the scheduled job in expireOverdueDeviations only reaches
				// deviations already APPROVED).
				if (d.validityEndDate != null && d.validityEndDate.isBefore(LocalDate.now())) {
					throw ApiException.unprocessable("This deviation's validity period has already lapsed");
				}
				reasonIfGiven(form.reason());
				yield text(form.reason());
			}
			default -> throw new IllegalStateException(action.name());
		};
		row.decision = switch (action) {
			case APPROVE -> DeviationDecision.APPROVED;
			case REJECT -> DeviationDecision.REJECTED;
			case SEND_BACK -> DeviationDecision.SENT_BACK;
			default -> throw new IllegalStateException(action.name());
		};
		row.approverUserId = me.userId();
		row.decidedAt = Instant.now();
		row.comment = comment;
		recomputeStatus(d, row);
		writeVersionSnapshot(d);
		return view(me, d);
	}

	/** Recomputes {@code currentStatus} from the row just decided (brief item 3): REJECTED is terminal regardless of
	 * which row rejects; SENT_BACK on the ERGO row goes back to REQUESTED (its own row stays SENT_BACK, historical,
	 * until {@link #startReview} re-arms it), SENT_BACK on the customer row goes back to UNDER_REVIEW and re-arms the
	 * ERGO row immediately (ERGO must re-review before the customer gets another chance); APPROVED on the ERGO row
	 * either finishes the deviation (no customer approval required) or opens CUSTOMER_APPROVAL_PENDING, forcing
	 * {@code customerVisible} true and (re-)arming the customer row; APPROVED on the customer row finishes it (the
	 * ERGO row is already APPROVED by construction - CUSTOMER_APPROVAL_PENDING is only reachable that way). */
	private void recomputeStatus(Deviation d, DeviationApproval decided) {
		if (decided.decision == DeviationDecision.REJECTED) {
			d.currentStatus = DeviationStatus.REJECTED;
			return;
		}
		if (decided.decision == DeviationDecision.SENT_BACK) {
			if (decided.approverRole == Role.ERGO_QUALITY) {
				d.currentStatus = DeviationStatus.REQUESTED;
			}
			else {
				d.currentStatus = DeviationStatus.UNDER_REVIEW;
				resetRow(d.id, Role.ERGO_QUALITY);
			}
			return;
		}
		// APPROVED
		if (decided.approverRole == Role.ERGO_QUALITY) {
			if (d.customerApprovalRequiredFlag) {
				d.currentStatus = DeviationStatus.CUSTOMER_APPROVAL_PENDING;
				d.customerVisible = true;
				resetRow(d.id, Role.CUSTOMER_QUALITY);
			}
			else {
				d.currentStatus = DeviationStatus.APPROVED;
			}
		}
		else {
			d.currentStatus = DeviationStatus.APPROVED;
		}
	}

	/** APPROVED/EXPIRED -&gt; CLOSED (brief item 5), manual. No reason required (not in CLAUDE.md's reopen/reject/
	 * send-back list); {@code form.reason()} is still recorded as an audit comment when given, same shape as {@code
	 * NcrService.close}. */
	public DeviationDetail close(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Deviation d = visible(me, id);
		require(DeviationAction.CLOSE, me, d);
		reasonIfGiven(form.reason());
		d.currentStatus = DeviationStatus.CLOSED;
		return view(me, d);
	}

	/** SRS §14.7 automatic transition: once a day, every still-APPROVED deviation whose validity has passed expires.
	 * No authenticated actor (ARCHITECTURE.md's {@code AuditLogService} already degrades to a null actor / SYSTEM
	 * channel outside a request); no version snapshot (class comment). */
	@Scheduled(cron = "${ergo.quality.deviation-expiry-cron:0 0 2 * * *}")
	public void expireOverdueDeviations() {
		LocalDate today = LocalDate.now();
		for (Deviation d : deviations.findByCurrentStatusAndValidityEndDateBefore(DeviationStatus.APPROVED, today)) {
			d.currentStatus = DeviationStatus.EXPIRED;
		}
	}

	// ---------------------------------------------------------------- rules

	/** Record scope. Out-of-scope deviations do not exist for the caller (404, not 403). */
	Deviation visible(CurrentUser me, long id) {
		Deviation d = deviations.findById(id).orElse(null);
		boolean inScope = d != null && switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_QUALITY -> true;
			case ERGO_KAM -> d.customerCompanyId != null && directory.accountsOf(me.userId()).contains(d.customerCompanyId);
			case ERGO_NPD -> d.linkedModule == LinkedModule.NPD;
			case ERGO_OFP -> d.linkedModule == LinkedModule.OFP;
			// Security review H3 fix: supplierVisible gate, mirroring the customer branch below.
			case SUPPLIER_QUALITY, SUPPLIER_NPD, SUPPLIER_OFP, SUPPLIER_KAM ->
				d.supplierVisible && d.supplierCompanyId != null && me.companyId() == d.supplierCompanyId;
			case CUSTOMER_QUALITY, CUSTOMER_NPD, CUSTOMER_OFP, CUSTOMER_KAM, CUSTOMER_MANAGEMENT ->
				d.customerVisible && d.customerCompanyId != null && me.companyId() == d.customerCompanyId;
			default -> false; // ERGO_RFQ, CUSTOMER_RFQ, SUPPLIER_RFQ: SRS §10.8 X
		};
		if (!inScope) {
			throw ApiException.notFound("Deviation");
		}
		return d;
	}

	private boolean ergoEditor(CurrentUser me) {
		return me.role() == ERGO_ADMIN || me.role() == ERGO_QUALITY;
	}

	/** Which {@link DeviationApproval#approverRole} row this caller's role may decide (brief item 4), or {@code null}
	 * if their role never decides any row - every ERGO role that can approve decides the {@code ERGO_QUALITY} row
	 * (including ERGO Management, who has no create/edit/submit right at all but does have this one); Customer
	 * Quality decides only the {@code CUSTOMER_QUALITY} row; Supplier Quality decides neither. */
	private static Role myApprovalRole(Role role) {
		return switch (role) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_QUALITY -> Role.ERGO_QUALITY;
			case CUSTOMER_QUALITY -> Role.CUSTOMER_QUALITY;
			default -> null;
		};
	}

	private Map<Role, DeviationApproval> rowsByRole(long deviationId) {
		return approvals.findByDeviationIdOrderById(deviationId).stream()
			.collect(Collectors.toMap(a -> a.approverRole, Function.identity()));
	}

	/** Security review H2 fix: whoever created the deviation (== {@code requestedByUserId} by construction, class
	 * comment) may never decide any row of their own deviation - ERGO Quality/Admin/Management deciding the
	 * {@code ERGO_QUALITY} row of a deviation they themselves raised, or Customer Quality deciding the
	 * {@code CUSTOMER_QUALITY} row of a deviation it created itself, are both self-approval and blocked here, the
	 * same {@code NcrService.allowed}'s own H3 self-approval guard. */
	private boolean canDecide(CurrentUser me, Deviation d, Map<Role, DeviationApproval> rows) {
		Role approvalRole = myApprovalRole(me.role());
		if (approvalRole == null || me.userId() == d.createdByUserId) {
			return false;
		}
		DeviationStatus required = approvalRole == Role.ERGO_QUALITY ? DeviationStatus.UNDER_REVIEW
				: DeviationStatus.CUSTOMER_APPROVAL_PENDING;
		if (d.currentStatus != required) {
			return false;
		}
		DeviationApproval row = rows.get(approvalRole);
		return row != null && row.decision == DeviationDecision.PENDING;
	}

	/** The rule book: SRS §10.8 rights combined with the §14.7 status, same shape as {@code NcrService.allowed}.
	 * <p>
	 * Security review H1 fix: EDIT is now REQUESTED-only, and frozen entirely (including for ERGO) once the
	 * deviation moves to UNDER_REVIEW, the same "frozen during an approver's mid-review" precedent as {@code
	 * NcrService.allowed}'s own M1 fix (there: frozen during PENDING_CLOSURE_APPROVAL) - this also closes the
	 * narrower hole of an approve with no rowVersion check: since content cannot change at all once review starts,
	 * there is no window left for it to diverge from what the reviewer is deciding on. While REQUESTED, an ERGO
	 * editor (admin/quality) may still edit any deviation - ERGO is the reviewer/owner of the workflow regardless of
	 * who raised it - but a customer/supplier editor may edit only a deviation its own company created ({@code
	 * createdByCompanyId}), never the other party's. */
	private boolean allowed(DeviationAction action, CurrentUser me, Deviation d, Map<Role, DeviationApproval> rows) {
		DeviationStatus s = d.currentStatus;
		return switch (action) {
			case EDIT -> s == DeviationStatus.REQUESTED
					&& (ergoEditor(me) || (EDITORS.contains(me.role()) && me.companyId() == d.createdByCompanyId));
			case START_REVIEW -> REVIEW_STARTERS.contains(me.role()) && s == DeviationStatus.REQUESTED;
			case APPROVE, REJECT, SEND_BACK -> canDecide(me, d, rows);
			case CLOSE -> CLOSERS.contains(me.role()) && (s == DeviationStatus.APPROVED || s == DeviationStatus.EXPIRED);
		};
	}

	private Set<DeviationAction> actions(CurrentUser me, Deviation d, Map<Role, DeviationApproval> rows) {
		return Arrays.stream(DeviationAction.values()).filter(a -> allowed(a, me, d, rows))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(DeviationAction.class)));
	}

	private void require(DeviationAction action, CurrentUser me, Deviation d) {
		require(action, me, d, rowsByRole(d.id));
	}

	private void require(DeviationAction action, CurrentUser me, Deviation d, Map<Role, DeviationApproval> rows) {
		if (!allowed(action, me, d, rows)) {
			throw ApiException.conflict("This action is not available for this deviation right now");
		}
	}

	private void createApprovalRow(long deviationId, Role role) {
		DeviationApproval row = new DeviationApproval();
		row.deviationId = deviationId;
		row.approverRole = role;
		row.decision = DeviationDecision.PENDING;
		approvals.save(row);
	}

	/** Re-arms an existing row to PENDING for the next review cycle (class comment: at most one row per role ever
	 * exists, reused rather than recreated - unlike {@code NpdApproval}'s fresh-row-per-request). A missing row (the
	 * {@code CUSTOMER_QUALITY} row before {@code customerApprovalRequiredFlag} was ever set) is a no-op. */
	private void resetRow(long deviationId, Role role) {
		approvals.findByDeviationIdAndApproverRole(deviationId, role).ifPresent(row -> {
			row.decision = DeviationDecision.PENDING;
			row.approverUserId = null;
			row.comment = null;
			row.decidedAt = null;
		});
	}

	// ---------------------------------------------------------------- versioning (SRS §15.4)

	/** Immutable snapshot of the full deviation plus its approvals state (brief item 6), same pattern as {@code
	 * QuoteService.submit}'s own write to {@code supplier_quote_versions}: a raw JSONB insert via {@link JdbcTemplate},
	 * not a second JPA entity. ponytail: the next version number is read with a plain {@code SELECT MAX(...)+1}, not
	 * {@code SELECT ... FOR UPDATE} - fine at this module's volume (one reviewer deciding one deviation at a time);
	 * add a row lock if concurrent decisions on the same deviation ever race in practice. */
	private void writeVersionSnapshot(Deviation d) {
		CurrentUser me = CurrentUser.get();
		List<DeviationApproval> rows = approvals.findByDeviationIdOrderById(d.id);
		DeviationSnapshot snapshot = new DeviationSnapshot(d.id, d.deviationNumber, d.linkedModule, d.linkedRecordId,
				d.inspectionId, d.customerCompanyId, d.supplierCompanyId, d.partNumber, d.deviationDescription,
				d.requestedByUserId, d.qualityImpact, d.technicalImpact, d.customerApprovalRequiredFlag,
				d.validityQuantity, d.validityEndDate, d.currentStatus, d.customerVisible, d.supplierVisible,
				rows.stream()
					.map(r -> new ApprovalSnapshot(r.approverRole, r.decision, r.approverUserId, r.comment, r.decidedAt))
					.toList());
		int nextVersion = jdbc.queryForObject(
				"SELECT COALESCE(MAX(version_no), 0) + 1 FROM deviation_versions WHERE deviation_id = ?", Integer.class,
				d.id);
		jdbc.update(
				"INSERT INTO deviation_versions (deviation_id, version_no, snapshot, recorded_by_user_id) "
						+ "VALUES (?, ?, ?::jsonb, ?)",
				d.id, nextVersion, json.writeValueAsString(snapshot), me.userId());
	}

	private record DeviationSnapshot(long id, String deviationNumber, LinkedModule linkedModule, long linkedRecordId,
			Long inspectionId, Long customerCompanyId, Long supplierCompanyId, String partNumber,
			String deviationDescription, Long requestedByUserId, String qualityImpact, String technicalImpact,
			boolean customerApprovalRequiredFlag, BigDecimal validityQuantity, LocalDate validityEndDate,
			DeviationStatus currentStatus, boolean customerVisible, boolean supplierVisible,
			List<ApprovalSnapshot> approvals) {
	}

	private record ApprovalSnapshot(Role approverRole, DeviationDecision decision, Long approverUserId, String comment,
			Instant decidedAt) {
	}

	// ---------------------------------------------------------------- view assembly

	/** The single per-audience view builder, same shape as {@code NcrService.view}. */
	private DeviationDetail view(CurrentUser me, Deviation d) {
		List<DeviationApproval> rows = approvals.findByDeviationIdOrderById(d.id);
		Map<Role, DeviationApproval> rowsByRole = rows.stream()
			.collect(Collectors.toMap(a -> a.approverRole, Function.identity()));
		Set<DeviationAction> acts = actions(me, d, rowsByRole);

		if (SUPPLIER_VIEWERS.contains(me.role())) {
			// Security review H3 fix: the CUSTOMER_QUALITY row's comment is the customer's own free text (a
			// send-back/reject reason etc.) and must never reach the supplier - see ExternalDeviationApprovalView.
			List<ExternalDeviationApprovalView> views = externalApprovals(rows, Role.CUSTOMER_QUALITY);
			return new SupplierDeviationView(d.id, d.deviationNumber, d.linkedModule, d.linkedRecordId, d.partNumber,
					d.deviationDescription, d.qualityImpact, d.technicalImpact, d.customerApprovalRequiredFlag,
					d.validityQuantity, d.validityEndDate, d.currentStatus, views, acts, d.createdAt, d.updatedAt);
		}

		if (CUSTOMER_VIEWERS.contains(me.role())) {
			// Security review H3 fix: the ERGO_QUALITY row's comment is ERGO's own internal review note and must
			// never reach the customer - see ExternalDeviationApprovalView.
			List<ExternalDeviationApprovalView> views = externalApprovals(rows, Role.ERGO_QUALITY);
			return new CustomerDeviationView(d.id, d.deviationNumber, d.linkedModule, d.linkedRecordId, d.partNumber,
					d.deviationDescription, d.qualityImpact, d.technicalImpact, d.customerApprovalRequiredFlag,
					d.validityQuantity, d.validityEndDate, d.currentStatus, views, acts, d.createdAt, d.updatedAt);
		}

		// Explicit guard (matches NCR/Inspection's own precedent): this branch must only ever be reached for ERGO
		// roles - visible() already 404s every other role, but a future widening should not silently fall through.
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.forbidden("Access denied");
		}
		Map<Long, UserRef> users = directory.users(concatIds(d.requestedByUserId, rows));
		List<ErgoDeviationApprovalView> ergoViews = rows.stream()
			.map(r -> new ErgoDeviationApprovalView(r.approverRole, r.decision, r.approverUserId,
					userName(users, r.approverUserId), r.comment, r.decidedAt, r.createdAt))
			.toList();
		String customerName = d.customerCompanyId == null ? null
				: name(directory.companies(Set.of(d.customerCompanyId)), d.customerCompanyId);
		String supplierName = d.supplierCompanyId == null ? null
				: name(directory.companies(Set.of(d.supplierCompanyId)), d.supplierCompanyId);
		return new DeviationView(d.id, d.deviationNumber, d.linkedModule, d.linkedRecordId, d.inspectionId,
				d.customerCompanyId, customerName, d.supplierCompanyId, supplierName, d.partNumber,
				d.deviationDescription, d.requestedByUserId, userName(users, d.requestedByUserId), d.qualityImpact,
				d.technicalImpact, d.customerApprovalRequiredFlag, d.validityQuantity, d.validityEndDate,
				d.currentStatus, d.customerVisible, d.supplierVisible, ergoViews, acts, d.createdAt, d.updatedAt);
	}

	// ---------------------------------------------------------------- small helpers

	/** Security review H3 fix: builds the external, per-audience approval rows with the one row whose comment
	 * belongs to the other party's counterpart redacted to {@code null} - decision/status still show for every row
	 * (the audience already knows both rows exist whenever {@code customerApprovalRequiredFlag} is set), only the
	 * free-text comment is withheld. */
	private static List<ExternalDeviationApprovalView> externalApprovals(List<DeviationApproval> rows, Role hiddenCommentRole) {
		return rows.stream()
			.map(r -> new ExternalDeviationApprovalView(r.approverRole, r.decision,
					r.approverRole == hiddenCommentRole ? null : r.comment, r.decidedAt, r.createdAt))
			.toList();
	}

	private static Set<Long> concatIds(long requestedByUserId, Collection<DeviationApproval> rows) {
		Set<Long> ids = rows.stream().map(r -> r.approverUserId).filter(Objects::nonNull).collect(Collectors.toSet());
		ids.add(requestedByUserId);
		return ids;
	}

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
