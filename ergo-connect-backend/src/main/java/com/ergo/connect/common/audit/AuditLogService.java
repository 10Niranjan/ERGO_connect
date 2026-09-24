package com.ergo.connect.common.audit;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
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

	private final JdbcTemplate jdbc;

	AuditLogService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Explicit business event, e.g. ROLE_ADDED on a user. Module and entity names come from the entity class. */
	public void record(Class<?> entity, long recordId, String eventType, String field, String oldValue,
			String newValue) {
		Optional<CurrentUser> actor = CurrentUser.find();
		HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
				? a.getRequest() : null;
		jdbc.update("""
				INSERT INTO audit_logs (module_name, entity_name, record_id, event_type, field_name, old_value, new_value,
				                        changed_by_user_id, changed_by_role_code, changed_by_company_id, source_channel,
				                        ip_address)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				moduleOf(entity), entity.getSimpleName(), recordId, eventType, field, oldValue, newValue,
				actor.map(CurrentUser::userId).orElse(null), actor.map(u -> u.role().name()).orElse(null),
				actor.map(CurrentUser::companyId).orElse(null), channel(request),
				request == null ? null : request.getRemoteAddr());
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
