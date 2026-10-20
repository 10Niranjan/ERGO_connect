package com.ergo.connect.rfq.domain;

import static com.ergo.connect.common.security.Role.CUSTOMER_KAM;
import static com.ergo.connect.common.security.Role.CUSTOMER_MANAGEMENT;
import static com.ergo.connect.common.security.Role.CUSTOMER_RFQ;
import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;
import static com.ergo.connect.common.security.Role.ERGO_RFQ;
import static com.ergo.connect.rfq.domain.RfqStatus.CANCELLED;
import static com.ergo.connect.rfq.domain.RfqStatus.CLOSED;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_APPROVED;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_DECLINED;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_REVISION_REQUESTED;
import static com.ergo.connect.rfq.domain.RfqStatus.QUOTES_COMPLETE;
import static com.ergo.connect.rfq.domain.RfqStatus.QUOTES_PARTIAL;
import static com.ergo.connect.rfq.domain.RfqStatus.SENT_TO_CUSTOMER;
import static com.ergo.connect.rfq.domain.RfqStatus.SENT_TO_SUPPLIERS;
import static com.ergo.connect.rfq.domain.RfqStatus.UNDER_COMPARISON;
import static com.ergo.connect.rfq.domain.RfqStatus.VALIDATED;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.record.Awaiting;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.ModuleAccess;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.documents.api.DocumentStore;
import com.ergo.connect.documents.api.DocumentStore.DocumentInfo;
import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.MasterDataApi;
import com.ergo.connect.masterdata.api.MasterDataApi.Named;
import com.ergo.connect.masterdata.api.MasterDataApi.Ref;
import com.ergo.connect.rfq.api.RfqApi;

@Service
@Transactional
class RfqApiService implements RfqApi {

	/** Suppliers can be invited once validated, and more added while quotes come in. */
	static final Set<RfqStatus> INVITING = EnumSet.of(VALIDATED, SENT_TO_SUPPLIERS, QUOTES_PARTIAL, QUOTES_COMPLETE);

	/** Suppliers may work on their quotes (a customer revision request can re-open them). */
	static final Set<RfqStatus> QUOTING = EnumSet.of(SENT_TO_SUPPLIERS, QUOTES_PARTIAL, QUOTES_COMPLETE,
			UNDER_COMPARISON, CUSTOMER_REVISION_REQUESTED);

	/** Statuses driven by the quotes; from comparison on, ERGO's actions drive the status. */
	private static final Set<RfqStatus> PROGRESS = EnumSet.of(SENT_TO_SUPPLIERS, QUOTES_PARTIAL, QUOTES_COMPLETE);

	/** ERGO evaluates quotes once some are in, until the customer has decided. */
	static final Set<RfqStatus> EVALUATING = EnumSet.of(QUOTES_PARTIAL, QUOTES_COMPLETE, UNDER_COMPARISON,
			SENT_TO_CUSTOMER, CUSTOMER_REVISION_REQUESTED);

	/** ERGO may send the customer a quote: quotes are in and no earlier one awaits the customer's answer. */
	static final Set<RfqStatus> OFFERING = EnumSet.of(QUOTES_PARTIAL, QUOTES_COMPLETE, UNDER_COMPARISON,
			CUSTOMER_REVISION_REQUESTED);

	private final RfqRepository rfqs;

	private final RfqService rfqService;

	private final MasterDataApi masterData;

	private final DocumentStore documents;

	private final Directory directory;

	RfqApiService(RfqRepository rfqs, RfqService rfqService, MasterDataApi masterData, DocumentStore documents,
			Directory directory) {
		this.rfqs = rfqs;
		this.rfqService = rfqService;
		this.masterData = masterData;
		this.documents = documents;
		this.directory = directory;
	}

	@Override
	@Transactional(readOnly = true)
	public RfqRef forErgo(long rfqId) {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.notFound("RFQ");
		}
		Rfq r = rfqService.visible(me, rfqId);
		return new RfqRef(r.id, r.number, r.customerCompanyId, r.partNumber, r.revision, r.description, r.currencyId,
				INVITING.contains(r.status), QUOTING.contains(r.status), EVALUATING.contains(r.status),
				OFFERING.contains(r.status), new Targets(r.targetToolingCost, r.targetPpapPiecePrice,
						r.targetProductionPiecePrice, r.paymentTermId, r.moq, r.materialLeadTimeWeeks,
						r.toolingLeadTimeDays, r.sampleLeadTimeDays, r.productionLeadTimeDays));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean visibleToCaller(long rfqId) {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() == CompanyType.SUPPLIER) {
			return false;
		}
		return rfqService.inScope(me, rfqId);
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Optional<com.ergo.connect.common.record.RecordParties> partiesVisibleToCaller(long rfqId) {
		if (!visibleToCaller(rfqId)) {
			return java.util.Optional.empty();
		}
		return rfqs.findById(rfqId).map(r -> new com.ergo.connect.common.record.RecordParties(r.customerCompanyId, null));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean replaceOpenForCaller(long rfqId) {
		return rfqService.uploadOpen(CurrentUser.get(), rfqId);
	}

	@Override
	@Transactional(readOnly = true)
	public CustomerRfqRef forCustomer(long rfqId) {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() != CompanyType.CUSTOMER) {
			throw ApiException.notFound("RFQ");
		}
		Rfq r = rfqService.visible(me, rfqId);
		return new CustomerRfqRef(r.id, r.number, r.customerCompanyId, r.status == SENT_TO_CUSTOMER);
	}

	@Override
	@Transactional(readOnly = true)
	public String numberOf(long rfqId) {
		if (CurrentUser.get().companyType() != CompanyType.ERGO) {
			throw ApiException.notFound("RFQ"); // an external role reads its RFQ number only through forCustomer/forSupplier
		}
		return rfqs.findById(rfqId).map(r -> r.number).orElseThrow(() -> ApiException.notFound("RFQ"));
	}

	@Override
	@Transactional(readOnly = true)
	public SupplierRfq forSupplier(long rfqId) {
		SupplierRfq rfq = forSupplier(List.of(rfqId)).get(rfqId);
		if (rfq == null) {
			throw ApiException.notFound("RFQ");
		}
		return rfq;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, SupplierRfq> forSupplier(Collection<Long> rfqIds) {
		if (rfqIds.isEmpty()) {
			return Map.of();
		}
		List<Rfq> found = rfqs.findByIdIn(rfqIds);
		Map<Long, Named> materials = masterData.describe(Ref.MATERIAL, ids(found, r -> r.materialId));
		Map<Long, Named> coatings = masterData.describe(Ref.COATING, ids(found, r -> r.coatingId));
		Map<Long, Named> processes = masterData.describe(Ref.PROCESS, found.stream()
			.flatMap(r -> r.processSteps.stream().map(ProcessStep::processId)).filter(Objects::nonNull)
			.collect(Collectors.toSet()));
		return found.stream().collect(Collectors.toMap(r -> r.id, r -> new SupplierRfq(r.id, r.number, r.partNumber,
				r.revision, r.description, r.eau, r.netWeightKg, r.grossWeightKg, name(materials, r.materialId),
				r.materialSpec, name(coatings, r.coatingId), r.coatingSpec,
				r.processSteps.stream()
					.map(s -> new PlannedStep(s.processId(), name(processes, s.processId()), s.stepName()))
					.toList(),
				QUOTING.contains(r.status))));
	}

	@Override
	@Transactional(readOnly = true)
	public List<DocumentInfo> supplierDocuments(long rfqId) {
		return documents.list(RfqService.MODULE, rfqId).stream()
			.filter(d -> d.visibility() == Visibility.SUPPLIER || d.visibility() == Visibility.ALL)
			.toList();
	}

	@Override
	public void sentToSuppliers(long rfqId) {
		Rfq rfq = rfqs.findById(rfqId).orElseThrow();
		if (rfq.status == VALIDATED) {
			rfq.status = SENT_TO_SUPPLIERS;
		}
		else if (!INVITING.contains(rfq.status)) {
			throw ApiException.conflict("Suppliers can only be invited to a validated RFQ");
		}
	}

	@Override
	public void quoteProgress(long rfqId, int received, int awaited) {
		Rfq rfq = rfqs.findById(rfqId).orElseThrow();
		if (PROGRESS.contains(rfq.status)) {
			rfq.status = received == 0 ? SENT_TO_SUPPLIERS : awaited == 0 ? QUOTES_COMPLETE : QUOTES_PARTIAL;
		}
	}

	@Override
	public void comparing(long rfqId) {
		Rfq rfq = rfqs.findById(rfqId).orElseThrow();
		if (rfq.status == QUOTES_PARTIAL || rfq.status == QUOTES_COMPLETE) {
			rfq.status = UNDER_COMPARISON;
		}
	}

	@Override
	public void sentToCustomer(long rfqId) {
		Rfq rfq = rfqs.findById(rfqId).orElseThrow();
		if (!OFFERING.contains(rfq.status)) {
			throw ApiException.conflict("A quote can go to the customer once quotes are in, and not while the customer "
					+ "is deciding on an earlier one");
		}
		rfq.status = SENT_TO_CUSTOMER;
	}

	@Override
	public void customerResponded(long rfqId, CustomerResponse response) {
		Rfq rfq = rfqs.findById(rfqId).orElseThrow();
		if (rfq.status != SENT_TO_CUSTOMER) {
			throw ApiException.conflict("This RFQ is no longer waiting for your answer");
		}
		rfq.status = switch (response) {
			case APPROVED -> CUSTOMER_APPROVED; // appears in the ready-for-NPD queue; never converted automatically
			case REVISION_REQUESTED -> CUSTOMER_REVISION_REQUESTED;
			case DECLINED -> CUSTOMER_DECLINED;
		};
	}

	@Override
	@Transactional(readOnly = true)
	public List<ApprovedRfq> customerApproved() {
		CurrentUser me = CurrentUser.get();
		if (!ModuleAccess.canView(me.role(), Module.RFQ)) {
			return List.of(); // a hidden RFQ module (switch or sheet) is closed here too
		}
		List<Rfq> approved = switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ, ERGO_NPD ->
				rfqs.findByStatusInOrderByIdDesc(Set.of(CUSTOMER_APPROVED));
			case ERGO_KAM -> {
				Set<Long> accounts = directory.accountsOf(me.userId());
				yield accounts.isEmpty() ? List.of()
						: rfqs.findByStatusInOrderByIdDesc(Set.of(CUSTOMER_APPROVED)).stream()
							.filter(r -> accounts.contains(r.customerCompanyId))
							.toList();
			}
			default -> List.of(); // no RFQ visibility for this role, so nothing to convert either
		};
		return approved.stream()
			.map(r -> new ApprovedRfq(r.id, r.number, r.customerCompanyId, r.partNumber, r.revision, r.description,
					r.updatedAt))
			.toList();
	}

	/** CUSTOMER_APPROVED is excluded: {@link #customerApproved} already counts it separately. */
	private static final Set<RfqStatus> DASHBOARD_EXCLUDED = EnumSet.of(CLOSED, CANCELLED, CUSTOMER_DECLINED,
			CUSTOMER_APPROVED);

	@Override
	@Transactional(readOnly = true)
	public List<RfqDashboardItem> dashboardItems() {
		CurrentUser me = CurrentUser.get();
		List<Rfq> visible = switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ -> rfqs.findAllByOrderByIdDesc();
			case ERGO_KAM -> {
				Set<Long> accounts = directory.accountsOf(me.userId());
				yield accounts.isEmpty() ? List.of() : rfqs.findByCustomerCompanyIdInOrderByIdDesc(accounts);
			}
			case CUSTOMER_RFQ, CUSTOMER_KAM, CUSTOMER_MANAGEMENT, CUSTOMER_NPD, CUSTOMER_QUALITY ->
				rfqs.findByCustomerCompanyIdOrderByIdDesc(me.companyId());
			default -> List.of(); // suppliers (no invitation data here) and ERGO_NPD (dashboard-relevant RFQs are
									// CUSTOMER_APPROVED only, which DASHBOARD_EXCLUDED drops anyway)
		};
		boolean ergo = me.companyType() == CompanyType.ERGO;
		List<Rfq> open = visible.stream().filter(r -> !DASHBOARD_EXCLUDED.contains(r.status)).toList();
		Map<Long, CompanyRef> customers = ergo ? directory.companies(ids(open, r -> r.customerCompanyId)) : Map.of();
		LocalDate today = LocalDate.now();
		return open.stream()
			.map(r -> new RfqDashboardItem(r.id, r.number, ergo ? companyName(customers, r.customerCompanyId) : null,
					ergo ? r.status.name() : r.status.forCustomer().name(), ergo ? awaiting(r.status) : null,
					r.targetQuoteDate, r.targetQuoteDate != null && r.targetQuoteDate.isBefore(today),
					r.priority.name()))
			.toList();
	}

	/** This module's own judgment call on whose turn it is to act (ARCHITECTURE.md §8, 2026-10-05 Phase 7c) - the
	 * SRS does not define it. Statuses in {@link #DASHBOARD_EXCLUDED} never reach here. */
	private static Awaiting awaiting(RfqStatus status) {
		return switch (status) {
			case DRAFT, SUBMITTED, UNDER_REVIEW, CLARIFICATION_REQUIRED, VALIDATED, QUOTES_COMPLETE, UNDER_COMPARISON,
					CUSTOMER_REVISION_REQUESTED ->
				Awaiting.ERGO;
			case SENT_TO_SUPPLIERS, QUOTES_PARTIAL -> Awaiting.SUPPLIER;
			case SENT_TO_CUSTOMER -> Awaiting.CUSTOMER;
			default -> Awaiting.NONE;
		};
	}

	private static String companyName(Map<Long, CompanyRef> names, Long id) {
		CompanyRef ref = id == null ? null : names.get(id);
		return ref == null ? null : ref.name();
	}

	private static Set<Long> ids(Collection<Rfq> rfqs, Function<Rfq, Long> field) {
		return rfqs.stream().map(field).filter(Objects::nonNull).collect(Collectors.toSet());
	}

	private static String name(Map<Long, Named> names, Long id) {
		Named named = id == null ? null : names.get(id);
		return named == null ? null : named.name();
	}

}
