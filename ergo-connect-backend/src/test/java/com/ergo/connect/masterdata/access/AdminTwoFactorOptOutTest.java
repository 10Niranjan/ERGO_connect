package com.ergo.connect.masterdata.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;

/**
 * ergo.security.admin-2fa-required=false (the pilot switch, ARCHITECTURE.md §8 2026-11-06): any holder of ERGO_ADMIN signs
 * in with the password alone and the sign-in is audited, while every other check and everyone else's 2FA are unchanged.
 * The default (true) is covered by TwoFactorAuthTest, which runs without the property and still expects a challenge for
 * every ERGO Admin. Each login uses its own client IP so the per-IP login limiter never mixes tests up.
 */
@IntegrationTest
@TestPropertySource(properties = "ergo.security.admin-2fa-required=false")
class AdminTwoFactorOptOutTest {

	private static final AtomicInteger IP = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	CompanyRepository companies;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@Autowired
	OtpCodeStore otpCodes;

	@Autowired
	JdbcTemplate jdbc;

	Accounts accounts;

	@BeforeEach
	void setUp() {
		accounts = new Accounts(companies, users, encoder, mvc, otpCodes);
	}

	private ResultActions login(String email, String password) throws Exception {
		String ip = "10.77.0." + IP.incrementAndGet();
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.with(request -> {
				request.setRemoteAddr(ip);
				return request;
			})
			.content("""
					{"email": "%s", "password": "%s"}""".formatted(email, password)));
	}

	private int auditRows(String event, long userId) {
		return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE event_type = ? AND record_id = ?",
				Integer.class, event, userId);
	}

	@Test
	void anErgoAdminGetsASessionFromThePasswordAlone() throws Exception {
		User admin = accounts.user(accounts.company(CompanyType.ERGO), Role.ERGO_ADMIN);

		login(admin.email, Accounts.PASSWORD)
			.andExpect(status().isOk())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("accessToken")
				.doesNotContain("otpRequired"))
			.andExpect(result -> assertThat(result.getResponse().getHeader("Set-Cookie")).isNotBlank());
	}

	@Test
	void theSignInIsAuditedAndTheSelfHealIsSkipped() throws Exception {
		User admin = accounts.user(accounts.company(CompanyType.ERGO), Role.ERGO_ADMIN);
		assertThat(admin.twoFactorEnabled).isFalse();

		login(admin.email, Accounts.PASSWORD).andExpect(status().isOk());

		assertThat(auditRows("LOGIN_WITHOUT_SECOND_FACTOR", admin.id)).isEqualTo(1);
		assertThat(auditRows("TWO_FACTOR_ENABLED", admin.id)).isZero(); // the flag is not forced on while the rule is off
		assertThat(users.findById(admin.id).orElseThrow().twoFactorEnabled).isFalse();
	}

	@Test
	void anAdminWhoseFlagWasAlreadySetIsAlsoNotChallenged() throws Exception {
		// the bootstrap admin has twoFactorEnabled = true in the database from the day it was created
		User admin = accounts.user(accounts.company(CompanyType.ERGO), Role.ERGO_ADMIN);
		admin.twoFactorEnabled = true;
		users.save(admin);

		login(admin.email, Accounts.PASSWORD).andExpect(status().isOk())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("accessToken"));
	}

	@Test
	void aUserHoldingErgoAdminAsASecondaryRoleIsExemptToo() throws Exception {
		// the rule keys on held roles, not the active one: signing in under another role and switching can't bypass it either way
		User user = accounts.user(accounts.company(CompanyType.ERGO), Role.ERGO_RFQ, Role.ERGO_ADMIN);

		login(user.email, Accounts.PASSWORD).andExpect(status().isOk())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("otpRequired"));
	}

	@Test
	void aWrongPasswordIsStillRejectedForAnAdmin() throws Exception {
		User admin = accounts.user(accounts.company(CompanyType.ERGO), Role.ERGO_ADMIN);

		login(admin.email, "not-the-password").andExpect(status().isUnauthorized());
		assertThat(auditRows("LOGIN_WITHOUT_SECOND_FACTOR", admin.id)).isZero();
	}

	@Test
	void fiveWrongPasswordsLockTheAdminOutEvenForTheRightPassword() throws Exception {
		User admin = accounts.user(accounts.company(CompanyType.ERGO), Role.ERGO_ADMIN);

		for (int i = 0; i < 5; i++) {
			login(admin.email, "wrong-password-" + i).andExpect(status().isUnauthorized());
		}
		login(admin.email, Accounts.PASSWORD).andExpect(status().isUnauthorized());
		assertThat(auditRows("LOGIN_WITHOUT_SECOND_FACTOR", admin.id)).isZero();
	}

	@Test
	void anInactiveAdminIsRefused() throws Exception {
		User admin = accounts.user(accounts.company(CompanyType.ERGO), Role.ERGO_ADMIN);
		admin.active = false;
		users.save(admin);

		login(admin.email, Accounts.PASSWORD).andExpect(status().isUnauthorized());
	}

	@Test
	void everyoneElseWhoTurnedOnTwoFactorIsStillChallenged() throws Exception {
		User user = accounts.user(accounts.company(CompanyType.CUSTOMER), Role.CUSTOMER_RFQ);
		user.twoFactorEnabled = true;
		users.save(user);

		login(user.email, Accounts.PASSWORD)
			.andExpect(status().isOk())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString())
				.isEqualTo("{\"otpRequired\":true}"));
	}

}
