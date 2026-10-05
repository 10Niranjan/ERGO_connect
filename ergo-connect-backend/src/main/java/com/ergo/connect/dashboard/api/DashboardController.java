package com.ergo.connect.dashboard.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.dashboard.service.DashboardService;
import com.ergo.connect.dashboard.service.DashboardView;

/** SRS §10.11: every role gets View (no Create/Edit/Submit/Approve for anyone) - one read-only endpoint, shaped
 * per caller by {@link DashboardService}. */
@RestController
class DashboardController {

	private static final String EVERY_ROLE = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_KAM', 'ERGO_RFQ', "
			+ "'ERGO_NPD', 'ERGO_OFP', 'ERGO_QUALITY', 'CUSTOMER_RFQ', 'CUSTOMER_NPD', 'CUSTOMER_OFP', "
			+ "'CUSTOMER_QUALITY', 'CUSTOMER_KAM', 'CUSTOMER_MANAGEMENT', 'SUPPLIER_RFQ', 'SUPPLIER_NPD', "
			+ "'SUPPLIER_OFP', 'SUPPLIER_QUALITY', 'SUPPLIER_KAM')";

	private final DashboardService dashboard;

	DashboardController(DashboardService dashboard) {
		this.dashboard = dashboard;
	}

	@GetMapping("/api/dashboard")
	@PreAuthorize(EVERY_ROLE)
	DashboardView view() {
		return dashboard.view();
	}

}
