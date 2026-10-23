package com.ergo.connect.dashboard.service;

import java.util.List;

import com.ergo.connect.npd.api.NpdApi.NpdDashboardItem;
import com.ergo.connect.ofp.api.OfpApi.OrderDashboardItem;
import com.ergo.connect.quality.api.QualityApi.DeviationDashboardItem;
import com.ergo.connect.quality.api.QualityApi.NcrDashboardItem;
import com.ergo.connect.quotation.api.QuotationApi.PartStatusItem;
import com.ergo.connect.quotation.api.QuotationApi.SupplierQuoteItem;
import com.ergo.connect.rfq.api.RfqApi.RfqDashboardItem;

/**
 * SRS §12.5/§12.10.1: one response shape per dashboard audience, assembled purely by calling each owning module's
 * {@code .api} facade (ARCHITECTURE.md §2: "no business logic here"). No module of its own: every field is a count
 * or a list another module already computed and scoped for the caller.
 */
public sealed interface DashboardView {

	/** Mirrors the discriminator convention other modules already use (e.g. {@code NpdView}'s own {@code audience}
	 * field) so the frontend can tell the four shapes apart structurally without duck-typing. A stored record
	 * component, not a derived method: Jackson only serializes a record's own canonical components. */
	enum Audience {
		MANAGEMENT, FUNCTIONAL, CUSTOMER, SUPPLIER
	}

	Audience audience();

	/** §12.5.1 "Supplier performance summary" (built as a simple metric, ARCHITECTURE.md §8 2026-10-05): combines
	 * OFP's dispatch timeliness with Quality's open-NCR count for the same supplier. */
	record SupplierPerformanceRow(long supplierCompanyId, String supplierName, int openOrderCount, int openNcrCount,
			int dispatchedOrderCount, int onTimeDispatchedCount) {
	}

	/** §12.5.1 ERGO Management Dashboard. ERGO Admin and ERGO Management only (§10.11 unrestricted "V"). */
	record ManagementDashboard(Audience audience, int openRfqCount, int quotesPendingSubmissionCount,
			int quotesAwaitingCustomerApprovalCount, int approvedForConversionCount, int activeNpdCount,
			List<NpdDashboardItem> delayedNpdProjects, List<NpdDashboardItem> highRiskNpdProjects,
			List<OrderDashboardItem> ordersAtRisk, int openNcrCount, int openDeviationCount,
			List<SupplierPerformanceRow> supplierPerformance, int pendingApprovalsCount,
			List<PartStatusItem> partItems) implements DashboardView {

		public ManagementDashboard(int openRfqCount, int quotesPendingSubmissionCount,
				int quotesAwaitingCustomerApprovalCount, int approvedForConversionCount, int activeNpdCount,
				List<NpdDashboardItem> delayedNpdProjects, List<NpdDashboardItem> highRiskNpdProjects,
				List<OrderDashboardItem> ordersAtRisk, int openNcrCount, int openDeviationCount,
				List<SupplierPerformanceRow> supplierPerformance, int pendingApprovalsCount,
				List<PartStatusItem> partItems) {
			this(Audience.MANAGEMENT, openRfqCount, quotesPendingSubmissionCount, quotesAwaitingCustomerApprovalCount,
					approvedForConversionCount, activeNpdCount, delayedNpdProjects, highRiskNpdProjects, ordersAtRisk,
					openNcrCount, openDeviationCount, supplierPerformance, pendingApprovalsCount, partItems);
		}
	}

	/**
	 * §12.5.2 ERGO Functional Dashboards: one shape for all five roles. A single-module role (RFQ/NPD/OFP/Quality)
	 * populates only its own module's list, the rest empty; ERGO KAM populates every list (SRS §8.2/§11.1: KAM sees
	 * cross-functional data, scoped to its assigned accounts) since every underlying facade call already scopes
	 * ERGO_KAM to {@code directory.accountsOf} on its own. {@code awaitingMeCount}/{@code awaitingExternalCount}
	 * split "own pending actions" from "items waiting for external response" (§12.5.2) - this module's own judgment
	 * call (ARCHITECTURE.md §8, 2026-10-05), computed from each item's own {@code Awaiting} field.
	 */
	record FunctionalDashboard(Audience audience, List<RfqDashboardItem> rfqItems, List<NpdDashboardItem> npdItems,
			List<OrderDashboardItem> orderItems, List<NcrDashboardItem> ncrItems,
			List<DeviationDashboardItem> deviationItems, int overdueCount, int awaitingMeCount,
			int awaitingExternalCount, List<PartStatusItem> partItems) implements DashboardView {

		public FunctionalDashboard(List<RfqDashboardItem> rfqItems, List<NpdDashboardItem> npdItems,
				List<OrderDashboardItem> orderItems, List<NcrDashboardItem> ncrItems,
				List<DeviationDashboardItem> deviationItems, int overdueCount, int awaitingMeCount,
				int awaitingExternalCount, List<PartStatusItem> partItems) {
			this(Audience.FUNCTIONAL, rfqItems, npdItems, orderItems, ncrItems, deviationItems, overdueCount,
					awaitingMeCount, awaitingExternalCount, partItems);
		}
	}

	/** §12.5.3 Customer Dashboard. No supplier identity anywhere (every item's {@code supplierName} is already null
	 * for a customer caller, enforced by the owning module). "Pending documents/reviews" is not built (no suitable
	 * Documents facade method exists yet); "Shipment status" is approximated by order status rather than a
	 * separate per-shipment widget (ARCHITECTURE.md §8, 2026-10-05: both are this module's own simplification, not
	 * an SRS-given field). */
	record CustomerDashboard(Audience audience, List<RfqDashboardItem> rfqItems, List<NpdDashboardItem> npdItems,
			List<OrderDashboardItem> orderItems, List<NcrDashboardItem> ncrItems, List<PartStatusItem> partItems)
			implements DashboardView {

		public CustomerDashboard(List<RfqDashboardItem> rfqItems, List<NpdDashboardItem> npdItems,
				List<OrderDashboardItem> orderItems, List<NcrDashboardItem> ncrItems, List<PartStatusItem> partItems) {
			this(Audience.CUSTOMER, rfqItems, npdItems, orderItems, ncrItems, partItems);
		}
	}

	/** §12.5.4 Supplier Dashboard. {@code quoteItems} covers "Assigned RFQs"/"Quote due items" (quotation module,
	 * not RFQ - suppliers have no RFQ-module scope of their own). */
	record SupplierDashboard(Audience audience, List<SupplierQuoteItem> quoteItems, List<NpdDashboardItem> npdItems,
			List<OrderDashboardItem> orderItems, List<NcrDashboardItem> ncrItems) implements DashboardView {

		public SupplierDashboard(List<SupplierQuoteItem> quoteItems, List<NpdDashboardItem> npdItems,
				List<OrderDashboardItem> orderItems, List<NcrDashboardItem> ncrItems) {
			this(Audience.SUPPLIER, quoteItems, npdItems, orderItems, ncrItems);
		}
	}

}
