package com.ergo.connect.rfq.domain;

import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_REVISION_REQUESTED;
import static com.ergo.connect.rfq.domain.RfqStatus.QUOTES_COMPLETE;
import static com.ergo.connect.rfq.domain.RfqStatus.QUOTES_PARTIAL;
import static com.ergo.connect.rfq.domain.RfqStatus.SENT_TO_SUPPLIERS;
import static com.ergo.connect.rfq.domain.RfqStatus.UNDER_COMPARISON;
import static com.ergo.connect.rfq.domain.RfqStatus.VALIDATED;

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
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.documents.api.DocumentStore;
import com.ergo.connect.documents.api.DocumentStore.DocumentInfo;
import com.ergo.connect.documents.api.DocumentStore.Visibility;
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

	private final RfqRepository rfqs;

	private final RfqService rfqService;

	private final MasterDataApi masterData;

	private final DocumentStore documents;

	RfqApiService(RfqRepository rfqs, RfqService rfqService, MasterDataApi masterData, DocumentStore documents) {
		this.rfqs = rfqs;
		this.rfqService = rfqService;
		this.masterData = masterData;
		this.documents = documents;
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
				INVITING.contains(r.status), QUOTING.contains(r.status));
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

	private static Set<Long> ids(Collection<Rfq> rfqs, Function<Rfq, Long> field) {
		return rfqs.stream().map(field).filter(Objects::nonNull).collect(Collectors.toSet());
	}

	private static String name(Map<Long, Named> names, Long id) {
		Named named = id == null ? null : names.get(id);
		return named == null ? null : named.name();
	}

}
