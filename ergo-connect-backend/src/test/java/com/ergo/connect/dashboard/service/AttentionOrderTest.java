package com.ergo.connect.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ergo.connect.common.record.Awaiting;

/** What a capped dashboard section keeps first (Phase 8o): urgent, then waiting on the viewer, then soonest, then newest. */
class AttentionOrderTest {

	record Item(long id, boolean urgent, Awaiting awaiting, LocalDate due) {
	}

	static final LocalDate D = LocalDate.of(2026, 11, 1);

	Comparator<Item> order(Awaiting mine) {
		return AttentionOrder.of(Item::urgent, Item::awaiting, Item::due, Item::id, mine);
	}

	List<Long> ids(Awaiting mine, Item... items) {
		return List.of(items).stream().sorted(order(mine)).map(Item::id).toList();
	}

	@Test
	void urgentBeatsEverythingElseEvenWithALaterDate() {
		assertThat(ids(Awaiting.ERGO,
				new Item(1, false, Awaiting.ERGO, D),
				new Item(2, true, Awaiting.CUSTOMER, D.plusDays(90))))
			.containsExactly(2L, 1L);
	}

	@Test
	void waitingOnTheViewerComesBeforeWaitingOnSomeoneElse() {
		var other = new Item(1, false, Awaiting.CUSTOMER, D);
		var mine = new Item(2, false, Awaiting.ERGO, D.plusDays(30));
		assertThat(ids(Awaiting.ERGO, other, mine)).containsExactly(2L, 1L);
		assertThat(ids(Awaiting.CUSTOMER, other, mine)).containsExactly(1L, 2L); // same data, a customer's view
	}

	@Test
	void soonestDateThenNewestAndNoDateLast() {
		assertThat(ids(Awaiting.ERGO,
				new Item(1, false, Awaiting.NONE, null),
				new Item(2, false, Awaiting.NONE, D.plusDays(5)),
				new Item(3, false, Awaiting.NONE, D),
				new Item(4, false, Awaiting.NONE, D)))
			.containsExactly(4L, 3L, 2L, 1L); // D twice: the newer id (4) first; then +5; the undated one last
	}

}
