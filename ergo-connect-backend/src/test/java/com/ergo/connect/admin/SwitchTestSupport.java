package com.ergo.connect.admin;

import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;

import com.ergo.connect.common.security.AccessPolicy;
import com.ergo.connect.common.security.DefaultAccessSheet;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.Role;

/**
 * Module-switch tests flip the switches straight in the database (the admin API is covered by ModuleSwitchTests) and
 * always put the shipped sheet back: all integration tests share one database.
 */
final class SwitchTestSupport {

	private SwitchTestSupport() {
	}

	/** Sets one module's switch the way another application instance's save would: database first, then the policy. */
	static void cap(JdbcTemplate jdbc, AccessPolicy policy, String module, String cap) {
		jdbc.update("UPDATE app_modules SET access_cap = ? WHERE module_code = ?", cap, module);
		jdbc.update("UPDATE access_config SET version = version + 1");
		policy.refresh();
	}

	/** Every switch back to O and every role cell back to the shipped sheet. */
	static void restore(JdbcTemplate jdbc, AccessPolicy policy) {
		jdbc.update("UPDATE app_modules SET access_cap = 'O'");
		for (Role role : Role.values()) {
			for (Module module : Module.values()) {
				jdbc.update("UPDATE role_module_permissions SET access_level = ? WHERE role_code = ? AND module_code = ?",
						DefaultAccessSheet.code(role, module.name()), role.name(), module.name());
			}
		}
		jdbc.update("UPDATE access_config SET version = version + 1");
		policy.refresh();
	}

	/** The stored role-by-module grid, to prove a switch never rewrites it. */
	static Map<String, String> grid(JdbcTemplate jdbc) {
		Map<String, String> grid = new TreeMap<>();
		jdbc.query("SELECT role_code, module_code, access_level FROM role_module_permissions",
				rs -> {
					grid.put(rs.getString(1) + "/" + rs.getString(2), rs.getString(3));
				});
		return grid;
	}

}
