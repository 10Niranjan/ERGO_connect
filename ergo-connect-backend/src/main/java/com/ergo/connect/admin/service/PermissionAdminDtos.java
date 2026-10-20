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

	/** {@code cap} is the module switch: O on, A no final approvals, V read-only, NV hidden for every role. */
	public record ModuleColumn(String code, String label, boolean configurable, String cap) {
	}

	/**
	 * {@code level} is the role's own level; {@code effective} is what it really gets once the module switch is applied
	 * (they differ only while the switch is below the role's level). {@code allowed} is what the server will accept for
	 * this cell; {@code editable} is false for fixed modules.
	 */
	public record Cell(String level, String effective, String defaultLevel, boolean editable, List<String> allowed) {
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

	public record SwitchRequest(long expectedVersion, @NotBlank @Size(max = 500) String reason, @NotBlank String level) {
	}

	public record ResetRequest(long expectedVersion, @NotBlank @Size(max = 500) String reason) {
	}

	/** {@code role} is null for a module-switch change. */
	public record HistoryEntry(Instant at, String changedBy, String changedByRole, Role role, String module,
			String oldLevel, String newLevel, String reason) {
	}

}
