package com.ergo.connect.communication.domain;

import java.time.Instant;
import java.util.List;

import com.ergo.connect.communication.api.NotificationType;

/** SRS §12.12.2 Notifications Drawer DTOs. One shape for every audience: the text was already written for the
 * recipient's side when the notification was stored, and a user only ever receives its own rows. */
public final class NotificationDtos {

	private NotificationDtos() {
	}

	public record NotificationView(long id, NotificationType type, String title, String message, String actionUrl,
			String linkedModule, long linkedRecordId, boolean read, Instant sentAt, Instant readAt) {
	}

	public record NotificationPage(List<NotificationView> items, int page, int size, long totalElements,
			long unreadCount) {
	}

	public record UnreadCount(long count) {
	}

	public record MarkedRead(long marked) {
	}

}
