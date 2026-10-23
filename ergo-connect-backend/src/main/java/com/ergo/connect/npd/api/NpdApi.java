package com.ergo.connect.npd.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.ergo.connect.common.record.Awaiting;

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

	/**
	 * SRS §12.5.1/§12.5.2 dashboards: the caller's own in-scope, still-open (not CLOSED) NPD projects - same scope
	 * as {@code NpdService.list()}/{@code myProjects()} (ERGO's full-viewer/KAM roles, or the caller's own company
	 * as customer/supplier). {@code customerName}/{@code supplierName} are null for a customer/supplier caller
	 * (never reveal the other party). {@link Awaiting} and "delayed" are this module's own judgment call
	 * (ARCHITECTURE.md §8, 2026-10-05): not SRS-defined fields.
	 */
	record NpdDashboardItem(long id, String npdNumber, String customerName, String supplierName, String stage,
			Awaiting awaiting, LocalDate targetSampleDate, Long delayDays, String riskLevel) {
	}

	List<NpdDashboardItem> dashboardItems();

	/** Management and KAM dashboards: Sample POs submitted by customers that wait for the KAM's decision (the KAM sees
	 * only its assigned accounts', Management all; 0 for every other role). */
	int samplePosAwaitingCount();

	/** The customer of a Sample PO if the current caller may see it (SamplePoService's own record scope); empty otherwise.
	 * A Sample PO has no supplier, so only the customer side is returned. Used by the communication module for notifications. */
	Optional<com.ergo.connect.common.record.RecordParties> samplePoPartiesVisibleToCaller(long samplePoId);

	/** ERGO Management's "Pending approvals" widget (§12.5.1): count of every {@code PENDING} approval across every
	 * NPD project, for the unrestricted-view roles only (ERGO Admin/Management/RFQ/NPD) - 0 for everyone else,
	 * including ERGO KAM, since this widget is Management-only and not part of any functional/KAM dashboard. */
	int pendingApprovalsCount();

}
