package com.ergo.connect.masterdata.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Fresh companies and users per test (tests share one database), signed in through the real login endpoint. */
class Accounts {

	static final String PASSWORD = "Correct-Horse-9";

	private static final AtomicInteger SEQ = new AtomicInteger();

	private final CompanyRepository companies;

	private final UserRepository users;

	private final MockMvc mvc;

	private final OtpCodeStore otpCodes;

	private final String hash;

	Accounts(CompanyRepository companies, UserRepository users, PasswordEncoder encoder, MockMvc mvc,
			OtpCodeStore otpCodes) {
		this.companies = companies;
		this.users = users;
		this.mvc = mvc;
		this.otpCodes = otpCodes;
		this.hash = encoder.encode(PASSWORD);
	}

	Company company(CompanyType type) {
		Company company = new Company();
		company.code = "T" + SEQ.incrementAndGet();
		company.name = "Test " + type + " " + company.code;
		company.type = type;
		company.status = CompanyStatus.ACTIVE;
		return companies.save(company);
	}

	User user(Company company, Role... roles) {
		int n = SEQ.incrementAndGet();
		User user = new User();
		user.companyId = company.id;
		user.firstName = "Test";
		user.lastName = "User " + n;
		user.email = "user" + n + "@test.dev";
		user.passwordHash = hash;
		user.mustChangePassword = false;
		user.roles.addAll(List.of(roles));
		user.defaultRole = roles[0];
		return users.save(user);
	}

	ResultActions login(String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "password": "%s"}""".formatted(email, password)));
	}

	/**
	 * A ready-to-use session, even for an ERGO_ADMIN account - which self-heals {@code twoFactorEnabled} to true
	 * on its very first login (ARCHITECTURE.md §8, Phase 8g security review finding M1). Transparently completes
	 * that OTP challenge with a code minted directly through {@link OtpCodeStore} (bypassing email, same as
	 * {@link TwoFactorAuthTest} does for its own cases), so every existing caller of this method/{@link #bearer}
	 * keeps working unchanged.
	 */
	MvcResult login(User user) throws Exception {
		MvcResult result = login(user.email, PASSWORD).andExpect(status().isOk()).andReturn();
		if (result.getResponse().getContentAsString().contains("\"otpRequired\":true")) {
			String code = otpCodes.create(user.id, Instant.now().plus(Duration.ofMinutes(5)));
			result = mvc.perform(post("/api/auth/verify-otp").contentType(MediaType.APPLICATION_JSON).content("""
					{"email": "%s", "code": "%s"}""".formatted(user.email, code)))
				.andExpect(status().isOk())
				.andReturn();
		}
		return result;
	}

	/** "Bearer ..." header value for the user's default role. */
	String bearer(User user) throws Exception {
		return bearer(login(user));
	}

	static String bearer(MvcResult result) throws Exception {
		return "Bearer " + JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
	}

	static Cookie sessionCookie(MvcResult login) {
		String header = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
		return new Cookie("ergo_session", value);
	}

}
