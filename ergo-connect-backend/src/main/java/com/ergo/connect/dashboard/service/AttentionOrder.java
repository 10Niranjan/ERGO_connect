package com.ergo.connect.dashboard.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

import com.ergo.connect.common.record.Awaiting;

/**
 * Which rows a capped dashboard section keeps (Phase 8o load-test fix): what needs attention first. Presentation only,
 * no business rule: an item that is already overdue / at risk comes first, then the ones waiting on the viewer's own side,
 * then the soonest due date, then the newest. Items with no due date sort after those that have one.
 */
final class AttentionOrder {

	private AttentionOrder() {
	}

	static <T> Comparator<T> of(Predicate<T> urgent, Function<T, Awaiting> awaiting, Function<T, LocalDate> due,
			ToLongFunction<T> id, Awaiting mine) {
		return Comparator.<T, Boolean>comparing(t -> !urgent.test(t))
			.thenComparing(t -> awaiting.apply(t) != mine)
			.thenComparing(due, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(Comparator.comparingLong(id).reversed());
	}

}
