package com.ergo.connect.dashboard.service;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.record.Awaiting;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.dashboard.service.DashboardView.CustomerDashboard;
import com.ergo.connect.dashboard.service.DashboardView.FunctionalDashboard;
import com.ergo.connect.dashboard.service.DashboardView.ManagementDashboard;
import com.ergo.connect.dashboard.service.DashboardView.SupplierDashboard;
import com.ergo.connect.dashboard.service.DashboardView.SupplierPerformanceRow;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.npd.api.NpdApi;
import com.ergo.connect.npd.api.NpdApi.NpdDashboardItem;
import com.ergo.connect.ofp.api.OfpApi;
import com.ergo.connect.ofp.api.OfpApi.OrderDashboardItem;
import com.ergo.connect.ofp.api.OfpApi.SupplierPerformance;
import com.ergo.connect.quality.api.QualityApi;
import com.ergo.connect.quality.api.QualityApi.DeviationDashboardItem;
import com.ergo.connect.quality.api.QualityApi.NcrDashboardItem;
import com.ergo.connect.quality.api.QualityApi.SupplierNcrCount;
import com.ergo.connect.quotation.api.QuotationApi;
import com.ergo.connect.rfq.api.RfqApi;
import com.ergo.connect.rfq.api.RfqApi.RfqDashboardItem;

/** SRS §12.5/§12.10.1 dashboards (Phase 7c). No business logic of its own (ARCHITECTURE.md §2): every field comes
 * from an owning module's {@code .api} facade, already scoped to the caller by that module. */
@Service
@Transactional(readOnly = true)
public class DashboardService {

	private final RfqApi rfqApi;

	private final QuotationApi quotationApi;

	private final NpdApi npdApi;

	private final OfpApi ofpApi;

	private final QualityApi qualityApi;

	private final Directory directory;

	public DashboardService(RfqApi rfqApi, QuotationApi quotationApi, NpdApi npdApi, OfpApi ofpApi,
			QualityApi qualityApi, Directory directory) {
		this.rfqApi = rfqApi;
		this.quotationApi = quotationApi;
		this.npdApi = npdApi;
		this.ofpApi = ofpApi;
		this.qualityApi = qualityApi;
		this.directory = directory;
	}

	/** No {@code default} branch: every one of the 18 roles is named explicitly (SRS §10.11, every role gets View),
	 * so a future role added to {@link com.ergo.connect.common.security.Role} without updating this switch is a
	 * compile error here, not a silent fall-through into the wrong audience (security review, ARCHITECTURE.md §8
	 * 2026-10-05 Phase 7c, L3). */
	public DashboardView view() {
		return switch (CurrentUser.get().role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT -> management();
			case ERGO_KAM, ERGO_RFQ, ERGO_NPD, ERGO_OFP, ERGO_QUALITY -> functional();
			case CUSTOMER_RFQ, CUSTOMER_NPD, CUSTOMER_OFP, CUSTOMER_QUALITY, CUSTOMER_KAM, CUSTOMER_MANAGEMENT ->
				customer();
			case SUPPLIER_RFQ, SUPPLIER_NPD, SUPPLIER_OFP, SUPPLIER_QUALITY, SUPPLIER_KAM -> supplier();
		};
	}

	private ManagementDashboard management() {
		List<RfqDashboardItem> rfq = rfqApi.dashboardItems();
		List<NpdDashboardItem> npd = npdApi.dashboardItems();
		List<OrderDashboardItem> orders = ofpApi.dashboardItems();
		List<NcrDashboardItem> ncrs = qualityApi.ncrDashboardItems();
		List<DeviationDashboardItem> deviations = qualityApi.deviationDashboardItems();

		int awaitingCustomerApproval = (int) rfq.stream().filter(r -> "SENT_TO_CUSTOMER".equals(r.status())).count();
		List<NpdDashboardItem> delayed = npd.stream().filter(p -> p.delayDays() != null && p.delayDays() > 0).toList();
		List<NpdDashboardItem> highRisk = npd.stream().filter(p -> "HIGH".equals(p.riskLevel())).toList();
		List<OrderDashboardItem> atRisk = orders.stream().filter(OrderDashboardItem::atRisk).toList();

		int pendingApprovals = npdApi.pendingApprovalsCount() + directory.pendingCompanyApprovalsCount()
				+ (int) rfq.stream().filter(r -> "SUBMITTED".equals(r.status()) || "UNDER_REVIEW".equals(r.status())).count()
				+ (int) ncrs.stream().filter(n -> "PENDING_CLOSURE_APPROVAL".equals(n.status())).count()
				+ (int) deviations.stream()
					.filter(d -> "UNDER_REVIEW".equals(d.status()) || "CUSTOMER_APPROVAL_PENDING".equals(d.status()))
					.count();

		return new ManagementDashboard(rfq.size(), quotationApi.pendingSubmissionCount(), awaitingCustomerApproval,
				npdApi.unconvertedApprovedRfqCount(), npd.size(), delayed, highRisk, atRisk, ncrs.size(),
				deviations.size(), supplierPerformance(), pendingApprovals);
	}

	/** Combines OFP's per-supplier dispatch timeliness with Quality's per-supplier open-NCR count. Both facade
	 * calls already return empty for any caller without unrestricted visibility. */
	private List<SupplierPerformanceRow> supplierPerformance() {
		List<SupplierPerformance> ofp = ofpApi.supplierPerformance();
		java.util.Map<Long, Integer> openNcrBySupplier = qualityApi.openNcrCountsBySupplier().stream()
			.collect(java.util.stream.Collectors.toMap(SupplierNcrCount::supplierCompanyId,
					SupplierNcrCount::openNcrCount));
		return ofp.stream()
			.map(o -> new SupplierPerformanceRow(o.supplierCompanyId(), o.supplierName(), o.openOrderCount(),
					openNcrBySupplier.getOrDefault(o.supplierCompanyId(), 0), o.dispatchedOrderCount(),
					o.onTimeDispatchedCount()))
			.toList();
	}

	private FunctionalDashboard functional() {
		List<RfqDashboardItem> rfq = rfqApi.dashboardItems();
		List<NpdDashboardItem> npd = npdApi.dashboardItems();
		List<OrderDashboardItem> orders = ofpApi.dashboardItems();
		List<NcrDashboardItem> ncrs = qualityApi.ncrDashboardItems();
		List<DeviationDashboardItem> deviations = qualityApi.deviationDashboardItems();

		int overdue = (int) (rfq.stream().filter(RfqDashboardItem::overdue).count()
				+ npd.stream().filter(p -> p.delayDays() != null && p.delayDays() > 0).count()
				+ orders.stream().filter(OrderDashboardItem::atRisk).count()
				+ ncrs.stream().filter(NcrDashboardItem::overdue).count());

		Stream<Awaiting> all = Stream.of(rfq.stream().map(RfqDashboardItem::awaiting),
				npd.stream().map(NpdDashboardItem::awaiting), orders.stream().map(OrderDashboardItem::awaiting),
				ncrs.stream().map(NcrDashboardItem::awaiting), deviations.stream().map(DeviationDashboardItem::awaiting))
			.flatMap(Function.identity());
		List<Awaiting> awaitingValues = all.toList();
		// The caller is always ERGO here (functional() only serves ERGO_KAM/RFQ/NPD/OFP/QUALITY): "me" = ERGO,
		// "external" = the customer or supplier side.
		int awaitingMe = (int) awaitingValues.stream().filter(a -> a == Awaiting.ERGO).count();
		int awaitingExternal = (int) awaitingValues.stream()
			.filter(a -> a == Awaiting.CUSTOMER || a == Awaiting.SUPPLIER)
			.count();

		return new FunctionalDashboard(rfq, npd, orders, ncrs, deviations, overdue, awaitingMe, awaitingExternal);
	}

	private CustomerDashboard customer() {
		return new CustomerDashboard(rfqApi.dashboardItems(), npdApi.dashboardItems(), ofpApi.dashboardItems(),
				qualityApi.ncrDashboardItems());
	}

	private SupplierDashboard supplier() {
		return new SupplierDashboard(quotationApi.dashboardItems(), npdApi.dashboardItems(), ofpApi.dashboardItems(),
				qualityApi.ncrDashboardItems());
	}

}
