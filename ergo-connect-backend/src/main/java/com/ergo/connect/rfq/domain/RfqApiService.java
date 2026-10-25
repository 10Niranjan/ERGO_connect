package com.ergo.connect.rfq.domain;

import static com.ergo.connect.common.security.Role.CUSTOMER_KAM;
import static com.ergo.connect.common.security.Role.CUSTOMER_MANAGEMENT;
import static com.ergo.connect.common.security.Role.CUSTOMER_NPD;
import static com.ergo.connect.common.security.Role.CUSTOMER_QUALITY;
import static com.ergo.connect.common.security.Role.CUSTOMER_RFQ;
import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;
import static com.ergo.connect.common.security.Role.ERGO_NPD;
import static com.ergo.connect.common.security.Role.ERGO_RFQ;
import static com.ergo.connect.rfq.domain.RfqStatus.CANCELLED;
import static com.ergo.connect.rfq.domain.RfqStatus.CLOSED;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_APPROVED;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_DECLINED;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_ON_HOLD;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_REVISION_REQUESTED;
import static com.ergo.connect.rfq.domain.RfqStatus.QUOTES_COMPLETE;
import static com.ergo.connect.rfq.domain.RfqStatus.QUOTES_PARTIAL;
import static com.ergo.connect.rfq.domain.RfqStatus.SENT_TO_CUSTOMER;
import static com.ergo.connect.rfq.domain.RfqStatus.SENT_TO_SUPPLIERS;
import static com.ergo.connect.rfq.domain.RfqStatus.UNDER_REVIEW;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.record.Awaiting;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.ModuleSwitch;
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

	/** Parts can be sent to suppliers while ERGO reviews the RFQ and while it is out, until it is finished. */
	static final Set<RfqStatus> INVITING = EnumSet.of(UNDER_REVIEW, SENT_TO_SUPPLIERS, QUOTES_PARTIAL, QUOTES_COMPLETE,
			SENT_TO_CUSTOMER, CUSTOMER_ON_HOLD, CUSTOMER_REVISION_REQUESTED);

	/** What suppliers are told is "open": not closed or cancelled. The customer's decision on the RFQ never shows. */
	static final Set<RfqStatus> SUPPLIER_OPEN = EnumSet.of(SENT_TO_SUPPLIERS, QUOTES_PARTIAL, QUOTES_COMPLETE,
			SENT_TO_CUSTOMER, CUSTOMER_ON_HOLD, CUSTOMER_REVISION_REQUESTED, CUSTOMER_APPROVED, CUSTOMER_DECLINED);

	/** The RFQ is out: quote work on its parts is possible (each part's own status decides what). */
	static final Set<RfqStatus> QUOTING = EnumSet.of(SENT_TO_SUPPLIERS, QUOTES_PARTIAL, QUOTES_COMPLETE,
			SENT_TO_CUSTOMER, CUSTOMER_ON_HOLD, CUSTOMER_REVISION_REQUESTED);

	private final RfqRepository rfqs;

	private final RfqItemRepository items;

	private final RfqService rfqService;

	private final MasterDataApi masterData;

	private final DocumentStore documents;

	private final Directory directory;

	private final org.springframework.jdbc.core.JdbcTemplate jdbc;

	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager entityManager;

	RfqApiService(RfqRepository rfqs, RfqItemRepository items, RfqService rfqService, MasterDataApi masterData,
			DocumentStore documents, Directory directory, org.springframework.jdbc.core.JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		this.rfqs = rfqs;
		this.items = items;
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
		ModuleSwitch.requireReadable(Module.RFQ);
		Rfq r = rfqService.visible(me, rfqId);
		// A read-only RFQ module offers no quoting work: every flag below is what lets the quotation module act.
		boolean open = ModuleSwitch.writable(Module.RFQ);
		return new RfqRef(r.id, r.number, r.customerCompanyId, r.currencyId, r.paymentTermId,
				open && INVITING.contains(r.status), open && QUOTING.contains(r.status),
				items.findByRfqIdOrderByLineNo(r.id).stream().map(RfqApiService::itemRef).toList());
	}

	private static ItemRef itemRef(RfqItem i) {
		return new ItemRef(i.id, i.lineNo, i.partNumber, i.revision, i.description,
				new Targets(i.targetToolingCost, i.targetPpapPiecePrice, i.targetProductionPiecePrice, i.moq,
						i.materialLeadTimeWeeks, i.toolingLeadTimeDays, i.sampleLeadTimeDays,
						i.productionLeadTimeDays));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean visibleToCaller(long rfqId) {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() == CompanyType.SUPPLIER || !ModuleSwitch.readable(Module.RFQ)) {
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
		return ModuleSwitch.writable(Module.RFQ) && rfqService.uploadOpen(CurrentUser.get(), rfqId);
	}

	@Override
	@Transactional(readOnly = true)
	public CustomerRfqRef forCustomer(long rfqId) {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() != CompanyType.CUSTOMER) {
			throw ApiException.notFound("RFQ");
		}
		ModuleSwitch.requireReadable(Module.RFQ);
		Rfq r = rfqService.visible(me, rfqId);
		return new CustomerRfqRef(r.id, r.number, r.customerCompanyId,
				ModuleSwitch.writable(Module.RFQ) && (r.status == SENT_TO_CUSTOMER || r.status == CUSTOMER_ON_HOLD),
				items.findByRfqIdOrderByLineNo(r.id).stream()
					.map(i -> new CustomerItemRef(i.id, i.lineNo, i.partNumber, i.revision, i.description))
					.toList());
	}

	@Override
	@Transactional(readOnly = true)
	public String numberOf(long rfqId) {
		if (CurrentUser.get().companyType() != CompanyType.ERGO) {
			throw ApiException.notFound("RFQ"); // an external role reads its RFQ number only through forCustomer/forSupplierItem
		}
		ModuleSwitch.requireReadable(Module.RFQ);
		return rfqs.findById(rfqId).map(r -> r.number).orElseThrow(() -> ApiException.notFound("RFQ"));
	}

	@Override
	@Transactional(readOnly = true)
	public SupplierRfqItem forSupplierItem(long itemId) {
		SupplierRfqItem item = forSupplierItems(List.of(itemId)).get(itemId);
		if (item == null) {
			throw ApiException.notFound("RFQ");
		}
		return item;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, SupplierRfqItem> forSupplierItems(Collection<Long> itemIds) {
		if (itemIds.isEmpty() || !ModuleSwitch.readable(Module.RFQ)) {
			return Map.of();
		}
		boolean open = ModuleSwitch.writable(Module.RFQ);
		List<RfqItem> found = items.findByIdIn(itemIds);
		Map<Long, Rfq> owners = rfqs.findByIdIn(found.stream().map(i -> i.rfqId).collect(Collectors.toSet())).stream()
			.collect(Collectors.toMap(r -> r.id, Function.identity()));
		Map<Long, Named> materials = masterData.describe(Ref.MATERIAL,
				found.stream().map(i -> i.materialId).filter(Objects::nonNull).collect(Collectors.toSet()));
		Map<Long, Named> coatings = masterData.describe(Ref.COATING,
				found.stream().map(i -> i.coatingId).filter(Objects::nonNull).collect(Collectors.toSet()));
		Map<Long, Named> processes = masterData.describe(Ref.PROCESS, found.stream()
			.flatMap(i -> i.processSteps.stream().map(ProcessStep::processId)).filter(Objects::nonNull)
			.collect(Collectors.toSet()));
		return found.stream().collect(Collectors.toMap(i -> i.id, i -> {
			Rfq r = owners.get(i.rfqId);
			return new SupplierRfqItem(r.id, r.number, i.id, i.partNumber, i.revision, i.description, i.eau,
					i.netWeightKg, i.grossWeightKg, name(materials, i.materialId), i.materialSpec,
					name(coatings, i.coatingId), i.coatingSpec,
					i.processSteps.stream()
						.map(s -> new PlannedStep(s.processId(), name(processes, s.processId()), s.stepName()))
						.toList(),
					open && SUPPLIER_OPEN.contains(r.status));
		}));
	}

	@Override
	@Transactional(readOnly = true)
	public List<DocumentInfo> supplierDocuments(long rfqId, Collection<Long> itemIds) {
		if (itemIds.isEmpty() || !ModuleSwitch.readable(Module.RFQ)) {
			return List.of();
		}
		Set<Long> shared = new java.util.HashSet<>(jdbc.queryForList(
				"SELECT DISTINCT document_id FROM rfq_document_parts WHERE rfq_item_id = ANY (?)", Long.class,
				(Object) itemIds.toArray(Long[]::new)));
		return documents.list(RfqService.MODULE, rfqId).stream()
			.filter(d -> d.visibility() == Visibility.SUPPLIER || d.visibility() == Visibility.ALL)
			.filter(d -> shared.contains(d.id()))
			.toList();
	}

	@Override
	public void distributing(long rfqId) {
		ModuleSwitch.requireWritable(Module.RFQ); // a read-only or hidden RFQ module is not changed by another module either
		Rfq rfq = rfqs.findById(rfqId).orElseThrow();
		if (!INVITING.contains(rfq.status)) {
			throw ApiException.conflict("Parts can be sent to suppliers once ERGO is reviewing the RFQ");
		}
		RfqService.requireComplete(rfq, items.findByRfqIdOrderByLineNo(rfqId));
		if (rfq.status == UNDER_REVIEW) {
			rfq.status = SENT_TO_SUPPLIERS;
		}
	}

	/**
	 * Flow doc: each part has its own status; the RFQ's status summarises them. Decided parts (approved or rejected)
	 * count as done; while a quotation waits for the customer the RFQ is "sent to customer", then on hold, then
	 * correction required; otherwise it is "quotes in" once no part is unassigned or with a supplier.
	 */
	@Override
	public void quotationProgress(long rfqId, QuotationSummary s) {
		ModuleSwitch.requireWritable(Module.RFQ); // a read-only or hidden RFQ module is not changed by another module either
		jdbc.queryForList("SELECT rfq_id FROM rfqs WHERE rfq_id = ? FOR UPDATE", Long.class, rfqId); // one summary at a time
		Rfq rfq = rfqs.findById(rfqId).orElseThrow();
		// The copy loaded before the lock may predate a status another transaction committed while we waited for it;
		// unchanged-looking state would then never be written. Push our own changes, then re-read.
		entityManager.flush();
		entityManager.refresh(rfq);
		if (!QUOTING.contains(rfq.status)) {
			return;
		}
		rfq.status = derive(items.findByRfqIdOrderByLineNo(rfqId).size(), s);
	}

	static RfqStatus derive(int parts, QuotationSummary s) {
		int decided = s.approved() + s.rejected();
		int assigned = s.withSupplier() + s.underErgoReview() + s.accepted() + s.withCustomer() + s.onHold()
				+ s.correction() + decided;
		int unassigned = Math.max(0, parts - assigned);
		if (parts > 0 && decided == parts) {
			return s.approved() > 0 ? CUSTOMER_APPROVED : CUSTOMER_DECLINED;
		}
		if (s.withCustomer() > 0) {
			return SENT_TO_CUSTOMER;
		}
		if (s.onHold() > 0) {
			return CUSTOMER_ON_HOLD;
		}
		if (s.correction() > 0) {
			return CUSTOMER_REVISION_REQUESTED;
		}
		if (s.underErgoReview() + s.accepted() + decided + s.sentBack() == 0) {
			return SENT_TO_SUPPLIERS;
		}
		return s.withSupplier() + unassigned == 0 ? QUOTES_COMPLETE : QUOTES_PARTIAL;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ItemRef> itemForErgo(long itemId) {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() != CompanyType.ERGO || !ModuleSwitch.readable(Module.RFQ)) {
			return Optional.empty();
		}
		return items.findById(itemId)
			.filter(i -> me.role() == ERGO_NPD || rfqService.inScope(me, i.rfqId))
			.map(RfqApiService::itemRef);
	}

	@Override
	@Transactional(readOnly = true)
	public List<CustomerPart> customerParts() {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() != CompanyType.CUSTOMER || !ModuleSwitch.readable(Module.RFQ)) {
			return List.of();
		}
		List<Rfq> own = rfqs.findByCustomerCompanyIdOrderByIdDesc(me.companyId()).stream()
			.filter(r -> r.status != CANCELLED && r.status != CLOSED)
			.toList();
		if (own.isEmpty()) {
			return List.of();
		}
		Map<Long, Rfq> byId = own.stream().collect(Collectors.toMap(r -> r.id, Function.identity()));
		return items.findByRfqIdInOrderByRfqIdAscLineNoAsc(byId.keySet()).stream()
			.map(i -> new CustomerPart(i.rfqId, byId.get(i.rfqId).number, i.id, i.partNumber))
			.toList();
	}

	/** CUSTOMER_APPROVED is excluded: an approved RFQ has nothing left to do in RFQ. */
	private static final Set<RfqStatus> DASHBOARD_EXCLUDED = EnumSet.of(CLOSED, CANCELLED, CUSTOMER_DECLINED,
			CUSTOMER_APPROVED);

	@Override
	@Transactional(readOnly = true)
	public List<RfqDashboardItem> dashboardItems() {
		if (!ModuleSwitch.readable(Module.RFQ)) {
			return List.of();
		}
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
		Map<Long, List<String>> parts = open.isEmpty() ? Map.of()
				: items.findByRfqIdInOrderByRfqIdAscLineNoAsc(open.stream().map(r -> r.id).toList()).stream()
					.collect(Collectors.groupingBy(i -> i.rfqId,
							Collectors.mapping(i -> i.partNumber, Collectors.toList())));
		LocalDate today = LocalDate.now();
		return open.stream()
			.map(r -> new RfqDashboardItem(r.id, r.number, ergo ? companyName(customers, r.customerCompanyId) : null,
					ergo ? r.status.name() : r.status.forCustomer().name(), ergo ? awaiting(r.status) : null,
					r.targetQuoteDate, r.targetQuoteDate != null && r.targetQuoteDate.isBefore(today),
					r.priority.name(), parts.getOrDefault(r.id, List.of())))
			.toList();
	}

	/** This module's own judgment call on whose turn it is to act (ARCHITECTURE.md §8, 2026-10-05 Phase 7c) - the
	 * SRS does not define it. Statuses in {@link #DASHBOARD_EXCLUDED} never reach here. */
	private static Awaiting awaiting(RfqStatus status) {
		return switch (status) {
			case DRAFT, SUBMITTED, UNDER_REVIEW, CLARIFICATION_REQUIRED, QUOTES_COMPLETE, CUSTOMER_REVISION_REQUESTED ->
				Awaiting.ERGO;
			case SENT_TO_SUPPLIERS, QUOTES_PARTIAL -> Awaiting.SUPPLIER;
			case SENT_TO_CUSTOMER, CUSTOMER_ON_HOLD -> Awaiting.CUSTOMER;
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
