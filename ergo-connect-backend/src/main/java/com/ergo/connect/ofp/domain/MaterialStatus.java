package com.ergo.connect.ofp.domain;

/** SRS §13.5.2 order_material_status.material_status; no SRS-given value list (self-decided, no access-control
 * impact, same footing as npd_issues_risks.severity/status in 3b). */
public enum MaterialStatus {
	PENDING, ORDERED, RECEIVED, DELAYED
}
