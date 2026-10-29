package com.ergo.connect.admin.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Theme shapes. Colours are strict #rrggbb so nothing but a colour can ever reach the page's styles. */
public final class ThemeDtos {

	private ThemeDtos() {
	}

	private static final String HEX = "^#[0-9a-fA-F]{6}$";

	public record ModeColors(@NotNull @Pattern(regexp = HEX) String primary,
			@NotNull @Pattern(regexp = HEX) String headerBg, @NotNull @Pattern(regexp = HEX) String headerText,
			@NotNull @Pattern(regexp = HEX) String navBg, @NotNull @Pattern(regexp = HEX) String navText,
			@NotNull @Pattern(regexp = HEX) String navActiveBg, @NotNull @Pattern(regexp = HEX) String navActiveText) {
	}

	public record ThemeConfig(@NotNull @Valid ModeColors light, @NotNull @Valid ModeColors dark) {
	}

	/** {@code draft} is null when there are no unpublished changes; {@code published} null = built-in default. */
	public record ThemeAdminView(String side, ThemeConfig draft, ThemeConfig published, long version) {
	}

	public record PublishedTheme(ThemeConfig config) {
	}

	public record SaveDraftRequest(long expectedVersion, @NotNull @Valid ThemeConfig config) {
	}

	public record VersionRequest(long expectedVersion) {
	}

}
