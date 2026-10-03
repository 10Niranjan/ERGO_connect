package com.ergo.connect.quality.domain;

import com.ergo.connect.common.event.Recipients;
import com.ergo.connect.common.security.Role;

/** Who to tell about a quality record (SRS §14.9). A request only: delivery re-checks each person against the record's
 * own scope - including the customerVisible / supplierVisible gates - so asking for a side that may not see the record
 * simply notifies nobody on that side. */
final class QualityNotices {

	private QualityNotices() {
	}

	static Recipients ergo() {
		return Recipients.ergo(Role.ERGO_ADMIN, Role.ERGO_QUALITY, Role.ERGO_MANAGEMENT);
	}

	static Recipients customer(Long companyId) {
		return companyId == null ? Recipients.none()
				: Recipients.company(companyId, Role.CUSTOMER_QUALITY, Role.CUSTOMER_KAM);
	}

	static Recipients supplier(Long companyId) {
		return companyId == null ? Recipients.none()
				: Recipients.company(companyId, Role.SUPPLIER_QUALITY, Role.SUPPLIER_KAM);
	}

	static Recipients everyone(Long customerCompanyId, Long supplierCompanyId) {
		return ergo().and(customer(customerCompanyId)).and(supplier(supplierCompanyId));
	}

}
