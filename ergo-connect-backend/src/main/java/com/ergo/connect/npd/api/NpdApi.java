package com.ergo.connect.npd.api;

import java.util.Optional;

/**
 * What other modules may ask of the NPD module. Phase 4a: the OFP module's "Handover to OFP" action reads an NPD
 * project's own handover data through here (ofp depends on npd.api, the same established direction npd itself used
 * to depend on rfq.api/quotation.api — the later-built module reaches into the earlier one, never the reverse).
 */
public interface NpdApi {

	/** What OFP needs to create an order from an NPD project: the customer/supplier the project itself never
	 * exposes outside ERGO, its part identity and its own source RFQ (for the order's own linkage). */
	record HandoverProject(long id, long customerCompanyId, long supplierCompanyId, long sourceRfqId,
			String partNumber, String revision) {
	}

	/**
	 * The project's handover data, for an ERGO caller only (404 otherwise, or if the project doesn't exist);
	 * 409 if this project has already been handed over to OFP (SRS's "Handover to OFP" is a one-time action per
	 * project, ARCHITECTURE.md §8 2026-09-28). Marks the project handed over as part of the same call, so this
	 * and the order it feeds are created atomically in the caller's own transaction.
	 */
	HandoverProject handover(long npdProjectId);

	/** Just the project's own company columns, no state change (unlike {@link #handover}) - what a module needs to
	 * derive/verify company ownership server-side (Quality's security review M5, ARCHITECTURE.md §8) rather than
	 * trusting a client-sent company id for an inspection linked to this project. Read is unrestricted by caller
	 * type: the consuming module (e.g. Quality) does its own authorization with the result (an ERGO caller uses it
	 * to derive both companies; a supplier caller uses it only to verify the project is its own, and is rejected
	 * before the value is ever returned to an HTTP response). Empty if the project doesn't exist. */
	Optional<ProjectRef> projectRef(long npdProjectId);

	/** Both companies of the project if the current caller may see it (NpdService's own record scope); empty
	 * otherwise. Used by the communication module (ARCHITECTURE.md §8, Phase 6c). */
	Optional<com.ergo.connect.common.record.RecordParties> partiesVisibleToCaller(long npdProjectId);

	/** Activities past their planned date and not done, for the daily reminder scan (no caller scope: the scan runs
	 * without a user, and delivery re-checks every recipient). */
	java.util.List<com.ergo.connect.common.event.OverdueItem> overdueItems(java.time.LocalDate today);

	record ProjectRef(long id, long customerCompanyId, long supplierCompanyId) {
	}

}
