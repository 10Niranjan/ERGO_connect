package com.ergo.connect.masterdata.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.masterdata.access.AuthDtos.RoleOption;
import com.ergo.connect.masterdata.access.UserAdminService;
import com.ergo.connect.masterdata.access.UserDtos.ActiveRequest;
import com.ergo.connect.masterdata.access.UserDtos.CreateUserRequest;
import com.ergo.connect.masterdata.access.UserDtos.CreatedUserResponse;
import com.ergo.connect.masterdata.access.UserDtos.RolesRequest;
import com.ergo.connect.masterdata.access.UserDtos.TemporaryPasswordResponse;
import com.ergo.connect.masterdata.access.UserDtos.UpdateUserRequest;
import com.ergo.connect.masterdata.access.UserDtos.UserResponse;

import jakarta.validation.Valid;

/**
 * SRS §10.1 User & Access Management. View: every role, limited to its record scope (V / RV). Create, edit,
 * activate, role mapping and password reset: ERGO Admin only (client decision, ARCHITECTURE.md §8).
 */
@RestController
@RequestMapping("/api")
class UserController {

	private static final String ERGO_ADMIN = "hasRole('ERGO_ADMIN')";

	private final UserAdminService users;

	UserController(UserAdminService users) {
		this.users = users;
	}

	@GetMapping("/users")
	List<UserResponse> list() {
		return users.list();
	}

	@GetMapping("/users/{id}")
	UserResponse get(@PathVariable long id) {
		return users.get(id);
	}

	@GetMapping("/roles")
	List<RoleOption> roles() {
		return users.roles();
	}

	@PostMapping("/users")
	@PreAuthorize(ERGO_ADMIN)
	@ResponseStatus(HttpStatus.CREATED)
	CreatedUserResponse create(@Valid @RequestBody CreateUserRequest body) {
		return users.create(body);
	}

	@PutMapping("/users/{id}")
	@PreAuthorize(ERGO_ADMIN)
	UserResponse update(@PathVariable long id, @Valid @RequestBody UpdateUserRequest body) {
		return users.update(id, body);
	}

	@PutMapping("/users/{id}/roles")
	@PreAuthorize(ERGO_ADMIN)
	UserResponse setRoles(@PathVariable long id, @Valid @RequestBody RolesRequest body) {
		return users.setRoles(id, body);
	}

	@PutMapping("/users/{id}/active")
	@PreAuthorize(ERGO_ADMIN)
	UserResponse setActive(@PathVariable long id, @Valid @RequestBody ActiveRequest body) {
		return users.setActive(id, body.active());
	}

	@PostMapping("/users/{id}/reset-password")
	@PreAuthorize(ERGO_ADMIN)
	TemporaryPasswordResponse resetPassword(@PathVariable long id) {
		return users.resetPassword(id);
	}

}
