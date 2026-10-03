package com.ergo.connect.communication.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.communication.domain.NotificationDtos.MarkedRead;
import com.ergo.connect.communication.domain.NotificationDtos.NotificationPage;
import com.ergo.connect.communication.domain.NotificationDtos.NotificationView;
import com.ergo.connect.communication.domain.NotificationDtos.UnreadCount;
import com.ergo.connect.communication.domain.NotificationService;

/**
 * SRS §12.12.2 Notifications Drawer. Every role has one (SRS §10.10 gives every role communication access); a user
 * only ever sees and changes their own notifications - the service filters by the signed-in user in every query, so
 * there is no record-scope question to answer here and another user's id is simply a 404.
 */
@RestController
@RequestMapping("/api/notifications")
class NotificationController {

	private static final String EVERY_ROLE = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_KAM', 'ERGO_RFQ', "
			+ "'ERGO_NPD', 'ERGO_OFP', 'ERGO_QUALITY', 'CUSTOMER_RFQ', 'CUSTOMER_NPD', 'CUSTOMER_OFP', "
			+ "'CUSTOMER_QUALITY', 'CUSTOMER_KAM', 'CUSTOMER_MANAGEMENT', 'SUPPLIER_RFQ', 'SUPPLIER_NPD', "
			+ "'SUPPLIER_OFP', 'SUPPLIER_QUALITY', 'SUPPLIER_KAM')";

	private final NotificationService notifications;

	NotificationController(NotificationService notifications) {
		this.notifications = notifications;
	}

	@GetMapping
	@PreAuthorize(EVERY_ROLE)
	NotificationPage list(@RequestParam(defaultValue = "false") boolean unreadOnly,
			@RequestParam(required = false) NotificationType type, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return notifications.list(unreadOnly, type, page, size);
	}

	@GetMapping("/unread-count")
	@PreAuthorize(EVERY_ROLE)
	UnreadCount unreadCount() {
		return notifications.unreadCount();
	}

	@PostMapping("/{id}/read")
	@PreAuthorize(EVERY_ROLE)
	NotificationView markRead(@PathVariable long id) {
		return notifications.markRead(id);
	}

	@PostMapping("/read-all")
	@PreAuthorize(EVERY_ROLE)
	MarkedRead markAllRead() {
		return notifications.markAllRead();
	}

}
