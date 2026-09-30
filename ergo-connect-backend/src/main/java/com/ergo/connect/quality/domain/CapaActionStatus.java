package com.ergo.connect.quality.domain;

/** SRS §13.6.5 {@code capa_actions.status}: no SRS-enumerated workflow of its own (V18 migration comment) - a simple
 * per-action lifecycle underneath its parent NCR's own {@link NcrStatus#CAPA_IN_PROGRESS}. */
public enum CapaActionStatus {
	OPEN, IN_PROGRESS, CLOSED
}
