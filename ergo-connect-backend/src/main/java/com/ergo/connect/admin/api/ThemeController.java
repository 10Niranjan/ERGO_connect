package com.ergo.connect.admin.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.admin.service.ThemeDtos.PublishedTheme;
import com.ergo.connect.admin.service.ThemeDtos.SaveDraftRequest;
import com.ergo.connect.admin.service.ThemeDtos.ThemeAdminView;
import com.ergo.connect.admin.service.ThemeDtos.VersionRequest;
import com.ergo.connect.admin.service.ThemeService;
import com.ergo.connect.common.security.CompanyType;

import jakarta.validation.Valid;

/** {@code /api/theme}: any signed-in user reads their own side's published look. {@code /api/admin/themes}: Super Admin only. */
@RestController
class ThemeController {

	private static final String SUPER_ADMIN = "hasRole('ERGO_ADMIN')";

	private final ThemeService themes;

	ThemeController(ThemeService themes) {
		this.themes = themes;
	}

	@GetMapping("/api/theme")
	PublishedTheme mine() {
		return themes.forCaller();
	}

	@GetMapping("/api/admin/themes/{side}")
	@PreAuthorize(SUPER_ADMIN)
	ThemeAdminView view(@PathVariable CompanyType side) {
		return themes.view(side);
	}

	@PutMapping("/api/admin/themes/{side}/draft")
	@PreAuthorize(SUPER_ADMIN)
	ThemeAdminView saveDraft(@PathVariable CompanyType side, @Valid @RequestBody SaveDraftRequest body) {
		return themes.saveDraft(side, body.expectedVersion(), body.config());
	}

	@PostMapping("/api/admin/themes/{side}/publish")
	@PreAuthorize(SUPER_ADMIN)
	ThemeAdminView publish(@PathVariable CompanyType side, @RequestBody VersionRequest body) {
		return themes.publish(side, body.expectedVersion());
	}

	@PostMapping("/api/admin/themes/{side}/reset")
	@PreAuthorize(SUPER_ADMIN)
	ThemeAdminView reset(@PathVariable CompanyType side, @RequestBody VersionRequest body) {
		return themes.reset(side, body.expectedVersion());
	}

}
