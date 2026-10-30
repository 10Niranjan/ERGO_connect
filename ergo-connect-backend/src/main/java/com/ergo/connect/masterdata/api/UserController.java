package com.ergo.connect.masterdata.api;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import com.ergo.connect.masterdata.access.AuthDtos.SessionResponse;
import com.ergo.connect.masterdata.access.AuthService;
import com.ergo.connect.masterdata.access.UserAdminService;
import com.ergo.connect.masterdata.access.UserDtos.AccountsRequest;
import com.ergo.connect.masterdata.access.UserDtos.ActiveRequest;
import com.ergo.connect.masterdata.access.UserDtos.CreateUserRequest;
import com.ergo.connect.masterdata.access.UserDtos.CreatedUserResponse;
import com.ergo.connect.masterdata.access.UserDtos.DeleteRequest;
import com.ergo.connect.masterdata.access.UserDtos.LoginAsRequest;
import com.ergo.connect.masterdata.access.UserDtos.RolesRequest;
import com.ergo.connect.masterdata.access.UserDtos.TemporaryPasswordResponse;
import com.ergo.connect.masterdata.access.UserDtos.UpdateUserRequest;
import com.ergo.connect.masterdata.access.UserDtos.UserResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * SRS §10.1 User & Access Management. View: every role, limited to its record scope (V / RV). Create, edit,
 * activate, role mapping, delete (with a reason) and password reset: ERGO Admin only (client decision, ARCHITECTURE.md §8); ERGO RFQ
 * may also create supplier accounts.
 */
@RestController
@RequestMapping("/api")
class UserController {

	private static final String ERGO_ADMIN = "hasRole('ERGO_ADMIN')";

	private final UserAdminService users;

	private final AuthService auth;

	UserController(UserAdminService users, AuthService auth) {
		this.users = users;
		this.auth = auth;
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

	/** ERGO Admin any account; ERGO RFQ supplier accounts only (the service enforces the company type). */
	@PostMapping("/users")
	@PreAuthorize("hasAnyRole('ERGO_ADMIN', 'ERGO_RFQ')")
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

	@PutMapping("/users/{id}/accounts")
	@PreAuthorize(ERGO_ADMIN)
	UserResponse setAccounts(@PathVariable long id, @Valid @RequestBody AccountsRequest body) {
		return users.setAccounts(id, body.customerIds());
	}

	/**
	 * Soft delete: hides the user and ends access, keeps the audit trail. A POST with a reason (like reject), not a
	 * DELETE: a request body on DELETE is dropped by some proxies and clients.
	 */
	@PostMapping("/users/{id}/delete")
	@PreAuthorize(ERGO_ADMIN)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable long id, @Valid @RequestBody DeleteRequest body) {
		users.delete(id, body.reason());
	}

	@PostMapping("/users/{id}/reset-password")
	@PreAuthorize(ERGO_ADMIN)
	TemporaryPasswordResponse resetPassword(@PathVariable long id) {
		return users.resetPassword(id);
	}

	/** Super Admin "Login as": a view-only session for this user (docs/PERMISSIONS.md section 13). */
	@PostMapping("/users/{id}/login-as")
	@PreAuthorize(ERGO_ADMIN)
	ResponseEntity<SessionResponse> loginAs(@PathVariable long id, @Valid @RequestBody LoginAsRequest body,
			HttpServletRequest request) {
		AuthService.LoginResult result = auth.startImpersonation(id, body.reason(), request.getRemoteAddr());
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, AuthController.shadowCookie(result.sessionToken(), result.sessionTtl()))
			.body(result.session());
	}

}
