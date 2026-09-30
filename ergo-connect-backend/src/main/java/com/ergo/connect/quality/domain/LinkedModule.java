package com.ergo.connect.quality.domain;

/** SRS §13.6.1 {@code inspections.linked_module}: which kind of inspection this is / what it is checked against.
 * Polymorphic, no FK (V16 migration) — the target table varies (an NPD/OFP record, or none for a standalone
 * incoming/final/in-process inspection), matching {@code npd_activities.phase_name}'s free-text-against-a-varying-
 * target precedent. */
public enum LinkedModule {
	NPD, OFP, INCOMING, FINAL, IN_PROCESS
}
