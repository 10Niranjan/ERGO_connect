package com.ergo.connect.common.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.ergo.connect.common.security.CurrentUser;

/**
 * What a module calls to say "someone should hear about this". Deliberately a thin wrapper over Spring's event
 * publisher living in {@code common}: the module that owns the record cannot depend on the communication module (which
 * itself calls back into every module to check who may read a record), so the two only meet through this event.
 */
@Component
public class Notifier {

	private final ApplicationEventPublisher publisher;

	Notifier(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	/** The acting user (if any) is excluded from the recipients automatically. */
	public void send(NotificationKind kind, String linkedModule, long linkedRecordId, String label, String detail,
			Recipients to) {
		publisher.publishEvent(new NotificationEvent(kind, linkedModule, linkedRecordId, label, detail,
				CurrentUser.find().map(CurrentUser::userId).orElse(null), to, null));
	}

	/** For the scheduled overdue scan: no actor, and at most one notification per recipient per {@code dedupeKey}. */
	public void sendScheduled(NotificationKind kind, String linkedModule, long linkedRecordId, String label,
			String detail, Recipients to, String dedupeKey) {
		publisher.publishEvent(
				new NotificationEvent(kind, linkedModule, linkedRecordId, label, detail, null, to, dedupeKey));
	}

}
