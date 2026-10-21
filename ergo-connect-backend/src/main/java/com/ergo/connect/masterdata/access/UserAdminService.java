package com.ergo.connect.masterdata.access;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.access.AuthDtos.RoleOption;
import com.ergo.connect.masterdata.access.UserDtos.CreateUserRequest;
import com.ergo.connect.masterdata.access.UserDtos.CreatedUserResponse;
import com.ergo.connect.masterdata.access.UserDtos.RolesRequest;
import com.ergo.connect.masterdata.access.UserDtos.TemporaryPasswordResponse;
import com.ergo.connect.masterdata.access.UserDtos.UpdateUserRequest;
import com.ergo.connect.masterdata.access.UserDtos.UserAction;
import com.ergo.connect.masterdata.access.UserDtos.UserResponse;

/**
 * User & Access Management (SRS §10.1). Everyone may view users in their record scope; only ERGO Admin changes them
 * (decision in ARCHITECTURE.md §8, enforced on the controller), with one exception: ERGO RFQ may also create supplier
 * accounts (client decision 2026-10-21). Field changes are audited automatically, role changes explicitly.
 */
@Service
@Transactional
public class UserAdminService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRepository users;

	private final CompanyRepository companies;

	private final SessionStore sessions;

	private final RoleCatalog roleCatalog;

	private final PasswordEncoder passwordEncoder;

	private final AuditLogService audit;

	private final PasswordResetTokenStore resetTokens;

	private final OtpCodeStore otpCodes;

	UserAdminService(UserRepository users, CompanyRepository companies, SessionStore sessions, RoleCatalog roleCatalog,
			PasswordEncoder passwordEncoder, AuditLogService audit, PasswordResetTokenStore resetTokens,
			OtpCodeStore otpCodes) {
		this.users = users;
		this.companies = companies;
		this.sessions = sessions;
		this.roleCatalog = roleCatalog;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.resetTokens = resetTokens;
		this.otpCodes = otpCodes;
	}

	@Transactional(readOnly = true)
	public List<UserResponse> list() {
		List<User> visible = users.findAllVisible(CurrentUser.get().companyScope());
		Map<Long, Company> byId = companies
			.findByIdIn(visible.stream().map(u -> u.companyId).collect(Collectors.toSet()))
			.stream()
			.collect(Collectors.toMap(c -> c.id, Function.identity()));
		return visible.stream().map(u -> toResponse(u, byId.get(u.companyId))).toList();
	}

	@Transactional(readOnly = true)
	public UserResponse get(long id) {
		User user = visible(id);
		return toResponse(user, companyOf(user));
	}

	@Transactional(readOnly = true)
	public List<RoleOption> roles() {
		return roleCatalog.activeRoles()
			.entrySet()
			.stream()
			.map(e -> new RoleOption(e.getKey(), e.getValue(), e.getKey().companyType()))
			.toList();
	}

	/**
	 * ERGO Admin creates any account (ERGO employee, customer employee, supplier employee). ERGO RFQ creates supplier
	 * accounts only (client decision 2026-10-21). The company must already be approved, so a pending or rejected
	 * supplier has no accounts.
	 */
	public CreatedUserResponse create(CreateUserRequest request) {
		Role caller = CurrentUser.get().role();
		Company company = companies.findById(request.companyId())
			.filter(c -> c.status == CompanyStatus.ACTIVE)
			.orElseThrow(() -> ApiException.unprocessable("Company not found or not active (approve it first)"));
		if (caller != Role.ERGO_ADMIN && company.type != CompanyType.SUPPLIER) {
			throw ApiException.forbidden("You can create accounts for supplier companies only");
		}
		requireUniqueEmail(request.email());
		Role defaultRole = checkRoles(request.roles(), request.defaultRole(), company.type);

		User user = new User();
		user.companyId = company.id;
		apply(user, request.firstName(), request.lastName(), request.email(), request.mobile());
		String temporaryPassword = temporaryPassword();
		user.passwordHash = passwordEncoder.encode(temporaryPassword);
		user.mustChangePassword = true;
		user.roles.addAll(request.roles());
		user.defaultRole = defaultRole;
		boolean mandatoryTwoFactor = request.roles().contains(Role.ERGO_ADMIN); // mandatory for ERGO Admin (ARCHITECTURE.md §8)
		user.twoFactorEnabled = mandatoryTwoFactor;
		users.save(user);
		request.roles().forEach(role -> audit.record(User.class, user.id, "ROLE_ADDED", "roles", null, role.name()));
		if (mandatoryTwoFactor) {
			audit.record(User.class, user.id, "TWO_FACTOR_ENABLED", null, null, null);
		}
		return new CreatedUserResponse(toResponse(user, company), temporaryPassword);
	}

	public UserResponse update(long id, UpdateUserRequest request) {
		User user = visible(id);
		boolean emailChanged = !user.email.equalsIgnoreCase(request.email().trim());
		if (emailChanged) {
			requireUniqueEmail(request.email());
		}
		apply(user, request.firstName(), request.lastName(), request.email(), request.mobile());
		if (emailChanged) {
			// a link or code already mailed to the old address must not still work once it no longer belongs to
			// this user (Phase 8f security review finding; same reasoning for OTP codes in Phase 8g)
			resetTokens.invalidateAllFor(user.id);
			otpCodes.invalidateAllFor(user.id);
		}
		return toResponse(user, companyOf(user));
	}

	public UserResponse setRoles(long id, RolesRequest request) {
		User user = visible(id);
		Role defaultRole = checkRoles(request.roles(), request.defaultRole(), companyOf(user).type);
		if (isSelf(user) && !request.roles().contains(Role.ERGO_ADMIN)) {
			throw ApiException.unprocessable("You cannot remove your own ERGO Admin role");
		}
		for (Role role : user.roles) {
			if (!request.roles().contains(role)) {
				audit.record(User.class, user.id, "ROLE_REMOVED", "roles", role.name(), null);
			}
		}
		for (Role role : request.roles()) {
			if (!user.roles.contains(role)) {
				audit.record(User.class, user.id, "ROLE_ADDED", "roles", null, role.name());
			}
		}
		user.roles.retainAll(request.roles());
		user.roles.addAll(request.roles());
		user.defaultRole = defaultRole;
		if (request.roles().contains(Role.ERGO_ADMIN) && !user.twoFactorEnabled) {
			// mandatory for ERGO Admin (ARCHITECTURE.md §8, Phase 8g) - granted here, not just at creation
			user.twoFactorEnabled = true;
			audit.record(User.class, user.id, "TWO_FACTOR_ENABLED", null, null, null);
		}
		return toResponse(user, companyOf(user));
	}

	public UserResponse setActive(long id, boolean active) {
		User user = visible(id);
		if (!active && isSelf(user)) {
			throw ApiException.unprocessable("You cannot deactivate your own account");
		}
		user.active = active;
		if (!active) {
			sessions.revokeAll(user.id, null);
			resetTokens.invalidateAllFor(user.id); // a still-outstanding emailed link must not reactivate access
			otpCodes.invalidateAllFor(user.id);
		}
		return toResponse(user, companyOf(user));
	}

	/**
	 * Soft delete (ERGO Admin only, enforced on the controller): the row is kept because the audit trail refers to
	 * it, but the user disappears from every list, loses every session and can never sign in or be reactivated. The
	 * reason is stored with the deletion in the audit trail.
	 */
	public void delete(long id, String reason) {
		User user = visible(id);
		if (isSelf(user)) {
			throw ApiException.unprocessable("You cannot delete your own account");
		}
		audit.reason(reason.trim());
		user.active = false;
		user.deletedAt = Instant.now();
		user.deletedByUserId = CurrentUser.get().userId();
		sessions.revokeAll(user.id, null);
		resetTokens.invalidateAllFor(user.id);
		otpCodes.invalidateAllFor(user.id);
		audit.record(User.class, user.id, "USER_DELETED", null, null, null);
	}

	/** SRS §12.13.1 "password reset trigger": a one-time password the admin hands over; the user must change it. */
	public TemporaryPasswordResponse resetPassword(long id) {
		User user = visible(id);
		String temporaryPassword = temporaryPassword();
		user.passwordHash = passwordEncoder.encode(temporaryPassword);
		user.mustChangePassword = true;
		user.failedLoginCount = 0;
		user.lockedUntil = null;
		resetTokens.invalidateAllFor(user.id); // this reset supersedes any self-service link still outstanding
		sessions.revokeAll(user.id, null);
		return new TemporaryPasswordResponse(temporaryPassword);
	}

	/** Which customer accounts an ERGO Key Account Manager covers (SRS §13.2.5). Also enforced by a DB trigger. */
	public UserResponse setAccounts(long id, Set<Long> customerIds) {
		User user = visible(id);
		Company company = companyOf(user);
		if (company.type != CompanyType.ERGO) {
			throw ApiException.unprocessable("Only ERGO users can be assigned customer accounts");
		}
		List<Company> customers = companies.findByIdIn(customerIds);
		if (customers.size() != customerIds.size() || customers.stream().anyMatch(c -> c.type != CompanyType.CUSTOMER)) {
			throw ApiException.unprocessable("Accounts must be existing customer companies");
		}
		for (Long account : user.accountIds) {
			if (!customerIds.contains(account)) {
				audit.record(User.class, user.id, "ACCOUNT_UNASSIGNED", "accounts", String.valueOf(account), null);
			}
		}
		for (Long account : customerIds) {
			if (!user.accountIds.contains(account)) {
				audit.record(User.class, user.id, "ACCOUNT_ASSIGNED", "accounts", null, String.valueOf(account));
			}
		}
		user.accountIds.retainAll(customerIds);
		user.accountIds.addAll(customerIds);
		return toResponse(user, company);
	}

	private User visible(long id) {
		return users.findVisible(id, CurrentUser.get().companyScope())
			.orElseThrow(() -> ApiException.notFound("User"));
	}

	/** @return the default role to store */
	private Role checkRoles(Set<Role> roles, Role requestedDefault, CompanyType companyType) {
		Set<Role> assignable = roleCatalog.activeRoles().keySet();
		if (roles.contains(Role.ERGO_ADMIN)) {
			// Super Admin (docs/PERMISSIONS.md §10): held only by an account of the ERGO company, and only an existing
			// Super Admin can hand it out. (The controller already says so; checked here too so no other caller can.)
			if (companyType != CompanyType.ERGO) {
				throw ApiException.unprocessable("The Super Admin role (ERGO Admin) can only be held by an ERGO account");
			}
			if (CurrentUser.find().map(me -> me.role() != Role.ERGO_ADMIN).orElse(true)) {
				throw ApiException.forbidden("Only a Super Admin can give or keep the Super Admin role");
			}
		}
		for (Role role : roles) {
			if (role.companyType() != companyType) {
				throw ApiException.unprocessable(role + " cannot be given to a " + companyType + " user");
			}
			if (!assignable.contains(role)) {
				throw ApiException.unprocessable(role + " is not active");
			}
		}
		if (requestedDefault == null) {
			return EnumSet.copyOf(roles).iterator().next();
		}
		if (!roles.contains(requestedDefault)) {
			throw ApiException.unprocessable("The default role must be one of the user's roles");
		}
		return requestedDefault;
	}

	private void requireUniqueEmail(String email) {
		if (users.existsByEmailIgnoreCase(email.trim())) {
			throw ApiException.conflict("A user with this email already exists");
		}
	}

	private static void apply(User user, String firstName, String lastName, String email, String mobile) {
		user.firstName = firstName.trim();
		user.lastName = lastName.trim();
		user.email = email.trim().toLowerCase(Locale.ROOT);
		user.mobile = mobile == null || mobile.isBlank() ? null : mobile.trim();
	}

	private static boolean isSelf(User user) {
		return CurrentUser.find().map(me -> me.userId() == user.id).orElse(false);
	}

	private Company companyOf(User user) {
		return companies.findById(user.companyId).orElseThrow();
	}

	static String temporaryPassword() {
		byte[] bytes = new byte[12];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); // 16 characters
	}

	private static UserResponse toResponse(User user, Company company) {
		// computed by the same checks that enforce them: ERGO Admin only, never on yourself
		Set<UserAction> actions = EnumSet.noneOf(UserAction.class);
		if (CurrentUser.find().map(me -> me.role() == Role.ERGO_ADMIN).orElse(false) && !isSelf(user)) {
			actions.add(UserAction.DELETE);
			actions.add(user.active ? UserAction.DEACTIVATE : UserAction.ACTIVATE);
		}
		return new UserResponse(user.id, company.id, company.name, company.type, user.firstName, user.lastName,
				user.email, user.mobile, user.active, user.roles.stream().sorted().toList(), user.defaultRole,
				user.accountIds.stream().sorted().toList(), user.lastLoginAt, user.twoFactorEnabled, actions);
	}

}
