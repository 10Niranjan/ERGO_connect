package com.ergo.connect.npd.api;

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

}
