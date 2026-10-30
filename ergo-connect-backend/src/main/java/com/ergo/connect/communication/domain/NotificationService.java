package com.ergo.connect.communication.domain;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ergo.connect.common.event.NotificationEvent;
import com.ergo.connect.common.event.Recipients;
import com.ergo.connect.common.event.Recipients.CompanyRoles;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.ModuleSwitch;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.communication.api.NotificationType;
import com.ergo.connect.communication.domain.NotificationCatalog.Spec;
import com.ergo.connect.communication.domain.NotificationDtos.MarkedRead;
import com.ergo.connect.communication.domain.NotificationDtos.NotificationPage;
import com.ergo.connect.communication.domain.NotificationDtos.NotificationView;
import com.ergo.connect.communication.domain.NotificationDtos.UnreadCount;
import com.ergo.connect.documents.api.DocumentsApi;
import com.ergo.connect.masterdata.api.Directory;
import com.ergo.connect.masterdata.api.Directory.CompanyRef;
import com.ergo.connect.masterdata.api.Directory.UserRef;

/**
 * In-app notifications (SRS §13.7.4, §12.12.2, §14.9), Phase 6e. Business modules publish a {@link NotificationEvent}
 * (they cannot call this module directly: it calls back into every one of them to check record scope); this service
 * turns it into one row per recipient after the publishing transaction commits.
 * <p>
 * <b>Rules (client decisions, ARCHITECTURE.md §8 2026-10-03 "Phase 6e"):</b>
 * <ul>
 * <li>In-app only; email and push are later channels. Every row is written for exactly one recipient and is readable
 * only by that recipient.
 * <li>The acting user is never told about their own action; repeats are not merged.
 * <li>A recipient must be an active user who can read the record <i>at send time</i> - the owning module's own record
 * scope, run as that person in each role the publisher targeted (or any role they hold, for an explicit user) - and
 * belong to a side the event's catalog entry names. So a stale or over-broad target (a mention, an assignee who has
 * since lost access, a reassigned customer) notifies nobody rather than disclosing a record.
 * <li>Wording comes from {@link NotificationCatalog}, per audience; the publisher supplies only the record's own label
 * and a short neutral detail.
 * <li>Notifications are derived, system-generated rows and are not audited row by row; the audited business change
 * that caused each one is the record of what happened.
 * <li>A failure here is logged and swallowed: notifying must never undo or block the business operation.
 * </ul>
 */
@Service
public class NotificationService {

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private static final int MAX_PAGE_SIZE = 100;

	private static final int MAX_MESSAGE = 500;

	private static final RowMapper<NotificationView> VIEW = (rs, n) -> new NotificationView(
			rs.getLong("notification_id"), NotificationType.valueOf(rs.getString("notification_type")),
			rs.getString("title"), rs.getString("message"), rs.getString("action_url"), rs.getString("linked_module"),
			rs.getLong("linked_record_id"), rs.getBoolean("read_flag"),
			rs.getObject("sent_at", OffsetDateTime.class).toInstant(),
			rs.getObject("read_at", OffsetDateTime.class) == null ? null
					: rs.getObject("read_at", OffsetDateTime.class).toInstant());

	/** One person an event may reach, with the roles to test the record scope under. */
	private record Candidate(UserRef user, CompanyRef company, Set<Role> roles) {
	}

	private final JdbcTemplate jdbc;

	private final Directory directory;

	private final RecordScope scope;

	private final DocumentsApi documentsApi;

	NotificationService(JdbcTemplate jdbc, Directory directory, RecordScope scope, DocumentsApi documentsApi) {
		this.jdbc = jdbc;
		this.directory = directory;
		this.scope = scope;
		this.documentsApi = documentsApi;
	}

	// ---------------------------------------------------------------- delivery

	/** After the publisher's transaction commits (or immediately, when it had none: the scheduled reminders). Its own
	 * new transaction, because the publisher's is already finished. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onEvent(NotificationEvent event) {
		try {
			deliver(event);
		}
		catch (RuntimeException e) {
			log.error("Could not deliver {} notification for {} {}", event.kind(), event.linkedModule(),
					event.linkedRecordId(), e);
		}
	}

	void deliver(NotificationEvent event) {
		Spec spec = NotificationCatalog.of(event.kind());
		if (spec == null) {
			return;
		}
		// Module switches: a notification files under the module its record belongs to. A hidden module notifies
		// nobody (it would expose that module's activity); read-only still delivers, it is information not a change.
		Module module;
		try {
			module = "document".equals(event.linkedModule())
					? documentsApi.owningModule(event.linkedRecordId()).orElse(null)
					: ModuleSwitch.ownerOf(event.linkedModule()).orElse(null);
		}
		catch (RuntimeException e) {
			module = null; // unfiled (e.g. the document is gone): delivery's own record-scope check still decides
		}
		if (module != null && !ModuleSwitch.readable(module)) {
			return;
		}
		String message = message(event);
		Map<String, Boolean> scopeCache = new HashMap<>();
		List<Object[]> rows = new java.util.ArrayList<>();
		for (Candidate c : candidates(event.recipients()).values()) {
			CompanyType side = c.company().type();
			if (c.user().id() == (event.actorUserId() == null ? -1L : event.actorUserId())
					|| !spec.audiences().contains(side) || !canRead(c, event, scopeCache)) {
				continue;
			}
			rows.add(new Object[] { spec.type().name(), event.kind().name(), event.linkedModule(),
					event.linkedRecordId(), c.user().id(), spec.titleFor(side), message,
					RecordScope.actionUrl(event.linkedModule(), event.linkedRecordId()), event.dedupeKey(),
					event.actorUserId(), module == null ? null : module.name() });
		}
		if (!rows.isEmpty()) {
			jdbc.batchUpdate("""
					INSERT INTO notifications (notification_type, event_kind, linked_module, linked_record_id,
					                           recipient_user_id, title, message, action_url, dedupe_key,
					                           triggered_by_user_id, module_code)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""", rows);
		}
	}

	private static String message(NotificationEvent e) {
		String label = e.label() == null ? "" : e.label();
		String text = e.detail() == null || e.detail().isBlank() ? label : label + " - " + e.detail();
		return text.length() > MAX_MESSAGE ? text.substring(0, MAX_MESSAGE) : text;
	}

	/** Does this person - in any of the roles the event targeted them under - currently read the record? Every
	 * module's record scope depends only on the role (ERGO) or the role and company (customers, suppliers), except an
	 * ERGO KAM's (their own assigned accounts), so the answer is shared per event instead of asked once per person. */
	private boolean canRead(Candidate c, NotificationEvent event, Map<String, Boolean> cache) {
		for (Role role : c.roles()) {
			String key = role + (c.company().type() == CompanyType.ERGO ? "" : ":" + c.user().companyId())
					+ (role == Role.ERGO_KAM ? ":" + c.user().id() : "");
			boolean readable = cache.computeIfAbsent(key, k -> {
				CurrentUser as = new CurrentUser(c.user().id(), c.user().companyId(), c.company().type(), role, 0L);
				return CurrentUser.runAs(as, () -> "document".equals(event.linkedModule())
						? documentsApi.documentVisibleToCaller(event.linkedRecordId())
						: scope.parties(event.linkedModule(), event.linkedRecordId()).isPresent());
			});
			if (readable) {
				return true;
			}
		}
		return false;
	}

	/** Expands the publisher's request into active users, each with the roles to test (union across targets). */
	private Map<Long, Candidate> candidates(Recipients to) {
		Map<Long, Set<Role>> roles = new LinkedHashMap<>();
		Map<Long, UserRef> users = new LinkedHashMap<>();

		if (!to.userIds().isEmpty()) {
			Map<Long, UserRef> named = directory.activeUsers(to.userIds());
			Map<Long, Set<Role>> held = directory.rolesOf(named.keySet());
			for (UserRef u : named.values()) {
				add(users, roles, u, held.getOrDefault(u.id(), Set.of()));
			}
		}
		if (!to.ergoRoles().isEmpty()) {
			addStaff(users, roles, directory.activeUsersOfType(CompanyType.ERGO), to.ergoRoles());
		}
		for (CompanyRoles company : to.companies()) {
			addStaff(users, roles, directory.activeUsersOf(company.companyId()), company.roles());
		}

		Map<Long, CompanyRef> companies = directory
			.companies(users.values().stream().map(UserRef::companyId).collect(Collectors.toSet()));
		Map<Long, Candidate> result = new LinkedHashMap<>();
		for (UserRef u : users.values()) {
			CompanyRef company = companies.get(u.companyId());
			if (company != null && !roles.get(u.id()).isEmpty()) {
				result.put(u.id(), new Candidate(u, company, roles.get(u.id())));
			}
		}
		return result;
	}

	/** Those of {@code staff} holding at least one of {@code wanted} (empty = any role), tested under those roles. */
	private void addStaff(Map<Long, UserRef> users, Map<Long, Set<Role>> roles, List<UserRef> staff, Set<Role> wanted) {
		Map<Long, Set<Role>> held = directory.rolesOf(staff.stream().map(UserRef::id).toList());
		for (UserRef u : staff) {
			Set<Role> mine = new HashSet<>(held.getOrDefault(u.id(), Set.of()));
			if (!wanted.isEmpty()) {
				mine.retainAll(wanted);
			}
			if (!mine.isEmpty()) {
				add(users, roles, u, mine);
			}
		}
	}

	private static void add(Map<Long, UserRef> users, Map<Long, Set<Role>> roles, UserRef user, Collection<Role> toTest) {
		users.putIfAbsent(user.id(), user);
		roles.computeIfAbsent(user.id(), id -> new HashSet<>()).addAll(toTest);
	}

	// ---------------------------------------------------------------- the recipient's own drawer

	@Transactional(readOnly = true)
	public NotificationPage list(boolean unreadOnly, NotificationType type, int page, int size) {
		long me = CurrentUser.get().userId();
		int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		int pageNo = Math.max(page, 0);
		String typeName = type == null ? null : type.name();
		Long total = jdbc.queryForObject("""
				SELECT count(*) FROM notifications WHERE recipient_user_id = ?
				  AND (? = false OR read_flag = false) AND (?::text IS NULL OR notification_type = ?)"""
				+ notHidden(), Long.class, me, unreadOnly, typeName, typeName);
		List<NotificationView> items = jdbc.query("""
				SELECT * FROM notifications WHERE recipient_user_id = ?
				  AND (? = false OR read_flag = false) AND (?::text IS NULL OR notification_type = ?)"""
				+ notHidden() + " ORDER BY sent_at DESC, notification_id DESC LIMIT ? OFFSET ?", VIEW, me, unreadOnly,
				typeName, typeName, pageSize, (long) pageNo * pageSize);
		return new NotificationPage(items, pageNo, pageSize, total == null ? 0 : total, unread(me));
	}

	@Transactional(readOnly = true)
	public UnreadCount unreadCount() {
		return new UnreadCount(unread(CurrentUser.get().userId()));
	}

	/** Idempotent. Someone else's notification does not exist for the caller (404, not 403). */
	public NotificationView markRead(long id) {
		if (CurrentUser.get().impersonated()) { // an unaudited write: the admin must not clear the real user's alerts
			throw ApiException.forbidden("Not allowed while logged in as another user.");
		}
		long me = CurrentUser.get().userId();
		jdbc.update("""
				UPDATE notifications SET read_flag = true, read_at = now()
				WHERE notification_id = ? AND recipient_user_id = ? AND read_flag = false""" + notHidden(), id, me);
		return jdbc.query("SELECT * FROM notifications WHERE notification_id = ? AND recipient_user_id = ?" + notHidden(),
				VIEW, id, me)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Notification"));
	}

	public MarkedRead markAllRead() {
		if (CurrentUser.get().impersonated()) { // an unaudited write: the admin must not clear the real user's alerts
			throw ApiException.forbidden("Not allowed while logged in as another user.");
		}
		long me = CurrentUser.get().userId();
		return new MarkedRead(jdbc.update("""
				UPDATE notifications SET read_flag = true, read_at = now()
				WHERE recipient_user_id = ? AND read_flag = false""" + notHidden(), me));
	}

	private long unread(long userId) {
		Long count = jdbc.queryForObject(
				"SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND read_flag = false" + notHidden(),
				Long.class, userId);
		return count == null ? 0 : count;
	}

	/**
	 * Module switches: notifications of a module switched to NV are kept but invisible - not listed, not counted, not
	 * markable as read (so their unread state survives) - and reappear unchanged when the module is switched back on.
	 * The names spliced in are the fixed module codes, never user input.
	 */
	private static String notHidden() {
		List<Module> hidden = ModuleSwitch.hidden();
		return hidden.isEmpty() ? ""
				: " AND (module_code IS NULL OR module_code NOT IN ("
						+ hidden.stream().map(m -> "'" + m.name() + "'").collect(Collectors.joining(",")) + "))";
	}

}
