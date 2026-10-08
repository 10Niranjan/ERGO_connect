package com.ergo.connect.common.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.ergo.connect.common.security.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Writes audit_logs rows (SRS §15.3) inside the caller's transaction, so an audit row exists exactly when the change
 * commits. Actor, active role, company and channel are always taken from the request, never from the caller.
 */
@Service
public class AuditLogService {

	private static final String BASE_PACKAGE = "com.ergo.connect.";

	private static final Object REASON = new Object();

	/** One row of a record's audit trail (SRS §15.5): when, who, in which role, what changed and why. */
	public record AuditEntry(Instant at, String user, String role, String event, String field, String oldValue,
			String newValue, String reason, String channel) {
	}

	private final JdbcTemplate jdbc;

	AuditLogService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Why the current transaction changes what it changes (approval comment, rejection or reopen reason). Stored on
	 * every audit row the transaction writes, including the automatic field changes flushed at commit.
	 */
	public void reason(String reason) {
		if (TransactionSynchronizationManager.hasResource(REASON)) {
			TransactionSynchronizationManager.unbindResource(REASON);
		}
		else {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					TransactionSynchronizationManager.unbindResourceIfPossible(REASON);
				}
			});
		}
		TransactionSynchronizationManager.bindResource(REASON, reason);
	}

	/** Explicit business event, e.g. ROLE_ADDED on a user. Module and entity names come from the entity class. */
	public void record(Class<?> entity, long recordId, String eventType, String field, String oldValue,
			String newValue) {
		record(moduleOf(entity), entity.getSimpleName(), recordId, eventType, field, oldValue, newValue);
	}

	/** For records written without JPA (e.g. master lists), which the automatic entity listener cannot see. */
	public void record(String module, String entity, long recordId, String eventType, String field, String oldValue,
			String newValue) {
		HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
				? a.getRequest() : null;
		insert(module, entity, recordId, eventType, field, oldValue, newValue, channel(request),
				request == null ? null : request.getRemoteAddr());
	}

	/**
	 * For a write made off the request thread (e.g. an {@code @Async} handler) - {@code RequestContextHolder} is
	 * empty there, so the caller must capture the request's own ip/channel on the request thread, before
	 * dispatching, and pass them through explicitly.
	 */
	public void record(Class<?> entity, long recordId, String eventType, String field, String oldValue,
			String newValue, String ip, String channel) {
		insert(moduleOf(entity), entity.getSimpleName(), recordId, eventType, field, oldValue, newValue, channel, ip);
	}

	/** Same channel/ip rule {@link #channel(HttpServletRequest)} applies, exposed for the async case above. */
	public static String channelOf(HttpServletRequest request) {
		return channel(request);
	}

	private void insert(String module, String entity, long recordId, String eventType, String field, String oldValue,
			String newValue, String channel, String ip) {
		Optional<CurrentUser> actor = CurrentUser.find();
		jdbc.update("""
				INSERT INTO audit_logs (module_name, entity_name, record_id, event_type, field_name, old_value, new_value,
				                        action_reason, changed_by_user_id, changed_by_role_code, changed_by_company_id,
				                        source_channel, ip_address)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				module, entity, recordId, eventType, field, oldValue, newValue,
				TransactionSynchronizationManager.getResource(REASON),
				actor.map(CurrentUser::userId).orElse(null), actor.map(u -> u.role().name()).orElse(null),
				actor.map(CurrentUser::companyId).orElse(null), channel, ip);
	}

	/** The audit trail of one record, oldest first. Callers decide who may read it. */
	public List<AuditEntry> history(String module, String entity, long recordId) {
		return jdbc.query("""
				SELECT a.changed_at, u.first_name || ' ' || u.last_name AS user_name, a.changed_by_role_code, a.event_type,
				       a.field_name, a.old_value, a.new_value, a.action_reason, a.source_channel
				FROM audit_logs a LEFT JOIN users u ON u.user_id = a.changed_by_user_id
				WHERE a.module_name = ? AND a.entity_name = ? AND a.record_id = ?
				ORDER BY a.audit_log_id""", (rs, n) -> new AuditEntry(rs.getObject(1, OffsetDateTime.class).toInstant(),
				rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
				rs.getString(8), rs.getString(9)), module, entity, recordId);
	}

	private static String moduleOf(Class<?> entity) {
		String name = entity.getName();
		return name.startsWith(BASE_PACKAGE) ? name.substring(BASE_PACKAGE.length()).split("\\.")[0] : "unknown";
	}

	/** SYSTEM for background work; UI for our web client (informational only, set by the frontend); else API. */
	private static String channel(HttpServletRequest request) {
		if (request == null) {
			return "SYSTEM";
		}
		return "web".equals(request.getHeader("X-Ergo-Client")) ? "UI" : "API";
	}

}
