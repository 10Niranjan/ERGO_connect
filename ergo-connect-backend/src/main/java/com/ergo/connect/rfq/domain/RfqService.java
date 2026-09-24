package com.ergo.connect.rfq.domain;

import static com.ergo.connect.common.security.Role.CUSTOMER_KAM;
import static com.ergo.connect.common.security.Role.CUSTOMER_MANAGEMENT;
import static com.ergo.connect.common.security.Role.CUSTOMER_RFQ;
import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;
import static com.ergo.connect.common.security.Role.ERGO_RFQ;
import static com.ergo.connect.rfq.domain.RfqStatus.CANCELLED;
import static com.ergo.connect.rfq.domain.RfqStatus.CLARIFICATION_REQUIRED;
import static com.ergo.connect.rfq.domain.RfqStatus.CLOSED;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_APPROVED;
import static com.ergo.connect.rfq.domain.RfqStatus.DRAFT;
import static com.ergo.connect.rfq.domain.RfqStatus.SUBMITTED;
import static com.ergo.connect.rfq.domain.RfqStatus.UNDER_REVIEW;
import static com.ergo.connect.rfq.domain.RfqStatus.VALIDATED;

import java.time.Year;
import java.util.ArrayList;
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
import com.ergo.connect.common.audit.AuditLogService.AuditEntry;
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
import com.ergo.connect.rfq.domain.RfqDtos.CustomerRfqRow;
import com.ergo.connect.rfq.domain.RfqDtos.CustomerRfqView;
import com.ergo.connect.rfq.domain.RfqDtos.DocumentView;
import com.ergo.connect.rfq.domain.RfqDtos.ErgoRfqRow;
import com.ergo.connect.rfq.domain.RfqDtos.ErgoRfqView;
import com.ergo.connect.rfq.domain.RfqDtos.Named;
import com.ergo.connect.rfq.domain.RfqDtos.PartView;
import com.ergo.connect.rfq.domain.RfqDtos.RfqAction;
import com.ergo.connect.rfq.domain.RfqDtos.RfqForm;
import com.ergo.connect.rfq.domain.RfqDtos.RfqRow;
import com.ergo.connect.rfq.domain.RfqDtos.RfqView;
import com.ergo.connect.rfq.domain.RfqDtos.StepView;
import com.ergo.connect.rfq.domain.RfqDtos.TargetsView;

/**
 * RFQ lifecycle up to validation (SRS §5, §14.2, §17.1). Record scope (SRS §10.4, §8.2): ERGO RFQ / Admin /
 * Management see all, ERGO KAMs their assigned customers, ERGO NPD approved work, customers their own company.
 * {@link #allowed} is the single rule book: it enforces every action and lists the caller's buttons.
 */
@Service
@Transactional
public class RfqService {

	static final String MODULE = "rfq";

	/** SRS §10.4 create / edit / submit. */
	private static final Set<Role> ERGO_EDITORS = EnumSet.of(ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ, ERGO_KAM);

	/** SRS §10.4 approve = validate. */
	private static final Set<Role> ERGO_REVIEWERS = EnumSet.of(ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ);

	private static final Set<Role> CUSTOMER_EDITORS = EnumSet.of(CUSTOMER_RFQ, CUSTOMER_KAM, CUSTOMER_MANAGEMENT);

	/** SRS §10.4 "RV" for ERGO NPD: approved RFQs heading to NPD. */
	private static final Set<RfqStatus> NPD_VISIBLE = EnumSet.of(CUSTOMER_APPROVED, CLOSED);

	/** While the customer may still change the RFQ. */
	private static final Set<RfqStatus> CUSTOMER_OPEN = EnumSet.of(DRAFT, SUBMITTED, CLARIFICATION_REQUIRED);

	/** Before any supplier has seen it: files may still be removed, ERGO may still cancel. */
	private static final Set<RfqStatus> BEFORE_FLOAT = EnumSet.of(DRAFT, SUBMITTED, UNDER_REVIEW,
			CLARIFICATION_REQUIRED, VALIDATED);

	/** A downloadable file: metadata plus content. */
	public record DocumentFile(DocumentInfo info, byte[] content) {
	}

	private final RfqRepository rfqs;

	private final Directory directory;

	private final MasterDataApi masterData;

	private final DocumentStore documents;

	private final AuditLogService audit;

	private final JdbcTemplate jdbc;

	RfqService(RfqRepository rfqs, Directory directory, MasterDataApi masterData, DocumentStore documents,
			AuditLogService audit, JdbcTemplate jdbc) {
		this.rfqs = rfqs;
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
		List<Rfq> visible = switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ -> rfqs.findAllByOrderByIdDesc();
			case ERGO_KAM -> {
				Set<Long> accounts = directory.accountsOf(me.userId());
				yield accounts.isEmpty() ? List.of() : rfqs.findByCustomerCompanyIdInOrderByIdDesc(accounts);
			}
			case ERGO_NPD -> rfqs.findByStatusInOrderByIdDesc(NPD_VISIBLE);
			case CUSTOMER_RFQ, CUSTOMER_KAM, CUSTOMER_MANAGEMENT ->
				rfqs.findByCustomerCompanyIdOrderByIdDesc(me.companyId());
			default -> List.of(); // suppliers: RFQs sent to them (Phase 2b); other roles: no RFQ access (SRS §10.4 X)
		};
		if (!isErgo(me)) {
			return visible.stream()
				.<RfqRow>map(r -> new CustomerRfqRow(r.id, r.number, r.status.forCustomer(), r.partNumber, r.revision,
						r.description, r.targetQuoteDate, r.priority, r.createdAt))
				.toList();
		}
		Map<Long, CompanyRef> customers = directory.companies(ids(visible, r -> r.customerCompanyId));
		Map<Long, UserRef> owners = directory.users(ids(visible, r -> r.ownerUserId));
		return visible.stream()
			.<RfqRow>map(r -> new ErgoRfqRow(r.id, r.number, r.status, customers.get(r.customerCompanyId).name(),
					r.partNumber, r.revision, r.description, r.targetQuoteDate, r.priority,
					userName(owners, r.ownerUserId), r.createdAt))
			.toList();
	}

	@Transactional(readOnly = true)
	public RfqView get(long id) {
		CurrentUser me = CurrentUser.get();
		return view(visible(me, id), me);
	}

	/** SRS §15.5 audit trail tab. ERGO only: it contains internal notes and internal steps. */
	@Transactional(readOnly = true)
	public List<AuditEntry> history(long id) {
		CurrentUser me = CurrentUser.get();
		visible(me, id);
		if (!isErgo(me)) {
			throw ApiException.forbidden("The audit trail is internal to ERGO");
		}
		return audit.history(MODULE, "Rfq", id);
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
		apply(rfq, form, me);
		rfqs.save(rfq);
		auditSteps(rfq, List.of());
		return view(rfq, me);
	}

	public RfqView update(long id, RfqForm form) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.EDIT, me, rfq);
		if (rfq.status == UNDER_REVIEW && (!Objects.equals(rfq.partNumber, form.partNumber().trim())
				|| !Objects.equals(rfq.revision, text(form.revision()))
				|| !Objects.equals(rfq.description, text(form.description())))) {
			throw ApiException.unprocessable(
					"Part number, revision and description can only change while the RFQ is a draft or submitted");
		}
		List<ProcessStep> before = List.copyOf(rfq.processSteps);
		apply(rfq, form, me);
		auditSteps(rfq, before);
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
		copy.partNumber = source.partNumber;
		copy.revision = source.revision;
		copy.description = source.description;
		copy.eau = source.eau;
		copy.netWeightKg = source.netWeightKg;
		copy.grossWeightKg = source.grossWeightKg;
		copy.materialId = source.materialId;
		copy.materialSpec = source.materialSpec;
		copy.coatingId = source.coatingId;
		copy.coatingSpec = source.coatingSpec;
		copy.currencyId = source.currencyId;
		copy.targetToolingCost = source.targetToolingCost;
		copy.targetPpapPiecePrice = source.targetPpapPiecePrice;
		copy.targetProductionPiecePrice = source.targetProductionPiecePrice;
		copy.paymentTermId = source.paymentTermId;
		copy.materialLeadTimeWeeks = source.materialLeadTimeWeeks;
		copy.toolingLeadTimeDays = source.toolingLeadTimeDays;
		copy.sampleLeadTimeDays = source.sampleLeadTimeDays;
		copy.productionLeadTimeDays = source.productionLeadTimeDays;
		copy.moq = source.moq;
		copy.customerRemarks = source.customerRemarks;
		copy.processSteps.addAll(source.processSteps);
		rfqs.save(copy);
		audit.record(Rfq.class, copy.id, "CLONED_FROM", "rfq", null, source.number);
		return view(copy, me);
	}

	// ---------------------------------------------------------------- workflow (SRS §14.2)

	/** Draft → Submitted, or (answering a clarification) → Under ERGO Review. */
	public RfqView submit(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.SUBMIT, me, rfq);
		requireComplete(rfq);
		if (rfq.status == CLARIFICATION_REQUIRED) {
			rfq.clarificationAnswer = required(comment, "Answer the clarification question before resubmitting");
			audit.reason(rfq.clarificationAnswer);
			rfq.status = UNDER_REVIEW;
		}
		else {
			reasonIfGiven(comment);
			rfq.status = SUBMITTED;
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
		return view(rfq, me);
	}

	/** SRS §5.6 / §14.2: nothing goes to suppliers before ERGO validates it. */
	public RfqView validate(long id, String comment) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.VALIDATE, me, rfq);
		requireComplete(rfq);
		reasonIfGiven(comment);
		rfq.status = VALIDATED;
		if (rfq.ownerUserId == null) {
			rfq.ownerUserId = me.userId();
		}
		return view(rfq, me);
	}

	/** SRS §17.1 "Re-open RFQ" (ERGO Admin / RFQ), reason required (SRS §14.1, §15.6). */
	public RfqView reopen(long id, String reason) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.REOPEN, me, rfq);
		audit.reason(required(reason, "A reason is required to re-open an RFQ"));
		rfq.status = UNDER_REVIEW;
		return view(rfq, me);
	}

	/** SRS §17.1: only before any supplier response. */
	public RfqView cancel(long id, String reason) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.CANCEL, me, rfq);
		audit.reason(required(reason, "A reason is required to cancel an RFQ"));
		rfq.status = CANCELLED;
		return view(rfq, me);
	}

	// ---------------------------------------------------------------- documents (drawings etc.)

	@Transactional(readOnly = true)
	public List<DocumentView> documents(long id) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		return documents.list(MODULE, id).stream().filter(d -> canSee(me, d)).map(d -> documentView(me, rfq, d))
			.toList();
	}

	/**
	 * Customers' uploads are visible to ERGO and themselves; only ERGO decides what suppliers see (client decision:
	 * the customer's identity is hidden from suppliers, so ERGO may share a cleaned copy instead).
	 */
	public DocumentView upload(long id, String fileName, byte[] content, Visibility requested) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		require(RfqAction.UPLOAD, me, rfq);
		Visibility visibility = isErgo(me) ? (requested == null ? Visibility.INTERNAL : requested) : Visibility.CUSTOMER;
		return documentView(me, rfq, documents.store(MODULE, id, fileName, content, visibility));
	}

	@Transactional(readOnly = true)
	public DocumentFile download(long id, long documentId) {
		CurrentUser me = CurrentUser.get();
		visible(me, id);
		DocumentInfo document = document(me, id, documentId);
		return new DocumentFile(document, documents.content(document));
	}

	public DocumentView setVisibility(long id, long documentId, Visibility visibility) {
		CurrentUser me = CurrentUser.get();
		Rfq rfq = visible(me, id);
		if (!ERGO_EDITORS.contains(me.role())) {
			throw ApiException.forbidden("Only ERGO decides who sees a document");
		}
		DocumentInfo document = document(me, id, documentId);
		documents.setVisibility(document, visibility);
		return documentView(me, rfq, document(me, id, documentId));
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

	/** Record scope. Out-of-scope RFQs do not exist for the caller (404, not 403). */
	private Rfq visible(CurrentUser me, long id) {
		Rfq rfq = rfqs.findById(id).orElse(null);
		boolean inScope = rfq != null && switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ -> true;
			case ERGO_KAM -> directory.accountsOf(me.userId()).contains(rfq.customerCompanyId);
			case ERGO_NPD -> NPD_VISIBLE.contains(rfq.status);
			case CUSTOMER_RFQ, CUSTOMER_KAM, CUSTOMER_MANAGEMENT -> rfq.customerCompanyId == me.companyId();
			default -> false;
		};
		if (!inScope) {
			throw ApiException.notFound("RFQ");
		}
		return rfq;
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
			case VALIDATE -> status == UNDER_REVIEW && reviewer;
			case REOPEN -> status == VALIDATED && (role == ERGO_ADMIN || role == ERGO_RFQ);
			case CANCEL -> customer ? CUSTOMER_OPEN.contains(status) : ergo && BEFORE_FLOAT.contains(status);
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

	/** SRS §17.1 mandatory fields; process flow needed before suppliers can quote. */
	private static void requireComplete(Rfq rfq) {
		List<String> missing = new ArrayList<>();
		if (rfq.description == null) {
			missing.add("description");
		}
		if (rfq.materialId == null && rfq.materialSpec == null) {
			missing.add("material");
		}
		if (rfq.processSteps.isEmpty()) {
			missing.add("process flow (at least one step)");
		}
		if (rfq.targetQuoteDate == null) {
			missing.add("target quote date");
		}
		if (!missing.isEmpty()) {
			throw ApiException.unprocessable("Please complete: " + String.join(", ", missing));
		}
	}

	private long customerFor(CurrentUser me, Long requested) {
		if (CUSTOMER_EDITORS.contains(me.role())) {
			return me.companyId(); // customers always create for their own company
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
	private void apply(Rfq rfq, RfqForm form, CurrentUser me) {
		if (!Objects.equals(rfq.currencyId, form.currencyId())) {
			masterData.requireActive(Ref.CURRENCY, form.currencyId(), "Currency");
		}
		if (!Objects.equals(rfq.materialId, form.materialId())) {
			masterData.requireActive(Ref.MATERIAL, form.materialId(), "Material");
		}
		if (!Objects.equals(rfq.coatingId, form.coatingId())) {
			masterData.requireActive(Ref.COATING, form.coatingId(), "Coating");
		}
		if (!Objects.equals(rfq.paymentTermId, form.paymentTermId())) {
			masterData.requireActive(Ref.PAYMENT_TERM, form.paymentTermId(), "Payment term");
		}
		List<ProcessStep> steps = form.processSteps() == null ? List.of()
				: form.processSteps().stream().map(RfqService::clean).toList();
		Set<Long> knownProcesses = rfq.processSteps.stream().map(ProcessStep::processId).filter(Objects::nonNull)
			.collect(Collectors.toSet());
		steps.stream().map(ProcessStep::processId).filter(p -> p != null && !knownProcesses.contains(p))
			.forEach(p -> masterData.requireActive(Ref.PROCESS, p, "Process"));

		rfq.partNumber = form.partNumber().trim();
		rfq.revision = text(form.revision());
		rfq.description = text(form.description());
		rfq.eau = form.eau();
		rfq.netWeightKg = form.netWeightKg();
		rfq.grossWeightKg = form.grossWeightKg();
		rfq.materialId = form.materialId();
		rfq.materialSpec = text(form.materialSpec());
		rfq.coatingId = form.coatingId();
		rfq.coatingSpec = text(form.coatingSpec());
		rfq.targetQuoteDate = form.targetQuoteDate();
		rfq.priority = form.priority() == null ? Priority.NORMAL : form.priority();
		rfq.currencyId = form.currencyId();
		rfq.targetToolingCost = form.targetToolingCost();
		rfq.targetPpapPiecePrice = form.targetPpapPiecePrice();
		rfq.targetProductionPiecePrice = form.targetProductionPiecePrice();
		rfq.paymentTermId = form.paymentTermId();
		rfq.materialLeadTimeWeeks = form.materialLeadTimeWeeks();
		rfq.toolingLeadTimeDays = form.toolingLeadTimeDays();
		rfq.sampleLeadTimeDays = form.sampleLeadTimeDays();
		rfq.productionLeadTimeDays = form.productionLeadTimeDays();
		rfq.moq = form.moq();
		rfq.customerRemarks = text(form.customerRemarks());
		if (isErgo(me)) {
			rfq.internalNotes = text(form.internalNotes()); // never written by customers, whatever they send
		}
		if (!rfq.processSteps.equals(steps)) {
			rfq.processSteps.clear();
			rfq.processSteps.addAll(steps);
		}
	}

	private void auditSteps(Rfq rfq, List<ProcessStep> before) {
		if (!before.equals(rfq.processSteps)) {
			audit.record(Rfq.class, rfq.id, "UPDATE", "processFlow", route(before), route(rfq.processSteps));
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
		Map<Long, MasterDataApi.Named> materials = masterData.describe(Ref.MATERIAL, ids(List.of(r), x -> x.materialId));
		Map<Long, MasterDataApi.Named> coatings = masterData.describe(Ref.COATING, ids(List.of(r), x -> x.coatingId));
		Map<Long, MasterDataApi.Named> processes = masterData.describe(Ref.PROCESS,
				r.processSteps.stream().map(ProcessStep::processId).filter(Objects::nonNull).collect(Collectors.toSet()));
		Map<Long, MasterDataApi.Named> currencies = masterData.describe(Ref.CURRENCY, List.of(r.currencyId));
		Map<Long, MasterDataApi.Named> terms = masterData.describe(Ref.PAYMENT_TERM, ids(List.of(r), x -> x.paymentTermId));

		PartView part = new PartView(r.partNumber, r.revision, r.description, r.eau, r.netWeightKg, r.grossWeightKg,
				named(materials, r.materialId), r.materialSpec, named(coatings, r.coatingId), r.coatingSpec,
				r.targetQuoteDate, r.priority,
				r.processSteps.stream().map(s -> new StepView(named(processes, s.processId()), s.stepName(),
						s.cycleTimeSeconds(), s.sourcingType(), s.outsourcedSupplierName(), s.remarks())).toList());
		TargetsView targets = new TargetsView(named(currencies, r.currencyId), r.targetToolingCost,
				r.targetPpapPiecePrice, r.targetProductionPiecePrice, named(terms, r.paymentTermId),
				r.materialLeadTimeWeeks, r.toolingLeadTimeDays, r.sampleLeadTimeDays, r.productionLeadTimeDays, r.moq);
		Set<RfqAction> actions = actions(me, r);

		if (!isErgo(me)) {
			return new CustomerRfqView("CUSTOMER", r.id, r.number, r.status.forCustomer(), part, targets,
					r.customerRemarks, r.clarificationQuestion, r.clarificationAnswer, actions, r.createdAt,
					r.updatedAt);
		}
		CompanyRef customer = directory.company(r.customerCompanyId).orElseThrow();
		Map<Long, UserRef> users = directory.users(ids(List.of(r), x -> x.createdByUserId, x -> x.ownerUserId));
		return new ErgoRfqView("ERGO", r.id, r.number, r.status, r.sourceType,
				new Named(customer.id(), customer.code(), customer.name()), userName(users, r.createdByUserId),
				userName(users, r.ownerUserId), part, targets, r.customerRemarks, r.internalNotes,
				r.clarificationQuestion, r.clarificationAnswer, actions, r.createdAt, r.updatedAt);
	}

	private static DocumentView documentView(CurrentUser me, Rfq rfq, DocumentInfo d) {
		return new DocumentView(d.id(), d.fileName(), d.size(), d.visibility(),
				d.uploadedByCompanyId() == me.companyId(), canDelete(me, rfq, d), d.createdAt());
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
		return value == null || value.isBlank() ? null : value.trim();
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
