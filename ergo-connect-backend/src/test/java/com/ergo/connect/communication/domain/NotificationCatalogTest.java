package com.ergo.connect.communication.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ergo.connect.common.event.NotificationKind;
import com.ergo.connect.common.security.CompanyType;

/** Structural guarantees of the notification catalog: every event is defined, worded only for the sides it names, and
 * external wording never refers to the other external party or to ERGO staff. */
class NotificationCatalogTest {

	@Test
	void everyKindHasAnEntryWithWordingForExactlyItsAudiences() {
		for (NotificationKind kind : NotificationKind.values()) {
			NotificationCatalog.Spec spec = NotificationCatalog.of(kind);
			assertNotNull(spec, kind + " has no catalog entry");
			assertFalse(spec.audiences().isEmpty(), kind + " has no audience");
			boolean ergo = spec.audiences().contains(CompanyType.ERGO);
			boolean external = spec.audiences().contains(CompanyType.CUSTOMER)
					|| spec.audiences().contains(CompanyType.SUPPLIER);
			if (ergo) {
				assertNotNull(spec.ergoTitle(), kind + " needs ERGO wording");
			}
			else {
				assertNull(spec.ergoTitle(), kind + " has ERGO wording but no ERGO audience");
			}
			if (external) {
				assertNotNull(spec.externalTitle(), kind + " needs external wording");
			}
			else {
				assertNull(spec.externalTitle(), kind + " has external wording but no external audience");
			}
		}
	}

	@Test
	void externalWordingNeverNamesTheOtherSideOrErgoStaff() {
		for (NotificationKind kind : NotificationKind.values()) {
			NotificationCatalog.Spec spec = NotificationCatalog.of(kind);
			if (spec.externalTitle() == null) {
				continue;
			}
			String title = spec.externalTitle().toLowerCase();
			if (spec.audiences().contains(CompanyType.CUSTOMER)) {
				assertFalse(title.contains("supplier"), kind + ": a customer must not be told about suppliers");
			}
			if (spec.audiences().contains(CompanyType.SUPPLIER)) {
				assertFalse(title.contains("customer"), kind + ": a supplier must not be told about customers");
			}
			assertFalse(title.matches(".*\\b(admin|manager|kam)\\b.*"), kind + ": no ERGO staff names or roles");
		}
	}

	@Test
	void everyEventMapsToADrawerTypeFromTheSrs() {
		for (NotificationKind kind : NotificationKind.values()) {
			assertTrue(NotificationCatalog.of(kind).type().name().matches(
					"ACTION_REQUIRED|OVERDUE_REMINDER|APPROVAL_PENDING|RESPONSE_RECEIVED|DOCUMENT_UPLOADED|RECORD_UPDATED"));
		}
	}

}
