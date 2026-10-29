package com.ergo.connect.dashboard.service;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.record.Awaiting;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.ModuleAccess;
import com.ergo.connect.common.security.ModuleAccess.Module;
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
import com.ergo.connect.quotation.api.QuotationApi.PartStatusItem;
import com.ergo.connect.quotation.api.QuotationApi.SupplierQuoteItem;
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

	private final int maxRows;

	public DashboardService(RfqApi rfqApi, QuotationApi quotationApi, NpdApi npdApi, OfpApi ofpApi,
			QualityApi qualityApi, Directory directory, @Value("${ergo.dashboard.max-rows:25}") int maxRows) {
		this.maxRows = maxRows;
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

	/** A module's widgets are built only for a role that can open that module (Permission Management can set any role to
	 * NV on it): an NV role gets no counts, rows or titles from it. */
	private static <T> List<T> items(Module module, Supplier<List<T>> source) {
		return ModuleAccess.canView(CurrentUser.get().role(), module) ? source.get() : List.of();
	}

	private static int count(Module module, IntSupplier source) {
		return ModuleAccess.canView(CurrentUser.get().role(), module) ? source.getAsInt() : 0;
	}

	/** The viewer's own side, for "waiting on me" in {@link AttentionOrder}. */
	private static Awaiting mine() {
		return switch (CurrentUser.get().companyType()) {
			case ERGO -> Awaiting.ERGO;
			case CUSTOMER -> Awaiting.CUSTOMER;
			case SUPPLIER -> Awaiting.SUPPLIER;
		};
	}

	/** The rows a section keeps: what needs attention first, at most {@code ergo.dashboard.max-rows} of them. */
	private <T> List<T> top(List<T> all, Comparator<T> order) {
		return all.size() <= maxRows ? all.stream().sorted(order).toList() : all.stream().sorted(order).limit(maxRows).toList();
	}

	private List<RfqDashboardItem> topRfq(List<RfqDashboardItem> all) {
		return top(all, AttentionOrder.of(RfqDashboardItem::overdue, RfqDashboardItem::awaiting,
				RfqDashboardItem::targetQuoteDate, RfqDashboardItem::id, mine()));
	}

	private List<NpdDashboardItem> topNpd(List<NpdDashboardItem> all) {
		return top(all, AttentionOrder.of(p -> p.delayDays() != null && p.delayDays() > 0, NpdDashboardItem::awaiting,
				NpdDashboardItem::targetSampleDate, NpdDashboardItem::id, mine()));
	}

	private List<OrderDashboardItem> topOrders(List<OrderDashboardItem> all) {
		return top(all, AttentionOrder.of(OrderDashboardItem::atRisk, OrderDashboardItem::awaiting,
				OrderDashboardItem::committedDispatchDate, OrderDashboardItem::id, mine()));
	}

	private List<NcrDashboardItem> topNcrs(List<NcrDashboardItem> all) {
		return top(all, AttentionOrder.of(NcrDashboardItem::overdue, NcrDashboardItem::awaiting,
				NcrDashboardItem::targetClosureDate, NcrDashboardItem::id, mine()));
	}

	private List<DeviationDashboardItem> topDeviations(List<DeviationDashboardItem> all) {
		return top(all, AttentionOrder.of(d -> false, DeviationDashboardItem::awaiting,
				DeviationDashboardItem::validityEndDate, DeviationDashboardItem::id, mine()));
	}

	private List<SupplierQuoteItem> topQuotes(List<SupplierQuoteItem> all) {
		return top(all, AttentionOrder.of(SupplierQuoteItem::overdue, q -> Awaiting.SUPPLIER,
				SupplierQuoteItem::responseDueDate, SupplierQuoteItem::invitationId, mine()));
	}

	/** Parts carry no due date or owner: newest RFQ first, parts in their own order. */
	private List<PartStatusItem> topParts(List<PartStatusItem> all) {
		return top(all, Comparator.comparingLong(PartStatusItem::rfqId).reversed().thenComparingLong(PartStatusItem::rfqItemId));
	}

	private ManagementDashboard management() {
		List<RfqDashboardItem> rfq = items(Module.RFQ, rfqApi::dashboardItems);
		List<NpdDashboardItem> npd = items(Module.NPD, npdApi::dashboardItems);
		List<OrderDashboardItem> orders = items(Module.OFP, ofpApi::dashboardItems);
		List<NcrDashboardItem> ncrs = items(Module.QUALITY, qualityApi::ncrDashboardItems);
		List<DeviationDashboardItem> deviations = items(Module.QUALITY, qualityApi::deviationDashboardItems);

		int awaitingCustomerApproval = (int) rfq.stream()
			.filter(r -> "SENT_TO_CUSTOMER".equals(r.status()) || "CUSTOMER_ON_HOLD".equals(r.status()))
			.count();
		List<NpdDashboardItem> delayed = npd.stream().filter(p -> p.delayDays() != null && p.delayDays() > 0).toList();
		List<NpdDashboardItem> highRisk = npd.stream().filter(p -> "HIGH".equals(p.riskLevel())).toList();
		List<OrderDashboardItem> atRisk = orders.stream().filter(OrderDashboardItem::atRisk).toList();

		int pendingApprovals = count(Module.NPD, npdApi::pendingApprovalsCount) + directory.pendingCompanyApprovalsCount()
				+ (int) rfq.stream().filter(r -> "SUBMITTED".equals(r.status()) || "UNDER_REVIEW".equals(r.status())).count()
				+ (int) ncrs.stream().filter(n -> "PENDING_CLOSURE_APPROVAL".equals(n.status())).count()
				+ (int) deviations.stream()
					.filter(d -> "UNDER_REVIEW".equals(d.status()) || "CUSTOMER_APPROVAL_PENDING".equals(d.status()))
					.count();

		List<PartStatusItem> parts = items(Module.QUOTATION, quotationApi::partStatusItems);
		return new ManagementDashboard(rfq.size(), count(Module.QUOTATION, quotationApi::pendingSubmissionCount), awaitingCustomerApproval,
				count(Module.NPD, npdApi::samplePosAwaitingCount), npd.size(), topNpd(delayed), delayed.size(),
				topNpd(highRisk), highRisk.size(), topOrders(atRisk), atRisk.size(), ncrs.size(), deviations.size(),
				supplierPerformance(), pendingApprovals, topParts(parts), parts.size());
	}

	/** Combines OFP's per-supplier dispatch timeliness with Quality's per-supplier open-NCR count. Both facade
	 * calls already return empty for any caller without unrestricted visibility. */
	private List<SupplierPerformanceRow> supplierPerformance() {
		List<SupplierPerformance> ofp = items(Module.OFP, ofpApi::supplierPerformance);
		java.util.Map<Long, Integer> openNcrBySupplier = items(Module.QUALITY, qualityApi::openNcrCountsBySupplier).stream()
			.collect(java.util.stream.Collectors.toMap(SupplierNcrCount::supplierCompanyId,
					SupplierNcrCount::openNcrCount));
		return ofp.stream()
			.map(o -> new SupplierPerformanceRow(o.supplierCompanyId(), o.supplierName(), o.openOrderCount(),
					openNcrBySupplier.getOrDefault(o.supplierCompanyId(), 0), o.dispatchedOrderCount(),
					o.onTimeDispatchedCount()))
			.toList();
	}

	private FunctionalDashboard functional() {
		List<RfqDashboardItem> rfq = items(Module.RFQ, rfqApi::dashboardItems);
		List<NpdDashboardItem> npd = items(Module.NPD, npdApi::dashboardItems);
		List<OrderDashboardItem> orders = items(Module.OFP, ofpApi::dashboardItems);
		List<NcrDashboardItem> ncrs = items(Module.QUALITY, qualityApi::ncrDashboardItems);
		List<DeviationDashboardItem> deviations = items(Module.QUALITY, qualityApi::deviationDashboardItems);

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

		List<PartStatusItem> parts = items(Module.QUOTATION, quotationApi::partStatusItems);
		return new FunctionalDashboard(topRfq(rfq), rfq.size(), topNpd(npd), npd.size(), topOrders(orders), orders.size(),
				topNcrs(ncrs), ncrs.size(), topDeviations(deviations), deviations.size(), overdue, awaitingMe,
				awaitingExternal, topParts(parts), parts.size());
	}

	private CustomerDashboard customer() {
		List<RfqDashboardItem> rfq = items(Module.RFQ, rfqApi::dashboardItems);
		List<NpdDashboardItem> npd = items(Module.NPD, npdApi::dashboardItems);
		List<OrderDashboardItem> orders = items(Module.OFP, ofpApi::dashboardItems);
		List<NcrDashboardItem> ncrs = items(Module.QUALITY, qualityApi::ncrDashboardItems);
		List<PartStatusItem> parts = items(Module.QUOTATION, quotationApi::partStatusItems);
		return new CustomerDashboard(topRfq(rfq), rfq.size(), topNpd(npd), npd.size(), topOrders(orders), orders.size(),
				topNcrs(ncrs), ncrs.size(), topParts(parts), parts.size());
	}

	private SupplierDashboard supplier() {
		List<SupplierQuoteItem> quotes = items(Module.QUOTATION, quotationApi::dashboardItems);
		List<NpdDashboardItem> npd = items(Module.NPD, npdApi::dashboardItems);
		List<OrderDashboardItem> orders = items(Module.OFP, ofpApi::dashboardItems);
		List<NcrDashboardItem> ncrs = items(Module.QUALITY, qualityApi::ncrDashboardItems);
		return new SupplierDashboard(topQuotes(quotes), quotes.size(), topNpd(npd), npd.size(), topOrders(orders),
				orders.size(), topNcrs(ncrs), ncrs.size());
	}

}
