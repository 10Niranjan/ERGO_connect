package com.ergo.connect.admin.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.admin.service.PermissionMatrix;
import com.ergo.connect.admin.service.PermissionMatrix.Legend;
import com.ergo.connect.admin.service.PermissionMatrix.Module;

import java.util.List;

/**
 * SRS §12.13.2 Permission Mapping Screen, scoped to a read-only viewer (ARCHITECTURE.md §8, 2026-10-07): renders the
 * §10.x permission tables for ERGO Admin. Governed by §10.12 (Admin Controls & Master Data), not §10.1's literal
 * table - see that table's own note.
 */
@RestController
@RequestMapping("/api/admin/permission-matrix")
class PermissionMatrixController {

	public record PermissionMatrixResponse(List<Legend> legend, List<Module> modules) {
	}

	@GetMapping
	@PreAuthorize("hasRole('ERGO_ADMIN')")
	PermissionMatrixResponse get() {
		return new PermissionMatrixResponse(PermissionMatrix.LEGEND, PermissionMatrix.MODULES);
	}

}
