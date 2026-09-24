package com.ergo.connect.masterdata.access;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ergo.connect.common.security.Role;

@Component
class RoleCatalog {

	private final JdbcTemplate jdbc;

	RoleCatalog(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Assignable roles with display names, in enum order. Inactive roles (Customer Management in MVP) are left out. */
	Map<Role, String> activeRoles() {
		Map<Role, String> roles = new EnumMap<>(Role.class);
		jdbc.query("SELECT role_code, role_name FROM roles WHERE active",
				rs -> { roles.put(Role.valueOf(rs.getString(1)), rs.getString(2)); });
		return roles;
	}

}
