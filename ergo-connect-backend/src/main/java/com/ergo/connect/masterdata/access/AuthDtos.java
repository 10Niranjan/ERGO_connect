package com.ergo.connect.masterdata.access;

import java.util.List;

import com.ergo.connect.common.security.AccessPolicy.AccessView;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

	private AuthDtos() {
	}

	public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(max = 200) String password) {
	}

	public record SwitchRoleRequest(@NotNull Role role) {
	}

	public record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword,
			@NotBlank @Size(min = 10, max = 64) String newPassword) {
	}

	public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 254) String email) {
	}

	public record ResetPasswordRequest(@NotBlank @Size(max = 500) String token,
			@NotBlank @Size(min = 10, max = 64) String newPassword) {
	}

	public record VerifyOtpRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Pattern(regexp = "\\d{6}") String code) {
	}

	/** {@code currentPassword} is required only when turning the feature off (security review finding M2). */
	public record TwoFactorRequest(boolean enabled, @Size(max = 200) String currentPassword) {
	}

	/** Returned by {@code /login} instead of a session when the account has 2FA enabled (Phase 8g). */
	public record OtpChallengeResponse(boolean otpRequired) {
	}

	public record RoleOption(Role code, String name, CompanyType companyType) {
	}

	public record SessionUser(long id, String firstName, String lastName, String email, long companyId,
			String companyName, CompanyType companyType, boolean twoFactorEnabled) {
	}

	/** Everything the client needs for the current session. The access token lives in memory only. */
	public record SessionResponse(String accessToken, long expiresIn, SessionUser user, Role activeRole,
			List<RoleOption> roles, boolean mustChangePassword, AccessView access) {
	}

}
