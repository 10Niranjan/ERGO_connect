package com.ergo.connect.masterdata.access;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;

/** Phase 8e (ARCHITECTURE.md §8): no Spring context needed, repositories are mocked. */
class AdminBootstrapRunnerTest {

	private static final String VALID_PASSWORD = "s3cret-pass"; // 11 chars, meets the 10-64 policy

	private final CompanyRepository companies = mock(CompanyRepository.class);

	private final UserRepository users = mock(UserRepository.class);

	private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

	private final AuditLogService audit = mock(AuditLogService.class);

	@Test
	void doesNothingWhenNotConfigured() {
		new AdminBootstrapRunner(companies, users, passwordEncoder, audit, "", "").run(null);

		verify(companies, never()).save(any());
		verify(users, never()).save(any());
		verifyNoInteractions(audit);
	}

	@Test
	void doesNothingWhenOnlyTheEmailIsSet() {
		new AdminBootstrapRunner(companies, users, passwordEncoder, audit, "admin@ergo.example", "").run(null);

		verify(companies, never()).save(any());
		verifyNoInteractions(audit);
	}

	@Test
	void doesNothingWhenOnlyThePasswordIsSet() {
		new AdminBootstrapRunner(companies, users, passwordEncoder, audit, "", VALID_PASSWORD).run(null);

		verify(companies, never()).save(any());
		verifyNoInteractions(audit);
	}

	@Test
	void doesNothingWhenACompanyAlreadyExists() {
		when(companies.count()).thenReturn(1L);

		new AdminBootstrapRunner(companies, users, passwordEncoder, audit, "admin@ergo.example", VALID_PASSWORD)
			.run(null);

		verify(companies, never()).save(any());
		verify(users, never()).save(any());
		verifyNoInteractions(audit);
	}

	@Test
	void theCompanyCountGuardIsCheckedBeforeAnyValidation() {
		when(companies.count()).thenReturn(1L); // an existing company must win even over an otherwise-rejected password

		new AdminBootstrapRunner(companies, users, passwordEncoder, audit, "admin@ergo.example", "short").run(null);

		verify(companies, never()).save(any());
	}

	@Test
	void rejectsAMalformedEmail() {
		when(companies.count()).thenReturn(0L);

		assertThatThrownBy(() -> new AdminBootstrapRunner(companies, users, passwordEncoder, audit, "not-an-email",
				VALID_PASSWORD).run(null)).isInstanceOf(IllegalStateException.class);
		verify(companies, never()).save(any());
	}

	@Test
	void rejectsAPasswordWithLeadingOrTrailingWhitespace() {
		when(companies.count()).thenReturn(0L);

		assertThatThrownBy(() -> new AdminBootstrapRunner(companies, users, passwordEncoder, audit, "admin@ergo.example",
				VALID_PASSWORD + " ").run(null)).isInstanceOf(IllegalStateException.class);
		verify(companies, never()).save(any());
	}

	@Test
	void rejectsAPasswordShorterThanThePolicyMinimum() {
		when(companies.count()).thenReturn(0L);

		assertThatThrownBy(() -> new AdminBootstrapRunner(companies, users, passwordEncoder, audit, "admin@ergo.example",
				"short123").run(null)).isInstanceOf(IllegalStateException.class);
		verify(companies, never()).save(any());
	}

	@Test
	void rejectsAPasswordTooLongForBcrypt() {
		when(companies.count()).thenReturn(0L);
		String tooLong = "x".repeat(73);

		assertThatThrownBy(
				() -> new AdminBootstrapRunner(companies, users, passwordEncoder, audit, "admin@ergo.example", tooLong)
					.run(null))
			.isInstanceOf(IllegalStateException.class);
		verify(companies, never()).save(any());
	}

	@Test
	void rejectsThePublishedDevDemoPassword() {
		when(companies.count()).thenReturn(0L);

		assertThatThrownBy(() -> new AdminBootstrapRunner(companies, users, passwordEncoder, audit, "admin@ergo.example",
				DevDataSeeder.PASSWORD).run(null)).isInstanceOf(IllegalStateException.class);
		verify(companies, never()).save(any());
	}

	@Test
	void createsTheErgoCompanyAndAnErgoAdminWhoMustChangePassword() {
		when(companies.count()).thenReturn(0L);
		when(passwordEncoder.encode(VALID_PASSWORD)).thenReturn("hashed");
		when(companies.save(any())).thenAnswer(inv -> {
			Company c = inv.getArgument(0);
			c.id = 1L;
			return c;
		});
		when(users.save(any())).thenAnswer(inv -> {
			User u = inv.getArgument(0);
			u.id = 2L;
			return u;
		});

		new AdminBootstrapRunner(companies, users, passwordEncoder, audit, "Admin@Ergo.Example", VALID_PASSWORD)
			.run(null);

		verify(companies).save(argThatCompany());
		verify(users).save(argThatUser());
		verify(audit).reason(any());
		verify(audit).record(eq(User.class), eq(2L), eq("BOOTSTRAP_ADMIN_CREATED"), isNull(), isNull(),
				eq("admin@ergo.example"));
		verify(audit).record(eq(User.class), eq(2L), eq("ROLE_ADDED"), eq("roles"), isNull(), eq("ERGO_ADMIN"));
	}

	private static Company argThatCompany() {
		return org.mockito.ArgumentMatchers.argThat(c -> c != null && c.type == CompanyType.ERGO && "ERGO".equals(c.code)
				&& c.status == CompanyStatus.ACTIVE);
	}

	private static User argThatUser() {
		return org.mockito.ArgumentMatchers.argThat(u -> u != null && u.email.equals("admin@ergo.example")
				&& u.passwordHash.equals("hashed") && u.mustChangePassword && u.roles.contains(Role.ERGO_ADMIN)
				&& u.defaultRole == Role.ERGO_ADMIN && u.companyId == 1L);
	}

}
