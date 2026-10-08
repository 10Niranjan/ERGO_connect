package com.ergo.connect.masterdata.access;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.SecurityProperties;

/**
 * Phase 8f (ARCHITECTURE.md §8): the branching logic (who gets a token and who doesn't) in isolation, with
 * mocks - faster and more deterministic than exercising every case through the real async endpoint. No Spring
 * context, no thread pool, no timing.
 */
class PasswordResetRequestHandlerTest {

	private final UserRepository users = mock(UserRepository.class);

	private final CompanyRepository companies = mock(CompanyRepository.class);

	private final PasswordResetTokenStore resetTokens = mock(PasswordResetTokenStore.class);

	private final PasswordResetMailer mailer = mock(PasswordResetMailer.class);

	private final AuditLogService audit = mock(AuditLogService.class);

	private final SecurityProperties props = new SecurityProperties("x".repeat(32), Duration.ofMinutes(15),
			Duration.ofHours(12), Duration.ofMinutes(30));

	private PasswordResetRequestHandler handler() {
		return new PasswordResetRequestHandler(users, companies, resetTokens, mailer, audit, props,
				"https://app.test");
	}

	@Test
	void sendsAResetLinkForAnActiveCustomerUser() {
		User user = user(7L, 1L, "buyer@test.dev");
		when(users.findByEmail("buyer@test.dev")).thenReturn(Optional.of(user));
		when(companies.findById(1L)).thenReturn(Optional.of(company(1L, CompanyType.CUSTOMER, CompanyStatus.ACTIVE)));
		when(resetTokens.create(eq(7L), any())).thenReturn("raw-token");

		handler().handle("buyer@test.dev", "203.0.113.1", "API");

		verify(resetTokens).create(eq(7L), any());
		verify(mailer).sendResetLink(eq("buyer@test.dev"), eq("https://app.test/reset-password#token=raw-token"),
				any());
		verify(audit).record(eq(User.class), eq(7L), eq("PASSWORD_RESET_REQUESTED"), isNull(), isNull(), isNull(),
				eq("203.0.113.1"), eq("API"));
	}

	@Test
	void stripsATrailingSlashFromTheConfiguredBaseUrl() {
		PasswordResetRequestHandler handler = new PasswordResetRequestHandler(users, companies, resetTokens, mailer,
				audit, props, "https://app.test/");
		User user = user(7L, 1L, "buyer@test.dev");
		when(users.findByEmail("buyer@test.dev")).thenReturn(Optional.of(user));
		when(companies.findById(1L)).thenReturn(Optional.of(company(1L, CompanyType.CUSTOMER, CompanyStatus.ACTIVE)));
		when(resetTokens.create(eq(7L), any())).thenReturn("raw-token");

		handler.handle("buyer@test.dev", "203.0.113.1", "API");

		verify(mailer).sendResetLink(eq("buyer@test.dev"), eq("https://app.test/reset-password#token=raw-token"),
				any());
	}

	@Test
	void doesNothingForAnUnknownEmail() {
		when(users.findByEmail("nobody@test.dev")).thenReturn(Optional.empty());

		handler().handle("nobody@test.dev", "203.0.113.1", "API");

		verifyNoInteractions(resetTokens, mailer, audit);
	}

	@Test
	void doesNothingForAnInactiveUser() {
		User user = user(7L, 1L, "buyer@test.dev");
		user.active = false;
		when(users.findByEmail("buyer@test.dev")).thenReturn(Optional.of(user));
		when(companies.findById(1L)).thenReturn(Optional.of(company(1L, CompanyType.CUSTOMER, CompanyStatus.ACTIVE)));

		handler().handle("buyer@test.dev", "203.0.113.1", "API");

		verifyNoInteractions(resetTokens, mailer, audit);
	}

	@Test
	void doesNothingForAUserOfANonActiveCompany() {
		User user = user(7L, 1L, "buyer@test.dev");
		when(users.findByEmail("buyer@test.dev")).thenReturn(Optional.of(user));
		when(companies.findById(1L))
			.thenReturn(Optional.of(company(1L, CompanyType.CUSTOMER, CompanyStatus.PENDING_APPROVAL)));

		handler().handle("buyer@test.dev", "203.0.113.1", "API");

		verifyNoInteractions(resetTokens, mailer, audit);
	}

	/** Security review decision (ARCHITECTURE.md §8): ERGO-side access stays gated by another admin's manual
	 * reset (SRS §12.13.1), not just one inbox. */
	@Test
	void doesNothingForAnErgoCompanyUser() {
		User user = user(7L, 1L, "admin@test.dev");
		when(users.findByEmail("admin@test.dev")).thenReturn(Optional.of(user));
		when(companies.findById(1L)).thenReturn(Optional.of(company(1L, CompanyType.ERGO, CompanyStatus.ACTIVE)));

		handler().handle("admin@test.dev", "203.0.113.1", "API");

		verifyNoInteractions(resetTokens, mailer, audit);
	}

	private static User user(long id, long companyId, String email) {
		User user = new User();
		user.id = id;
		user.companyId = companyId;
		user.email = email;
		user.active = true;
		return user;
	}

	private static Company company(long id, CompanyType type, CompanyStatus status) {
		Company company = new Company();
		company.id = id;
		company.type = type;
		company.status = status;
		return company;
	}

}
