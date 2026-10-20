package com.ergo.connect.admin.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ergo.connect.admin.service.PermissionAdminDtos.Cell;
import com.ergo.connect.admin.service.PermissionAdminDtos.Change;
import com.ergo.connect.admin.service.PermissionAdminDtos.HistoryEntry;
import com.ergo.connect.admin.service.PermissionAdminDtos.Matrix;
import com.ergo.connect.admin.service.PermissionAdminDtos.ModuleColumn;
import com.ergo.connect.admin.service.PermissionAdminDtos.RoleRow;
import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.AccessPolicy;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.DefaultAccessSheet;
import com.ergo.connect.common.security.Role;

/**
 * Permission Management (ERGO Admin only; the controller enforces it): reads and edits the role-by-module access sheet
 * and the per-module switches. The server, not the screen, decides what is allowed - every rule below is checked on save:
 * <ul>
 * <li>only configurable modules, and only roles of the side being edited;</li>
 * <li>ERGO Admin stays view-only (NV or V) on every configurable module - it manages accounts, companies and these
 * permissions, but never creates or approves business records (separation of duties, client decision 2026-10-20);</li>
 * <li>every configurable module keeps at least one ERGO role at {@code O}, or nobody could give its final approval;</li>
 * <li>a reason, a matching version (someone else may have saved meanwhile), and one audit row per change.</li>
 * </ul>
 * A module switch is a platform-wide ceiling on top of the sheet (effective level = the lower of the two), so it can
 * pause or hide a module without touching what any role was given. Record scope (own company, assigned records) is
 * not touched by any level or switch, so no edit can widen whose data a role sees.
 */
@Service
public class PermissionAdminService {

	public static final List<String> LEVELS = List.of("NV", "V", "A", "O");

	private static final String ENTITY = "RoleModulePermission";

	private static final String MODULE_ENTITY = "ModuleSwitch";

	private final JdbcTemplate jdbc;

	private final AuditLogService audit;

	private final AccessPolicy policy;

	PermissionAdminService(JdbcTemplate jdbc, AuditLogService audit, AccessPolicy policy) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.policy = policy;
	}

	@Transactional(readOnly = true)
	public Matrix matrix(CompanyType side) {
		long version = jdbc.queryForObject("SELECT version FROM access_config", Long.class);
		List<ModuleColumn> modules = jdbc.query(
				"SELECT module_code, label, configurable, access_cap FROM app_modules ORDER BY sort_order",
				(rs, n) -> new ModuleColumn(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)));
		Map<Role, String> names = new EnumMap<>(Role.class);
		Map<Role, Boolean> active = new EnumMap<>(Role.class);
		jdbc.query("SELECT role_code, role_name, active FROM roles", rs -> {
			Role r = Role.valueOf(rs.getString(1));
			names.put(r, rs.getString(2));
			active.put(r, rs.getBoolean(3));
		});
		Map<String, String> current = currentLevels();
		List<RoleRow> rows = new ArrayList<>();
		for (Role role : Role.values()) {
			if (role.companyType() != side) {
				continue;
			}
			Map<String, Cell> cells = new LinkedHashMap<>();
			for (ModuleColumn m : modules) {
				String level = current.get(key(role, m.code()));
				String def = DefaultAccessSheet.code(role, m.code());
				cells.put(m.code(), new Cell(level, AccessPolicy.lower(level, m.cap()), def == null ? level : def,
						m.configurable(), m.configurable() ? allowed(role) : List.of(level)));
			}
			rows.add(new RoleRow(role, names.getOrDefault(role, role.name()), active.getOrDefault(role, true), cells));
		}
		return new Matrix(version, side.name(), LEVELS, modules, rows);
	}

	@Transactional
	public Matrix save(CompanyType side, long expectedVersion, String reason, List<Change> changes) {
		lockAndCheckVersion(expectedVersion);
		Map<String, String> proposed = currentLevels();
		Map<String, String> before = new HashMap<>(proposed);
		Set<String> configurable = configurableModules();
		for (Change c : changes) {
			String module = c.module();
			if (!configurable.contains(module)) {
				throw ApiException.unprocessable("Module " + module + " cannot be configured here");
			}
			if (c.role().companyType() != side) {
				throw ApiException.unprocessable(c.role() + " is not a " + side + " role");
			}
			if (!allowed(c.role()).contains(c.level())) {
				throw ApiException.unprocessable(c.role() == Role.ERGO_ADMIN && LEVELS.contains(c.level())
						? "ERGO Admin is view-only on business modules (allowed: " + String.join(", ", allowed(c.role())) + ")"
						: "Unknown access level " + c.level());
			}
			proposed.put(key(c.role(), module), c.level());
		}
		return apply(side, reason, before, proposed, configurable);
	}

	@Transactional
	public Matrix reset(CompanyType side, long expectedVersion, String reason) {
		lockAndCheckVersion(expectedVersion);
		Map<String, String> proposed = currentLevels();
		Map<String, String> before = new HashMap<>(proposed);
		Set<String> configurable = configurableModules();
		for (Role role : Role.values()) {
			if (role.companyType() == side) {
				for (String module : configurable) {
					String def = DefaultAccessSheet.code(role, module);
					if (def != null) {
						proposed.put(key(role, module), def);
					}
				}
			}
		}
		return apply(side, reason, before, proposed, configurable);
	}

	/**
	 * Sets a module's platform-wide switch: O on, A no final approvals, V read-only, NV hidden for every role. The role
	 * sheet is untouched, so switching back to O restores exactly what every role had.
	 */
	@Transactional
	public Matrix switchModule(CompanyType side, String module, long expectedVersion, String reason, String level) {
		lockAndCheckVersion(expectedVersion);
		if (!LEVELS.contains(level)) {
			throw ApiException.unprocessable("Unknown access level " + level);
		}
		Map<String, Object> row = jdbc.query(
				"SELECT module_id, access_cap, configurable FROM app_modules WHERE module_code = ?",
				rs -> rs.next()
						? Map.<String, Object>of("id", rs.getLong(1), "cap", rs.getString(2), "cfg", rs.getBoolean(3))
						: null,
				module);
		if (row == null || !(Boolean) row.get("cfg")) {
			throw ApiException.unprocessable("Module " + module + " cannot be switched here");
		}
		String old = (String) row.get("cap");
		if (old.equals(level)) {
			throw ApiException.unprocessable("Nothing to change: the module already has this switch");
		}
		audit.reason(reason.trim());
		jdbc.update("UPDATE app_modules SET access_cap = ? WHERE module_code = ?", level, module);
		audit.record("admin", MODULE_ENTITY, (Long) row.get("id"), "MODULE_SWITCH_CHANGED", module, old, level);
		bumpVersionAfterCommitRefresh();
		return matrix(side);
	}

	/** Newest first: role-cell changes and module-switch changes (role null), optionally for one role and/or module. */
	@Transactional(readOnly = true)
	public List<HistoryEntry> history(Role role, String module) {
		String roleName = role == null ? null : role.name();
		List<Row> rows = new ArrayList<>(jdbc.query("""
				SELECT a.audit_log_id, a.changed_at, u.first_name || ' ' || u.last_name, a.changed_by_role_code,
				       p.role_code, p.module_code, a.old_value, a.new_value, a.action_reason
				FROM audit_logs a
				JOIN role_module_permissions p ON p.permission_id = a.record_id
				LEFT JOIN users u ON u.user_id = a.changed_by_user_id
				WHERE a.module_name = 'admin' AND a.entity_name = ?
				  AND (?::text IS NULL OR p.role_code = ?) AND (?::text IS NULL OR p.module_code = ?)
				ORDER BY a.audit_log_id DESC LIMIT 200""",
				(rs, n) -> new Row(rs.getLong(1), new HistoryEntry(rs.getObject(2, OffsetDateTime.class).toInstant(),
						rs.getString(3), rs.getString(4), Role.valueOf(rs.getString(5)), rs.getString(6),
						rs.getString(7), rs.getString(8), rs.getString(9))),
				ENTITY, roleName, roleName, module, module));
		if (role == null) { // a module switch belongs to no role
			rows.addAll(jdbc.query("""
					SELECT a.audit_log_id, a.changed_at, u.first_name || ' ' || u.last_name, a.changed_by_role_code,
					       m.module_code, a.old_value, a.new_value, a.action_reason
					FROM audit_logs a
					JOIN app_modules m ON m.module_id = a.record_id
					LEFT JOIN users u ON u.user_id = a.changed_by_user_id
					WHERE a.module_name = 'admin' AND a.entity_name = ? AND (?::text IS NULL OR m.module_code = ?)
					ORDER BY a.audit_log_id DESC LIMIT 200""",
					(rs, n) -> new Row(rs.getLong(1), new HistoryEntry(rs.getObject(2, OffsetDateTime.class).toInstant(),
							rs.getString(3), rs.getString(4), null, rs.getString(5), rs.getString(6), rs.getString(7),
							rs.getString(8))),
					MODULE_ENTITY, module, module));
		}
		return rows.stream().sorted(Comparator.comparingLong(Row::auditId).reversed()).limit(200).map(Row::entry).toList();
	}

	private record Row(long auditId, HistoryEntry entry) {
	}

	private Matrix apply(CompanyType side, String reason, Map<String, String> before, Map<String, String> proposed,
			Set<String> configurable) {
		for (String module : configurable) {
			boolean ergoOwner = Arrays.stream(Role.values())
				.anyMatch(r -> r.companyType() == CompanyType.ERGO && r != Role.ERGO_KAM
						&& "O".equals(proposed.get(key(r, module))));
			if (!ergoOwner) {
				throw ApiException.unprocessable("At least one ERGO role must keep Owner (O) on " + module
						+ ", otherwise nobody can give its final approval (the account-scoped Key Account Manager does not count)");
			}
		}
		audit.reason(reason.trim());
		long me = CurrentUser.get().userId();
		int changed = 0;
		for (Map.Entry<String, String> e : proposed.entrySet()) {
			String old = before.get(e.getKey());
			if (e.getValue().equals(old)) {
				continue;
			}
			String[] parts = e.getKey().split("\\|");
			Long id = jdbc.queryForObject("""
					UPDATE role_module_permissions SET access_level = ?, updated_by_user_id = ?, updated_at = now()
					WHERE role_code = ? AND module_code = ? RETURNING permission_id""", Long.class, e.getValue(), me,
					parts[0], parts[1]);
			audit.record("admin", ENTITY, id, "PERMISSION_CHANGED", parts[1], old, e.getValue());
			changed++;
		}
		if (changed == 0) {
			throw ApiException.unprocessable("Nothing to change: every cell already has the requested level");
		}
		bumpVersionAfterCommitRefresh();
		return matrix(side);
	}

	/** One version bump per save; the live policy reloads as soon as the save commits. */
	private void bumpVersionAfterCommitRefresh() {
		jdbc.update("UPDATE access_config SET version = version + 1");
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				policy.refresh();
			}
		});
	}

	private void lockAndCheckVersion(long expectedVersion) {
		long version = jdbc.queryForObject("SELECT version FROM access_config FOR UPDATE", Long.class);
		if (version != expectedVersion) {
			throw ApiException.conflict("The permissions were changed by someone else. Reload and try again.");
		}
	}

	private Map<String, String> currentLevels() {
		Map<String, String> levels = new HashMap<>();
		jdbc.query("SELECT role_code, module_code, access_level FROM role_module_permissions",
				rs -> { levels.put(rs.getString(1) + "|" + rs.getString(2), rs.getString(3)); });
		return levels;
	}

	private Set<String> configurableModules() {
		return Set.copyOf(jdbc.queryForList("SELECT module_code FROM app_modules WHERE configurable", String.class));
	}

	private static List<String> allowed(Role role) {
		return role == Role.ERGO_ADMIN ? List.of("NV", "V") : LEVELS;
	}

	private static String key(Role role, String module) {
		return role.name() + "|" + module;
	}

}
