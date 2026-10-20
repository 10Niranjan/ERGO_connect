package com.ergo.connect.admin.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.admin.service.PermissionAdminDtos.HistoryEntry;
import com.ergo.connect.admin.service.PermissionAdminDtos.Matrix;
import com.ergo.connect.admin.service.PermissionAdminDtos.ResetRequest;
import com.ergo.connect.admin.service.PermissionAdminDtos.SaveRequest;
import com.ergo.connect.admin.service.PermissionAdminService;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;

import jakarta.validation.Valid;

/**
 * Permission Management: ERGO Admin (the Super Admin) edits the role-by-module access sheet for ERGO, Customer and
 * Supplier roles. Every endpoint is Admin-only; the service enforces the rules that keep the sheet safe.
 */
@RestController
@RequestMapping("/api/admin/permissions")
class PermissionAdminController {

	private static final String SUPER_ADMIN = "hasRole('ERGO_ADMIN')";

	private final PermissionAdminService permissions;

	PermissionAdminController(PermissionAdminService permissions) {
		this.permissions = permissions;
	}

	@GetMapping("/{side}")
	@PreAuthorize(SUPER_ADMIN)
	Matrix matrix(@PathVariable CompanyType side) {
		return permissions.matrix(side);
	}

	@PutMapping("/{side}")
	@PreAuthorize(SUPER_ADMIN)
	Matrix save(@PathVariable CompanyType side, @Valid @RequestBody SaveRequest body) {
		return permissions.save(side, body.expectedVersion(), body.reason(), body.changes());
	}

	@PostMapping("/{side}/reset")
	@PreAuthorize(SUPER_ADMIN)
	Matrix reset(@PathVariable CompanyType side, @Valid @RequestBody ResetRequest body) {
		return permissions.reset(side, body.expectedVersion(), body.reason());
	}

	@GetMapping("/history")
	@PreAuthorize(SUPER_ADMIN)
	List<HistoryEntry> history(@RequestParam(required = false) Role role, @RequestParam(required = false) String module) {
		return permissions.history(role, module);
	}

}
