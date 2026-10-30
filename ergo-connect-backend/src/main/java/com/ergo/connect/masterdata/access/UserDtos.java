package com.ergo.connect.masterdata.access;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;

import com.ergo.connect.common.validation.NotInvisiblyBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class UserDtos {

	private UserDtos() {
	}

	/** What the caller may do with this user now, so the UI shows only valid buttons (empty for all but ERGO Admin). */
	public enum UserAction {
		DEACTIVATE, ACTIVATE, DELETE, LOGIN_AS
	}

	public record UserResponse(long id, long companyId, String companyName, CompanyType companyType,
			String firstName, String lastName, String email, String mobile, boolean active, List<Role> roles,
			Role defaultRole, List<Long> accountIds, Instant lastLoginAt, boolean twoFactorEnabled,
			Set<UserAction> actions) {
	}

	public record CreateUserRequest(@NotNull Long companyId, @NotBlank @Size(max = 100) String firstName,
			@NotBlank @Size(max = 100) String lastName, @NotBlank @Email @Size(max = 254) String email,
			@Size(max = 30) String mobile, @NotEmpty Set<@NotNull Role> roles, Role defaultRole) {
	}

	public record UpdateUserRequest(@NotBlank @Size(max = 100) String firstName,
			@NotBlank @Size(max = 100) String lastName, @NotBlank @Email @Size(max = 254) String email,
			@Size(max = 30) String mobile) {
	}

	public record RolesRequest(@NotEmpty Set<@NotNull Role> roles, Role defaultRole) {
	}

	/** Why an account is deleted; stored in the audit trail with the deletion. */
	public record DeleteRequest(@NotInvisiblyBlank @Size(max = 500) String reason) {
	}

	/** Why a Super Admin logs in as this user (view only); stored in the audit trail with the start. */
	public record LoginAsRequest(@NotInvisiblyBlank @Size(min = 5, max = 500) String reason) {
	}

	public record ActiveRequest(@NotNull Boolean active) {
	}

	/** An ERGO Key Account Manager's customer accounts. Replaces the current list. */
	public record AccountsRequest(@NotNull Set<@NotNull Long> customerIds) {
	}

	/** The temporary password is shown to the ERGO Admin once and never stored in plain text. */
	public record CreatedUserResponse(UserResponse user, String temporaryPassword) {
	}

	public record TemporaryPasswordResponse(String temporaryPassword) {
	}

}
