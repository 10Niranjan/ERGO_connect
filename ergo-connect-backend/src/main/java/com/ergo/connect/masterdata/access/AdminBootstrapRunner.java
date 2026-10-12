package com.ergo.connect.masterdata.access;

import static com.ergo.connect.common.security.CompanyType.ERGO;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.security.Role;

/**
 * Creates the first ERGO Admin outside the dev profile (ARCHITECTURE.md §8, Phase 8e, deferred from 1a). Every
 * other user is created by an ERGO Admin through {@link UserAdminService} - on a brand-new deployment there is no
 * admin yet to call it. Opt-in via env vars so it is a no-op wherever they're unset, and self-disabling once any
 * company exists so it can never run twice or overwrite a real deployment.
 */
@Component
class AdminBootstrapRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

	/** Same shape as a simple address the rest of the app would accept - not RFC 5322, just "not obviously wrong". */
	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

	/** Matches {@code AuthDtos.ChangePasswordRequest} so the bootstrap password can't be weaker than policy allows. */
	private static final int MIN_PASSWORD_LENGTH = 10;

	private static final int MAX_PASSWORD_LENGTH = 64;

	private final CompanyRepository companies;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AuditLogService audit;

	private final String email;

	private final String password;

	AdminBootstrapRunner(CompanyRepository companies, UserRepository users, PasswordEncoder passwordEncoder,
			AuditLogService audit, @Value("${ergo.bootstrap-admin.email:}") String email,
			@Value("${ergo.bootstrap-admin.password:}") String password) {
		this.companies = companies;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.email = email;
		this.password = password;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (email.isBlank() || password.isBlank()) {
			return; // not configured: dev seeds its own users, and a deployed instance normally has an admin already
		}
		if (companies.count() > 0) {
			return; // already bootstrapped - never overwrite existing data
		}
		String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
		if (!EMAIL.matcher(normalizedEmail).matches() || normalizedEmail.length() > 254) {
			throw new IllegalStateException("ergo.bootstrap-admin.email is not a valid email address");
		}
		if (!password.equals(password.strip())) {
			throw new IllegalStateException("ergo.bootstrap-admin.password has leading or trailing whitespace");
		}
		if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
			throw new IllegalStateException(
					"ergo.bootstrap-admin.password must be %d-%d characters".formatted(MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH));
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new IllegalStateException("ergo.bootstrap-admin.password is too long for bcrypt (max 72 bytes)");
		}
		if (password.equals(DevDataSeeder.PASSWORD)) {
			throw new IllegalStateException("ergo.bootstrap-admin.password must not be the published dev demo password");
		}
		audit.reason("First-admin bootstrap (ARCHITECTURE.md §8, Phase 8e)");
		Company ergo = new Company();
		ergo.code = "ERGO";
		ergo.name = "ERGO";
		ergo.type = ERGO;
		ergo.status = CompanyStatus.ACTIVE;
		companies.save(ergo);

		User admin = new User();
		admin.companyId = ergo.id;
		admin.firstName = "ERGO";
		admin.lastName = "Admin";
		admin.email = normalizedEmail;
		admin.passwordHash = passwordEncoder.encode(password);
		admin.mustChangePassword = true; // the env-var password is never the password they actually keep using
		admin.roles.add(Role.ERGO_ADMIN);
		admin.defaultRole = Role.ERGO_ADMIN;
		admin.twoFactorEnabled = true; // mandatory for ERGO Admin (ARCHITECTURE.md §8, Phase 8g)
		users.save(admin);

		audit.record(User.class, admin.id, "BOOTSTRAP_ADMIN_CREATED", null, null, admin.email);
		audit.record(User.class, admin.id, "ROLE_ADDED", "roles", null, Role.ERGO_ADMIN.name());
		log.warn("Bootstrapped first ERGO Admin {} - must change password on first login. Remove the "
				+ "ergo.bootstrap-admin.* env vars now so this can never run again.", admin.email);
	}

}
