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
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;
import com.ergo.connect.npd.api.NpdApi;
import com.ergo.connect.ofp.api.OfpApi;
import com.ergo.connect.quality.domain.QualityDtos.CustomerInspectionDefectView;
import com.ergo.connect.quality.domain.QualityDtos.CustomerInspectionRow;
import com.ergo.connect.quality.domain.QualityDtos.CustomerInspectionView;
import com.ergo.connect.quality.domain.QualityDtos.DefectForm;
import com.ergo.connect.quality.domain.QualityDtos.InspectionAction;
import com.ergo.connect.quality.domain.QualityDtos.InspectionDefectView;
import com.ergo.connect.quality.domain.QualityDtos.InspectionDetail;
import com.ergo.connect.quality.domain.QualityDtos.InspectionForm;
import com.ergo.connect.quality.domain.QualityDtos.InspectionRow;
import com.ergo.connect.quality.domain.QualityDtos.InspectionSummary;
import com.ergo.connect.quality.domain.QualityDtos.InspectionUpdateForm;
import com.ergo.connect.quality.domain.QualityDtos.InspectionView;
import com.ergo.connect.quality.domain.QualityDtos.ReasonForm;
import com.ergo.connect.quality.domain.QualityDtos.SupplierInspectionRow;
import com.ergo.connect.quality.domain.QualityDtos.SupplierInspectionView;

/**
 * Quality inspections (SRS §10.8, §13.6, §14.7). This is the module's one service, and its one workflow gate:
 * {@link #allowed} is the single rule book, enforcing every write and listing the caller's buttons - the same shape
 * {@code OrderService}/{@code NpdService} already use (ARCHITECTURE.md §8 2026-09-24: "the planned repository/
 * service/mapper sub-packages were dropped"; there is no separate {@code InspectionWorkflowService} for the same
 * reason neither of those modules has an {@code OrderWorkflowService}/{@code NpdWorkflowService} - the workflow
 * section below is this class's workflow service). No public {@code setStatus}: every status change is one of the
 * named methods below, each going through {@link #allowed}.
 * <p>
 * Record scope (SRS §8.2, this module's own reading - the SRS names ERGO KAM/Customer Quality/Supplier Quality
 * explicitly and is silent on the rest, resolved the same way OFP/NPD resolved their own silent cells): ERGO Admin/
 * Management/Quality see every inspection; ERGO KAM their assigned customer accounts' inspections (unfiltered by
 * {@code customerVisible} - that flag gates the *customer's own* view, not ERGO's); ERGO NPD/OFP the inspections
 * raised against their own linked module ({@link LinkedModule#NPD}/{@link LinkedModule#OFP} respectively - the same
 * shape as {@code OrderRepository.findByLinkedNpdProjectIdIsNotNullOrderByIdDesc}); every supplier role its own
 * company's inspections (direct {@code supplierCompanyId} match, no assignment table, same as OFP 4b); every
 * customer role its own company's inspections where {@code customerVisible} is set. ERGO RFQ and the three *_RFQ
 * roles get no access at all (SRS §10.8 "X"), enforced at the controller (excluded from {@code VIEWERS}).
 * <p>
 * <b>Judgment call (flagged per the build brief, not guessed silently):</b> the SRS §10.8 matrix's literal cells for
 * Customer Quality are C/E/S/A* (create/edit/submit/an asterisked approve). Built as view-only instead - no create,
 * edit, submit or status-transition right at all - because (1) the brief's own business context says C/E/S make no
 * sense for a role that never performs an inspection (ERGO/Supplier Quality do), and A* is explicitly scoped to a
 * "customer-facing acceptance" concept this phase does not build; (2) this matches the established precedent of
 * every earlier module (NPD 3a/3b, OFP 4a-4c) treating a matrix cell literally only when the surrounding SRS prose
 * and screen descriptions actually support it, and defaulting to the safer/narrower reading otherwise (e.g. NPD's
 * "ERGO Quality gets no NPD edit surface despite §10.6's unscoped E", ARCHITECTURE.md §8 2026-09-26). Two-way
 * anonymity (customer never learns the supplier and vice versa) is likewise extended into this module on the same
 * "same-shaped row as {@code orders}" reasoning OFP was built on (ARCHITECTURE.md §8 2026-09-28/29), not a literal
 * SRS statement for Quality specifically.
 */
@Service
@Transactional
public class InspectionService {

	/** The role sheet (ModuleAccess), Quality column. Create/Edit/Submit, ERGO side ("A" and up). */
	private static final Set<Role> EDITORS = ModuleAccess.actors(Module.QUALITY, CompanyType.ERGO);

	/** Create/Edit/Submit, supplier side ("A"). */
	private static final Set<Role> SUPPLIER_EDITORS = ModuleAccess.actors(Module.QUALITY, CompanyType.SUPPLIER);

	/** SRS §10.8 Approve, separate from edit (CLAUDE.md non-negotiable): notably includes ERGO Management, who has
	 * no create/edit/submit right at all - approval rights are their own authority, not folded into edit. Used for
	 * every result-determining and terminal transition (PASS/REJECT/RELEASE_WITH_DEVIATION/CLOSE/REOPEN): the SRS
	 * gives this module no separate "close"/"reopen" grant, so both are treated as the same approve-tier authority
	 * that decides PASSED/REJECTED/RELEASED_WITH_DEVIATION - the same tier that decides an inspection's outcome
	 * decides when that outcome is finalized (Close) or reversed (Reopen). Supplier Quality is deliberately absent
	 * ("X (no approve)"). */
	private static final Set<Role> APPROVERS = ModuleAccess.approvers(Module.QUALITY, CompanyType.ERGO);

	private static final Set<Role> SUPPLIER_VIEWERS = ModuleAccess.viewers(Module.QUALITY, CompanyType.SUPPLIER);

	private static final Set<Role> CUSTOMER_VIEWERS = ModuleAccess.viewers(Module.QUALITY, CompanyType.CUSTOMER);

	private final InspectionRepository inspections;

	private final InspectionDefectRepository defects;

	private final Directory directory;

	private final AuditLogService audit;

	private final NpdApi npdApi;

	private final OfpApi ofpApi;

	private final Notifier notifier;

	InspectionService(InspectionRepository inspections, InspectionDefectRepository defects, Directory directory,
			AuditLogService audit, NpdApi npdApi, OfpApi ofpApi, Notifier notifier) {
		this.notifier = notifier;
		this.inspections = inspections;
		this.defects = defects;
		this.directory = directory;
		this.audit = audit;
		this.npdApi = npdApi;
		this.ofpApi = ofpApi;
	}

	// ---------------------------------------------------------------- reads

	@Transactional(readOnly = true)
	public List<? extends InspectionSummary> list() {
		CurrentUser me = CurrentUser.get();
		if (SUPPLIER_VIEWERS.contains(me.role())) {
			return inspections.findBySupplierCompanyIdOrderByIdDesc(me.companyId()).stream()
				.map(i -> new SupplierInspectionRow(i.id, i.linkedModule, i.linkedRecordId, i.partNumber,
						i.batchLotNo, i.resultStatus, i.inspectionDate))
				.toList();
		}
		if (CUSTOMER_VIEWERS.contains(me.role())) {
			return inspections.findByCustomerCompanyIdAndCustomerVisibleTrueOrderByIdDesc(me.companyId()).stream()
				.map(i -> new CustomerInspectionRow(i.id, i.linkedModule, i.linkedRecordId, i.partNumber,
						i.batchLotNo, i.resultStatus, i.inspectionDate))
				.toList();
		}
		List<Inspection> visible = switch (QualityScope.of(me.role())) {
			case FULL -> inspections.findAllByOrderByIdDesc();
			case KAM -> {
				Set<Long> accounts = directory.accountsOf(me.userId());
				yield accounts.isEmpty() ? List.of() : inspections.findByCustomerCompanyIdInOrderByIdDesc(accounts);
			}
			case NPD_LINKED -> inspections.findByLinkedModuleOrderByIdDesc(LinkedModule.NPD);
			case OFP_LINKED -> inspections.findByLinkedModuleOrderByIdDesc(LinkedModule.OFP);
			default -> List.of(); // SRS §10.8 X: ERGO RFQ and every other role not handled above/below
		};
		Map<Long, CompanyRef> customers = directory.companies(ids(visible, i -> i.customerCompanyId));
		Map<Long, CompanyRef> suppliers = directory.companies(ids(visible, i -> i.supplierCompanyId));
		return visible.stream()
			.map(i -> new InspectionRow(i.id, i.linkedModule, i.linkedRecordId, name(customers, i.customerCompanyId),
					name(suppliers, i.supplierCompanyId), i.partNumber, i.batchLotNo, i.resultStatus,
					i.inspectionDate, i.customerVisible))
			.toList();
	}

	@Transactional(readOnly = true)
	public InspectionDetail get(long id) {
		CurrentUser me = CurrentUser.get();
		return view(me, visible(me, id));
	}

	// ---------------------------------------------------------------- create / edit

	public InspectionDetail create(InspectionForm form) {
		CurrentUser me = CurrentUser.get();
		boolean ergoEditor = EDITORS.contains(me.role());
		boolean supplierEditor = SUPPLIER_EDITORS.contains(me.role());
		if (!ergoEditor && !supplierEditor) {
			throw ApiException.forbidden("Only ERGO Quality/Admin or Supplier Quality may create an inspection");
		}
		Inspection inspection = new Inspection();
		inspection.linkedModule = form.linkedModule();
		inspection.linkedRecordId = form.linkedRecordId();
		inspection.partNumber = form.partNumber().trim();
		inspection.batchLotNo = text(form.batchLotNo());
		if (form.linkedModule() == LinkedModule.NPD || form.linkedModule() == LinkedModule.OFP) {
			// Security review M5: for an inspection linked to a real NPD/OFP record, both companies are always
			// derived from that record server-side, for either creator - never trusted from the client, and never
			// freely ERGO-editable either (see applyUpdate's own NPD/OFP guard below).
			applyLinkedCompanies(inspection, form.linkedModule(), form.linkedRecordId(),
					supplierEditor ? me.companyId() : null);
			inspection.customerVisible = me.companyType() == CompanyType.ERGO && ModuleAccess.canApprove(me.role(), Module.QUALITY)
					&& form.customerVisible(); // ERGO's Quality approvers only, whatever level another side is given
		}
		else if (supplierEditor) {
			// Two-way anonymity: a supplier's own inspection is tagged to its own company only. customerCompanyId
			// and customerVisible are never trusted from a supplier's own request (ERGO's own edit sets them later).
			inspection.supplierCompanyId = me.companyId();
			inspection.customerCompanyId = null;
			inspection.customerVisible = false;
		}
		else {
			// Security review M5: INCOMING/FINAL/IN_PROCESS have no backing NPD/OFP record to resolve companies
			// against, so an ERGO creator's request is trusted here (validated against the Directory below), same
			// as before this review.
			inspection.customerCompanyId = requireCompanyIfGiven(form.customerCompanyId(), CompanyType.CUSTOMER,
					"Customer");
			inspection.supplierCompanyId = requireCompanyIfGiven(form.supplierCompanyId(), CompanyType.SUPPLIER,
					"Supplier");
			inspection.customerVisible = ModuleAccess.canApprove(me.role(), Module.QUALITY) && form.customerVisible();
		}
		inspection.resultStatus = ResultStatus.OPEN;
		inspection.quantityRejected = BigDecimal.ZERO;
		inspection.createdByUserId = me.userId();
		inspection.createdByCompanyId = me.companyId(); // security review H2: who actually created this row
		if (!inScope(me, inspection)) {
			// e.g. ERGO OFP ("A") may only raise inspections against OFP orders: it could never open any other one
			throw ApiException.unprocessable("Your role can only raise inspections within its own area");
		}
		inspections.save(inspection);
		return view(me, inspection);
	}

	/** Security review M5: resolves an NPD/OFP-linked inspection's companies through {@code npd.api}/{@code ofp.api}
	 * instead of trusting the client, and - when the creator is a supplier - verifies the linked record is actually
	 * theirs (rejected before any company id from it is ever returned to them). */
	private void applyLinkedCompanies(Inspection inspection, LinkedModule module, long linkedRecordId,
			Long supplierCallerCompanyId) {
		long customerCompanyId;
		long supplierCompanyId;
		if (module == LinkedModule.NPD) {
			NpdApi.ProjectRef ref = npdApi.projectRef(linkedRecordId)
				.orElseThrow(() -> ApiException.unprocessable("Linked NPD project not found"));
			customerCompanyId = ref.customerCompanyId();
			supplierCompanyId = ref.supplierCompanyId();
		}
		else {
			OfpApi.OrderRef ref = ofpApi.orderRef(linkedRecordId)
				.orElseThrow(() -> ApiException.unprocessable("Linked OFP order not found"));
			customerCompanyId = ref.customerCompanyId();
			supplierCompanyId = ref.supplierCompanyId();
		}
		if (supplierCallerCompanyId != null && !supplierCallerCompanyId.equals(supplierCompanyId)) {
			throw ApiException.unprocessable("The linked record does not belong to your company");
		}
		inspection.customerCompanyId = customerCompanyId;
		inspection.supplierCompanyId = supplierCompanyId;
	}

	public InspectionDetail edit(long id, InspectionUpdateForm form) {
		CurrentUser me = CurrentUser.get();
		Inspection i = visible(me, id);
		require(InspectionAction.EDIT, me, i);
		applyUpdate(i, form, EDITORS.contains(me.role()), ModuleAccess.canApprove(me.role(), Module.QUALITY));
		return view(me, i);
	}

	private void applyUpdate(Inspection i, InspectionUpdateForm form, boolean ergoEditor,
			boolean decidesExposure) {
		// Captured before any mutation below (security review M6): if this edit turns out to reassign the customer
		// or supplier, the record's pre-edit remarks is what the audit trail needs to show as cleared - by the time
		// reassignment is known, i.remarks below has already been overwritten by this same request's own value.
		String previousRemarks = i.remarks;
		i.partNumber = form.partNumber().trim();
		i.batchLotNo = text(form.batchLotNo());
		i.quantityChecked = form.quantityChecked();
		i.quantityRejected = form.quantityRejected() == null ? BigDecimal.ZERO : form.quantityRejected();
		if (i.quantityChecked != null && i.quantityRejected.compareTo(i.quantityChecked) > 0) {
			throw ApiException.unprocessable("Quantity rejected cannot exceed quantity checked");
		}
		i.inspectionDate = form.inspectionDate();
		boolean reassigned = false;
		// ERGO-only fields (never trusted from a supplier's own edit; two-way anonymity + "ERGO controls customer
		// exposure" - see the class comment): a supplier's edit leaves customerVisible/customerCompanyId/
		// supplierCompanyId exactly as they were. remarks joins this list (bug fix, 2026-09-30): it's ERGO's own
		// internal free text, dropped entirely from SupplierInspectionView (security review H1), so a supplier's edit
		// form can never carry the current value back - applying it verbatim on every editor's request silently wiped
		// out whatever ERGO Quality had written. A supplier's edit leaves remarks untouched, same as the other three.
		if (ergoEditor) {
			i.remarks = text(form.remarks());
			// external exposure is decided by the Quality approvers only, not by every ERGO editor (e.g. ERGO OFP)
			if (decidesExposure) {
				i.customerVisible = form.customerVisible();
			}
			// Security review M5: an NPD/OFP-linked inspection's companies come only from applyLinkedCompanies at
			// creation - not from a later edit - so they can never drift from the record they're linked to.
			if (i.linkedModule != LinkedModule.NPD && i.linkedModule != LinkedModule.OFP) {
				Long newCustomerCompanyId = requireCompanyIfGiven(form.customerCompanyId(), CompanyType.CUSTOMER,
						"Customer");
				Long newSupplierCompanyId = requireCompanyIfGiven(form.supplierCompanyId(), CompanyType.SUPPLIER,
						"Supplier");
				reassigned = !Objects.equals(i.customerCompanyId, newCustomerCompanyId)
						|| !Objects.equals(i.supplierCompanyId, newSupplierCompanyId);
				i.customerCompanyId = newCustomerCompanyId;
				i.supplierCompanyId = newSupplierCompanyId;
			}
		}
		if (reassigned) {
			// Security review M6 (matches NPD's identical H2 fix, ARCHITECTURE.md §8 2026-09-26): a reassignment
			// clears remarks/defects outright - including whatever this same request also tried to set for them -
			// rather than only "not carrying the old value forward", since a client that simply re-submits a
			// pre-filled edit form would otherwise carry the previous party's authored text across unchanged, and a
			// value the client deliberately resupplies in the very same request is indistinguishable from one it
			// carried over by not touching the field. ERGO adds fresh notes under the new company in a follow-up
			// edit instead.
			clearAuthoredContentOnReassignment(i, previousRemarks);
		}
		else {
			replaceDefects(i.id, form.defects());
		}
	}

	private void clearAuthoredContentOnReassignment(Inspection i, String previousRemarks) {
		List<InspectionDefect> existingDefects = defects.findByInspectionIdOrderById(i.id);
		i.remarks = null;
		if (previousRemarks == null && existingDefects.isEmpty()) {
			return; // nothing was authored yet, nothing to audit or delete
		}
		audit.record(Inspection.class, i.id, "REASSIGNED_CONTENT_CLEARED", "remarks/defects",
				"remarks=" + previousRemarks + "; defects=" + describeDefects(existingDefects), null);
		defects.deleteByInspectionId(i.id);
	}

	/** Security review M3: delete-then-reinsert loses the old defect content with no audit trail of what it was.
	 * Skips the destructive replace entirely (and the audit noise) when nothing actually changed, and otherwise
	 * writes one explicit audit row carrying the full old and new defect sets before replacing them - "services
	 * audit collection changes as explicit events" (AuditEntityListener's own javadoc), matching the pattern
	 * already used for master-list deletes ({@code ExchangeRateService.delete}). */
	private void replaceDefects(long inspectionId, List<DefectForm> forms) {
		List<InspectionDefect> before = defects.findByInspectionIdOrderById(inspectionId);
		String beforeSummary = describeDefects(before);
		String afterSummary = describeDefectForms(forms);
		if (beforeSummary.equals(afterSummary)) {
			return;
		}
		if (!before.isEmpty()) {
			audit.record(Inspection.class, inspectionId, "DEFECTS_REPLACED", "defects", beforeSummary, afterSummary);
		}
		defects.deleteByInspectionId(inspectionId);
		if (forms == null) {
			return;
		}
		for (DefectForm f : forms) {
			InspectionDefect d = new InspectionDefect();
			d.inspectionId = inspectionId;
			d.defectCategory = f.defectCategory().trim();
			d.defectDescription = text(f.defectDescription());
			d.defectQuantity = f.defectQuantity();
			d.severity = f.severity();
			defects.save(d);
		}
	}

	private static String describeDefects(List<InspectionDefect> rows) {
		return rows.stream().map(d -> defectLine(d.defectCategory, d.defectDescription, d.defectQuantity, d.severity))
			.collect(Collectors.collectingAndThen(Collectors.toCollection(TreeSet::new), s -> String.join(" | ", s)));
	}

	private static String describeDefectForms(List<DefectForm> forms) {
		if (forms == null) {
			return "";
		}
		return forms.stream()
			.map(f -> defectLine(f.defectCategory(), f.defectDescription(), f.defectQuantity(), f.severity()))
			.collect(Collectors.collectingAndThen(Collectors.toCollection(TreeSet::new), s -> String.join(" | ", s)));
	}

	private static String defectLine(String category, String description, BigDecimal quantity, DefectSeverity severity) {
		return category + "/" + (description == null ? "" : description) + "/" + quantity + "/" + severity;
	}

	// ---------------------------------------------------------------- workflow (SRS §14.7)

	/** SRS §10.8 Submit: OPEN -> IN_INSPECTION, recording the raw checked/rejected quantities, defects and
	 * inspection date - business validation before the record can leave OPEN (mirrors RFQ's "cost breakdown
	 * required before submit" / NPD's mandatory-activity gate). */
	public InspectionDetail submit(long id, InspectionUpdateForm form) {
		CurrentUser me = CurrentUser.get();
		Inspection i = visible(me, id);
		require(InspectionAction.SUBMIT, me, i);
		applyUpdate(i, form, EDITORS.contains(me.role()), ModuleAccess.canApprove(me.role(), Module.QUALITY));
		if (i.quantityChecked == null) {
			throw ApiException.unprocessable("Quantity checked is required before an inspection can be submitted");
		}
		if (i.inspectionDate == null) {
			throw ApiException.unprocessable("Inspection date is required before an inspection can be submitted");
		}
		i.resultStatus = ResultStatus.IN_INSPECTION;
		i.inspectedByUserId = me.userId();
		return view(me, i);
	}

	/** SRS §10.8 Approve: IN_INSPECTION -> PASSED. */
	public InspectionDetail pass(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Inspection i = visible(me, id);
		require(InspectionAction.PASS, me, i);
		reasonIfGiven(form.reason());
		i.resultStatus = ResultStatus.PASSED;
		told(i, "Passed");
		return view(me, i);
	}

	/** SRS §10.8 Approve, §14.7: IN_INSPECTION -> REJECTED. Reason required (CLAUDE.md non-negotiable). */
	public InspectionDetail reject(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Inspection i = visible(me, id);
		require(InspectionAction.REJECT, me, i);
		audit.reason(required(form.reason(), "A reason is required to reject an inspection"));
		i.resultStatus = ResultStatus.REJECTED;
		told(i, "Rejected");
		return view(me, i);
	}

	/** SRS §10.8 Approve, §14.7: IN_INSPECTION -> RELEASED_WITH_DEVIATION. Reason required: a conditional release
	 * needs its justification on record, same as Reject. */
	public InspectionDetail releaseWithDeviation(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Inspection i = visible(me, id);
		require(InspectionAction.RELEASE_WITH_DEVIATION, me, i);
		audit.reason(required(form.reason(), "A reason is required to release an inspection with deviation"));
		i.resultStatus = ResultStatus.RELEASED_WITH_DEVIATION;
		told(i, "Released with deviation");
		return view(me, i);
	}

	/** (PASSED | REJECTED | RELEASED_WITH_DEVIATION) -> CLOSED. No SRS-named trigger; every other terminal
	 * transition in this codebase (RFQ Closed, NPD Closed, OFP Close Order) is likewise an explicit action, not
	 * automatic - same approve-tier authority that decided the outcome. */
	public InspectionDetail close(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Inspection i = visible(me, id);
		require(InspectionAction.CLOSE, me, i);
		reasonIfGiven(form.reason());
		i.resultStatus = ResultStatus.CLOSED;
		told(i, "Closed");
		return view(me, i);
	}

	/** CLOSED -> OPEN, the only reopen path (build brief). Reason required (CLAUDE.md non-negotiable). */
	public InspectionDetail reopen(long id, ReasonForm form) {
		CurrentUser me = CurrentUser.get();
		Inspection i = visible(me, id);
		require(InspectionAction.REOPEN, me, i);
		audit.reason(required(form.reason(), "A reason is required to reopen an inspection"));
		i.resultStatus = ResultStatus.OPEN;
		told(i, "Reopened");
		return view(me, i);
	}

	/** SRS §14.9: the result of an inspection, to everyone on it - never the reason (a rejection's reason may be
	 * ERGO-internal). The customer is dropped at delivery unless the inspection is customer-visible. */
	private void told(Inspection i, String detail) {
		notifier.send(NotificationKind.INSPECTION_UPDATED, "inspection", i.id, "Inspection #" + i.id, detail,
				QualityNotices.everyone(i.customerCompanyId, i.supplierCompanyId));
	}

	// ---------------------------------------------------------------- rules

	/** Record scope. Out-of-scope inspections do not exist for the caller (404, not 403). */
	Inspection visible(CurrentUser me, long id) {
		Inspection i = inspections.findById(id).orElse(null);
		if (!inScope(me, i)) {
			throw ApiException.notFound("Inspection");
		}
		return i;
	}

	private boolean inScope(CurrentUser me, Inspection i) {
		return i != null && switch (QualityScope.of(me.role())) {
			case FULL -> true;
			case KAM ->
				i.customerCompanyId != null && directory.accountsOf(me.userId()).contains(i.customerCompanyId);
			case NPD_LINKED -> i.linkedModule == LinkedModule.NPD;
			case OFP_LINKED -> i.linkedModule == LinkedModule.OFP;
			case SUPPLIER ->
				i.supplierCompanyId != null && me.companyId() == i.supplierCompanyId;
			case CUSTOMER ->
				i.customerVisible && i.customerCompanyId != null && me.companyId() == i.customerCompanyId;
			default -> false; // roles with no Quality access
		};
	}

	/** The rule book: SRS §10.8 rights combined with the §14.7 status. */
	private boolean allowed(InspectionAction action, CurrentUser me, Inspection i) {
		boolean ergoEditor = EDITORS.contains(me.role());
		// Security review H2 (confirmed with the user, ARCHITECTURE.md §8 2026-09-30): a supplier may edit/submit
		// only an inspection its own company *created* - not merely one tagged to its company (an ERGO-authored
		// inspection tagged to a supplier, e.g. ERGO's own incoming inspection of that supplier's goods, must stay
		// read-only to that supplier).
		boolean ownSupplier = SUPPLIER_EDITORS.contains(me.role()) && i.createdByCompanyId != null
				&& me.companyId() == i.createdByCompanyId;
		boolean approver = APPROVERS.contains(me.role());
		// Security review M2: the same user who submitted the inspection (and so became inspectedByUserId) must not
		// also decide its own outcome, regardless of role - the same "requester may not decide their own approval"
		// rule NPD's M-1 established (ARCHITECTURE.md §8 2026-09-27). Scoped to the three outcome-deciding actions
		// only: Close/Reopen finalize or reverse a decision someone else already made (or, for a supplier creator,
		// are unreachable anyway since Supplier Quality is never an approver), so there is no "own approval" for
		// either of them to defeat the same way PASS/REJECT/RELEASE_WITH_DEVIATION could.
		boolean selfApproval = i.inspectedByUserId != null && me.userId() == i.inspectedByUserId;
		ResultStatus s = i.resultStatus;
		return switch (action) {
			// Security review M1: only OPEN is editable. Once submitted (IN_INSPECTION), the record is frozen from
			// silent edits, matching this codebase's established pattern - the only way back to an editable state
			// is the workflow's own transitions (a decision, then Close, then Reopen with a reason).
			case EDIT -> (ergoEditor || ownSupplier) && s == ResultStatus.OPEN;
			case SUBMIT -> (ergoEditor || ownSupplier) && s == ResultStatus.OPEN;
			case PASS, REJECT, RELEASE_WITH_DEVIATION -> approver && !selfApproval && s == ResultStatus.IN_INSPECTION;
			case CLOSE -> approver
					&& (s == ResultStatus.PASSED || s == ResultStatus.REJECTED
							|| s == ResultStatus.RELEASED_WITH_DEVIATION);
			case REOPEN -> approver && s == ResultStatus.CLOSED;
		};
	}

	private Set<InspectionAction> actions(CurrentUser me, Inspection i) {
		return Arrays.stream(InspectionAction.values()).filter(a -> allowed(a, me, i))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(InspectionAction.class)));
	}

	private void require(InspectionAction action, CurrentUser me, Inspection i) {
		if (!allowed(action, me, i)) {
			throw ApiException.conflict("This action is not available for this inspection right now");
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

	// ---------------------------------------------------------------- view assembly

	/** The single per-audience view builder: a supplier caller gets SupplierInspectionView, a customer caller gets
	 * CustomerInspectionView (built without ever looking up the supplier), everyone else reaching here is ERGO
	 * (visible() already 404s every other role). */
	private InspectionDetail view(CurrentUser me, Inspection i) {
		List<InspectionDefect> defectRows = defects.findByInspectionIdOrderById(i.id);

		if (SUPPLIER_VIEWERS.contains(me.role())) {
			List<InspectionDefectView> defectViews = defectRows.stream()
				.map(d -> new InspectionDefectView(d.id, d.defectCategory, d.defectDescription, d.defectQuantity,
						d.severity, d.createdAt))
				.toList();
			// Security review H1: no remarks (shared free text either side can write; ERGO's own internal findings
			// must never reach the supplier) - dropped entirely, see SupplierInspectionView's own javadoc.
			return new SupplierInspectionView(i.id, i.linkedModule, i.linkedRecordId, i.partNumber, i.batchLotNo,
					i.quantityChecked, i.quantityRejected, i.resultStatus, i.inspectionDate, defectViews,
					actions(me, i), i.createdAt, i.updatedAt);
		}

		if (CUSTOMER_VIEWERS.contains(me.role())) {
			// Security review M4: no defectCategory/defectDescription free text - same class of leak as H1.
			List<CustomerInspectionDefectView> defectViews = defectRows.stream()
				.map(d -> new CustomerInspectionDefectView(d.id, d.defectQuantity, d.severity, d.createdAt))
				.toList();
			return new CustomerInspectionView(i.id, i.linkedModule, i.linkedRecordId, i.partNumber, i.batchLotNo,
					i.quantityChecked, i.quantityRejected, i.resultStatus, i.inspectionDate, defectViews,
					actions(me, i), i.createdAt, i.updatedAt);
		}

		// Explicit guard (matches OFP's M3 fix, ARCHITECTURE.md §8 2026-09-29): this branch must only ever be
		// reached for ERGO roles - visible() already 404s every other role, but a future widening of visible()
		// should not be able to silently fall through to the unrestricted ERGO view.
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.forbidden("Access denied");
		}
		List<InspectionDefectView> defectViews = defectRows.stream()
			.map(d -> new InspectionDefectView(d.id, d.defectCategory, d.defectDescription, d.defectQuantity,
					d.severity, d.createdAt))
			.toList();
		String customerName = i.customerCompanyId == null ? null : name(directory.companies(Set.of(i.customerCompanyId)), i.customerCompanyId);
		String supplierName = i.supplierCompanyId == null ? null : name(directory.companies(Set.of(i.supplierCompanyId)), i.supplierCompanyId);
		String inspectedByName = i.inspectedByUserId == null ? null
				: userName(directory.users(Set.of(i.inspectedByUserId)), i.inspectedByUserId);
		return new InspectionView(i.id, i.linkedModule, i.linkedRecordId, i.customerCompanyId, customerName,
				i.supplierCompanyId, supplierName, i.partNumber, i.batchLotNo, i.quantityChecked, i.quantityRejected,
				i.resultStatus, i.inspectedByUserId, inspectedByName, i.inspectionDate, i.remarks, i.customerVisible,
				defectViews, actions(me, i), i.createdAt, i.updatedAt);
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
		return com.ergo.connect.common.validation.Blank.is(value) ? null : value.trim();
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
