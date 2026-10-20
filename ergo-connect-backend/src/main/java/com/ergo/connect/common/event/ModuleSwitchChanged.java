package com.ergo.connect.common.event;

/**
 * An ERGO Admin changed a module's switch (O/A/V/NV), published once the change has committed. Background jobs that
 * were paused while a module was read-only or hidden listen for it to catch up straight away instead of waiting for
 * their next scheduled run (overdue reminders, deviation expiry). Nothing is queued: each job is stateless and
 * selects by date, so a missed run loses no work, this only makes it resume sooner.
 *
 * @param module the module code ({@code RFQ}, {@code QUOTATION}, {@code NPD}, {@code OFP}, {@code QUALITY})
 */
public record ModuleSwitchChanged(String module, String from, String to) {

	/** True when the module went from refusing changes (V, NV) to accepting them (A, O). */
	public boolean reopened() {
		return accepts(to) && !accepts(from);
	}

	private static boolean accepts(String cap) {
		return "A".equals(cap) || "O".equals(cap);
	}

}
