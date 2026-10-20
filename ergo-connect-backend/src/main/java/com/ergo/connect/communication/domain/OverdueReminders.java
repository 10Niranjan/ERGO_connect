package com.ergo.connect.communication.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

import com.ergo.connect.common.event.ModuleSwitchChanged;
import com.ergo.connect.common.event.Notifier;
import com.ergo.connect.common.event.OverdueItem;
import com.ergo.connect.common.security.AccessPolicy;
import com.ergo.connect.npd.api.NpdApi;
import com.ergo.connect.quality.api.QualityApi;
import com.ergo.connect.quotation.api.QuotationApi;

/**
 * SRS §14.9 "overdue activity": once a day, each module reports what is past its due date and the owners are reminded
 * (NPD activities, supplier quote responses, NCR target dates, CAPA action dates). A reminder repeats daily until the
 * item is dealt with, but a recipient gets at most one per item per day (the dedupe key), so a second run - another
 * server instance, a restart, a manual re-run - tells nobody twice. No user is signed in, so the scan reads each
 * module's facade without a caller scope; delivery still re-checks every recipient against the record.
 */
@Component
public class OverdueReminders {

	private static final Logger log = LoggerFactory.getLogger(OverdueReminders.class);

	private final NpdApi npdApi;

	private final QuotationApi quotationApi;

	private final QualityApi qualityApi;

	private final Notifier notifier;

	OverdueReminders(NpdApi npdApi, QuotationApi quotationApi, QualityApi qualityApi, Notifier notifier) {
		this.npdApi = npdApi;
		this.quotationApi = quotationApi;
		this.qualityApi = qualityApi;
		this.notifier = notifier;
	}

	@Scheduled(cron = "${ergo.communication.overdue-cron:0 0 6 * * *}")
	public void daily() {
		scan(LocalDate.now());
	}

	/**
	 * A module that was read-only or hidden is not scanned (each module's facade reports nothing then; see {@code
	 * ModuleSwitch}), so its reminders pause. The scan is stateless and a recipient gets at most one reminder per item
	 * per day, so when the module accepts changes again this catch-up run sends exactly what is due, once, instead of
	 * waiting for the next morning.
	 */
	@TransactionalEventListener(fallbackExecution = true)
	public void onSwitchChanged(ModuleSwitchChanged change) {
		if (!change.reopened()) {
			return;
		}
		// Its own thread: the admin's request keeps its pinned (pre-change) access snapshot and its identity, and a
		// reminder run must see the new switch and act as the system, exactly like the 06:00 run.
		Thread.ofVirtual().name("overdue-catch-up").start(() -> {
			try {
				AccessPolicy.syncNow(); // the switch that just committed, not the snapshot from before it
				scan(LocalDate.now());
			}
			catch (RuntimeException e) {
				log.error("Catch-up overdue scan after {} was switched back on failed", change.module(), e);
			}
		});
	}

	/** Returns how many overdue items were found (each may notify several people). */
	public int scan(LocalDate today) {
		List<OverdueItem> items = new ArrayList<>();
		collect(items, "NPD", () -> npdApi.overdueItems(today));
		collect(items, "quotation", () -> quotationApi.overdueItems(today));
		collect(items, "quality", () -> qualityApi.overdueItems(today));
		for (OverdueItem item : items) {
			// One failed delivery must not cost the remaining items their reminder (security review 6e L2).
			try {
				notifier.sendScheduled(item.kind(), item.linkedModule(), item.recordId(), item.label(), item.detail(),
						item.recipients(), item.kind() + ":" + item.itemKey() + ":" + today);
			}
			catch (RuntimeException e) {
				log.error("Overdue reminder failed for {} {}", item.kind(), item.itemKey(), e);
			}
		}
		return items.size();
	}

	/** One module failing must not stop the others being reminded. */
	private static void collect(List<OverdueItem> into, String module, java.util.function.Supplier<List<OverdueItem>> source) {
		try {
			into.addAll(source.get());
		}
		catch (RuntimeException e) {
			log.error("Overdue scan failed for {}", module, e);
		}
	}

}
