package com.ergo.connect.masterdata.access;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class UserDtos {

	private UserDtos() {
	}

	public record UserResponse(long id, long companyId, String companyName, CompanyType companyType,
			String firstName, String lastName, String email, String mobile, boolean active, List<Role> roles,
			Role defaultRole, Instant lastLoginAt) {
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

	public record ActiveRequest(@NotNull Boolean active) {
	}

	/** The temporary password is shown to the ERGO Admin once and never stored in plain text. */
	public record CreatedUserResponse(UserResponse user, String temporaryPassword) {
	}

	public record TemporaryPasswordResponse(String temporaryPassword) {
	}

}
