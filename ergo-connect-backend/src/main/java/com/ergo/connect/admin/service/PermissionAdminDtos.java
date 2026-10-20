package com.ergo.connect.admin.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.ergo.connect.common.security.Role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response shapes of the Permission Management screen. Levels are the codes NV, V, A, O. */
public final class PermissionAdminDtos {

	private PermissionAdminDtos() {
	}

	public record ModuleColumn(String code, String label, boolean configurable) {
	}

	/** {@code allowed} is what the server will accept for this cell; {@code editable} is false for fixed modules. */
	public record Cell(String level, String defaultLevel, boolean editable, List<String> allowed) {
	}

	public record RoleRow(Role code, String name, boolean active, Map<String, Cell> cells) {
	}

	public record Matrix(long version, String side, List<String> levels, List<ModuleColumn> modules,
			List<RoleRow> roles) {
	}

	public record Change(@NotNull Role role, @NotBlank String module, @NotBlank String level) {
	}

	public record SaveRequest(long expectedVersion, @NotBlank @Size(max = 500) String reason,
			@NotEmpty @Size(max = 200) List<@Valid Change> changes) {
	}

	public record ResetRequest(long expectedVersion, @NotBlank @Size(max = 500) String reason) {
	}

	public record HistoryEntry(Instant at, String changedBy, String changedByRole, Role role, String module,
			String oldLevel, String newLevel, String reason) {
	}

}
