package com.ergo.connect.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.admin.service.ThemeDtos.PublishedTheme;
import com.ergo.connect.admin.service.ThemeDtos.ThemeAdminView;
import com.ergo.connect.admin.service.ThemeDtos.ThemeConfig;
import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;

import jakarta.validation.Validator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Super Admin sets one look per side (ERGO, Customer, Supplier). Users only ever read their own side's published
 * theme; drafts are admin-only until published. Every change is audited.
 */
@Service
public class ThemeService {

	private static final Logger log = LoggerFactory.getLogger(ThemeService.class);

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final AuditLogService audit;

	private final Validator validator;

	ThemeService(JdbcTemplate jdbc, JsonMapper json, AuditLogService audit, Validator validator) {
		this.jdbc = jdbc;
		this.json = json;
		this.audit = audit;
		this.validator = validator;
	}

	/** The caller's own side, published theme only. */
	@Transactional(readOnly = true)
	public PublishedTheme forCaller() {
		String stored = jdbc.queryForObject("SELECT published_config FROM theme_settings WHERE side = ?", String.class,
				CurrentUser.get().companyType().name());
		try {
			ThemeConfig config = parse(stored);
			// a value that is not a plain hex colour must never reach the page; fall back to the default look
			return new PublishedTheme(config != null && validator.validate(config).isEmpty() ? config : null);
		}
		catch (RuntimeException e) {
			log.error("Stored theme for {} is unreadable; serving the default look", CurrentUser.get().companyType(), e);
			return new PublishedTheme(null);
		}
	}

	@Transactional(readOnly = true)
	public ThemeAdminView view(CompanyType side) {
		return jdbc.queryForObject("SELECT draft_config, published_config, version FROM theme_settings WHERE side = ?",
				(rs, n) -> new ThemeAdminView(side.name(), parse(rs.getString(1)), parse(rs.getString(2)), rs.getLong(3)),
				side.name());
	}

	@Transactional
	public ThemeAdminView saveDraft(CompanyType side, long expectedVersion, ThemeConfig config) {
		lock(side, expectedVersion);
		String old = jdbc.queryForObject("SELECT draft_config FROM theme_settings WHERE side = ?", String.class, side.name());
		String value = json.writeValueAsString(config);
		jdbc.update("""
				UPDATE theme_settings SET draft_config = ?, version = version + 1, updated_at = now(), updated_by = ?
				WHERE side = ?""", value, CurrentUser.get().userId(), side.name());
		audit.record("admin", "ThemeSettings", id(side), "THEME_DRAFT_SAVED", "draft_config", old, value);
		return view(side);
	}

	@Transactional
	public ThemeAdminView publish(CompanyType side, long expectedVersion) {
		lock(side, expectedVersion);
		var row = jdbc.queryForMap("SELECT draft_config, published_config FROM theme_settings WHERE side = ?", side.name());
		if (row.get("draft_config") == null) {
			throw ApiException.unprocessable("There is no draft to publish. Save your changes as a draft first.");
		}
		jdbc.update("""
				UPDATE theme_settings SET published_config = draft_config, draft_config = NULL, version = version + 1,
				       updated_at = now(), updated_by = ? WHERE side = ?""", CurrentUser.get().userId(), side.name());
		audit.record("admin", "ThemeSettings", id(side), "THEME_PUBLISHED", "published_config",
				(String) row.get("published_config"), (String) row.get("draft_config"));
		return view(side);
	}

	/** Back to the built-in default look: clears the draft and the published theme. */
	@Transactional
	public ThemeAdminView reset(CompanyType side, long expectedVersion) {
		lock(side, expectedVersion);
		var row = jdbc.queryForMap("SELECT draft_config, published_config FROM theme_settings WHERE side = ?", side.name());
		jdbc.update("""
				UPDATE theme_settings SET draft_config = NULL, published_config = NULL, version = version + 1,
				       updated_at = now(), updated_by = ? WHERE side = ?""", CurrentUser.get().userId(), side.name());
		audit.record("admin", "ThemeSettings", id(side), "THEME_RESET", "published_config",
				(String) row.get("published_config"), null);
		if (row.get("draft_config") != null) {
			audit.record("admin", "ThemeSettings", id(side), "THEME_RESET", "draft_config",
					(String) row.get("draft_config"), null);
		}
		return view(side);
	}

	private void lock(CompanyType side, long expectedVersion) {
		Long version = jdbc.queryForObject("SELECT version FROM theme_settings WHERE side = ? FOR UPDATE", Long.class, side.name());
		if (version == null || version != expectedVersion) {
			throw ApiException.conflict("The theme was changed by someone else. Reload and try again.");
		}
	}

	private static long id(CompanyType side) {
		// fixed on purpose: the audit history is keyed by this, so it must not follow the enum's declaration order
		return switch (side) {
			case ERGO -> 1L;
			case CUSTOMER -> 2L;
			case SUPPLIER -> 3L;
		};
	}

	private ThemeConfig parse(String text) {
		return text == null ? null : json.readValue(text, ThemeConfig.class);
	}

}
