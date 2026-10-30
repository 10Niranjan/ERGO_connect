package com.ergo.connect.masterdata.access;

import static com.ergo.connect.common.security.CompanyType.CUSTOMER;
import static com.ergo.connect.common.security.CompanyType.ERGO;
import static com.ergo.connect.common.security.CompanyType.SUPPLIER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Super Admin "Login as" (docs/PERMISSIONS.md section 13): a view-only session for another user. It sees what that user
 * sees, can change nothing, and ends with the admin's own session, the 30 minutes, or any change to either account.
 */
@IntegrationTest
class ShadowLoginTests {

	static final String REASON = "Customer reports a missing RFQ";

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

	@Autowired
	AuditLogService audit;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	Accounts accounts;

	Company ergo;

	Company customer;

	Company supplier;

	User admin;

	MvcResult adminLogin;

	String adminBearer;

	@BeforeEach
	void setUp() throws Exception {
		accounts = new Accounts(companies, users, encoder, mvc, otpCodes);
		ergo = accounts.company(ERGO);
		customer = accounts.company(CUSTOMER);
		supplier = accounts.company(SUPPLIER);
		admin = accounts.user(ergo, Role.ERGO_ADMIN);
		adminLogin = accounts.login(admin);
		adminBearer = Accounts.bearer(adminLogin);
	}

	// ---- helpers

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body, String bearer) {
		return b.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private MvcResult start(long targetId) throws Exception {
		return mvc.perform(json(post("/api/users/{id}/login-as", targetId), "{\"reason\": \"" + REASON + "\"}", adminBearer))
			.andExpect(status().isOk())
			.andReturn();
	}

	private static Cookie shadowCookie(MvcResult started) {
		String header = started.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
			.filter(h -> h.startsWith("ergo_shadow=")).findFirst().orElseThrow();
		return new Cookie("ergo_shadow", header.substring(header.indexOf('=') + 1, header.indexOf(';')));
	}

	private int statusOf(String bearer, String path) throws Exception {
		return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse().getStatus();
	}

	private int refreshStatus(Cookie... cookies) throws Exception {
		return mvc.perform(post("/api/auth/refresh").cookie(cookies)).andReturn().getResponse().getStatus();
	}

	private long auditRows(String event, long recordId) {
		return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE event_type = ? AND record_id = ?", Long.class,
				event, recordId);
	}

	// ---- who may start one, and for whom

	@Test
	void onlyASuperAdminActingAsOneCanStart_everyOtherRoleAndTheAdminInAnotherRoleAreRefused() throws Exception {
		User target = accounts.user(customer, Role.CUSTOMER_RFQ);
		String body = "{\"reason\": \"" + REASON + "\"}";
		for (Role role : new Role[] { Role.ERGO_MANAGEMENT, Role.ERGO_KAM, Role.ERGO_RFQ, Role.ERGO_NPD, Role.ERGO_OFP,
				Role.ERGO_QUALITY }) {
			mvc.perform(json(post("/api/users/{id}/login-as", target.id), body, accounts.bearer(accounts.user(ergo, role))))
				.andExpect(status().isForbidden());
		}
		mvc.perform(json(post("/api/users/{id}/login-as", target.id), body, accounts.bearer(accounts.user(customer, Role.CUSTOMER_NPD))))
			.andExpect(status().isForbidden());
		// an admin who is working as another of their roles is not acting as Super Admin
		User both = accounts.user(ergo, Role.ERGO_RFQ, Role.ERGO_ADMIN); // signs in as ERGO_RFQ
		mvc.perform(json(post("/api/users/{id}/login-as", target.id), body, accounts.bearer(both)))
			.andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_sessions WHERE user_id = ? AND impersonated_by_user_id IS NOT NULL",
				Long.class, target.id)).isZero();
	}

	@Test
	void theRightPeopleCanBeViewedAndTheWrongOnesCannot() throws Exception {
		User inactive = accounts.user(customer, Role.CUSTOMER_RFQ);
		inactive.active = false;
		users.save(inactive);
		Company dormant = accounts.company(SUPPLIER);
		User ofDormant = accounts.user(dormant, Role.SUPPLIER_RFQ);
		dormant.status = CompanyStatus.INACTIVE;
		companies.save(dormant);
		User otherAdmin = accounts.user(ergo, Role.ERGO_ADMIN);
		User deleted = accounts.user(customer, Role.CUSTOMER_RFQ);
		jdbc.update("UPDATE users SET active = false, deleted_at = now(), deleted_by_user_id = ? WHERE user_id = ?", admin.id, deleted.id);

		String ok = "{\"reason\": \"" + REASON + "\"}";
		for (long id : new long[] { admin.id, otherAdmin.id, inactive.id, ofDormant.id }) {
			mvc.perform(json(post("/api/users/{id}/login-as", id), ok, adminBearer)).andExpect(status().isUnprocessableEntity());
		}
		mvc.perform(json(post("/api/users/{id}/login-as", deleted.id), ok, adminBearer)).andExpect(status().isNotFound());
		mvc.perform(json(post("/api/users/{id}/login-as", 987654321L), ok, adminBearer)).andExpect(status().isNotFound());

		// the reason is required: missing, blank, whitespace only, too short
		User target = accounts.user(customer, Role.CUSTOMER_RFQ);
		for (String body : new String[] { "{}", "{\"reason\": \"\"}", "{\"reason\": \"      \"}", "{\"reason\": \"abc\"}" }) {
			mvc.perform(json(post("/api/users/{id}/login-as", target.id), body, adminBearer)).andExpect(status().is4xxClientError());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_sessions WHERE user_id = ? AND impersonated_by_user_id IS NOT NULL",
				Long.class, target.id)).isZero();
		// the Users screen offers the button only where the call can work
		for (User viewable : List.of(target)) {
			mvc.perform(get("/api/users/{id}", viewable.id).header(HttpHeaders.AUTHORIZATION, adminBearer))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("LOGIN_AS")));
		}
		for (User no : List.of(admin, otherAdmin, inactive, ofDormant)) {
			mvc.perform(get("/api/users/{id}", no.id).header(HttpHeaders.AUTHORIZATION, adminBearer))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("LOGIN_AS"))));
		}
	}

	// ---- what a shadow session is

	@Test
	void itIsTheViewedUsersSessionAuditedWithTheReasonAndTheAdminKeepsTheirOwn() throws Exception {
		User target = accounts.user(customer, Role.CUSTOMER_RFQ);
		MvcResult started = start(target.id);
		String body = started.getResponse().getContentAsString();
		assertThat((Integer) JsonPath.read(body, "$.user.id")).isEqualTo(target.id.intValue());
		assertThat((String) JsonPath.read(body, "$.activeRole")).isEqualTo("CUSTOMER_RFQ");
		assertThat((String) JsonPath.read(body, "$.impersonation.adminName")).isEqualTo(admin.firstName + " " + admin.lastName);
		assertThat((Boolean) JsonPath.read(body, "$.mustChangePassword")).isFalse();
		String header = started.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
			.filter(h -> h.startsWith("ergo_shadow=")).findFirst().orElseThrow();
		assertThat(header).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth");

		// audited as the admin's act on the viewed user, with the reason
		Map<String, Object> row = jdbc.queryForMap("""
				SELECT changed_by_user_id, changed_by_role_code, action_reason FROM audit_logs
				WHERE event_type = 'IMPERSONATION_STARTED' AND record_id = ?""", target.id);
		assertThat(((Number) row.get("changed_by_user_id")).longValue()).isEqualTo(admin.id);
		assertThat(row.get("changed_by_role_code")).isEqualTo("ERGO_ADMIN");
		assertThat(row.get("action_reason")).isEqualTo(REASON);

		// at most 30 minutes, and never longer than the admin's own session
		Map<String, Object> session = jdbc.queryForMap("""
				SELECT s.expires_at <= now() + interval '30 minutes 5 seconds' AS short_enough,
				       s.expires_at <= p.expires_at AS inside_parent, s.impersonation_reason
				FROM user_sessions s JOIN user_sessions p ON p.session_id = s.parent_session_id
				WHERE s.user_id = ? AND s.impersonated_by_user_id = ?""", target.id, admin.id);
		assertThat(session.get("short_enough")).isEqualTo(true);
		assertThat(session.get("inside_parent")).isEqualTo(true);

		// the admin's own token still works and is still the admin's
		assertThat(statusOf(adminBearer, "/api/permissions/version")).isNotEqualTo(401);
		assertThat((Integer) JsonPath.read(mvc.perform(post("/api/auth/refresh").cookie(Accounts.sessionCookie(adminLogin)))
			.andReturn().getResponse().getContentAsString(), "$.user.id")).isEqualTo(admin.id.intValue());
	}

	@Test
	void itSeesExactlyWhatTheViewedUserSees_notWhatTheAdminSees() throws Exception {
		User buyer = accounts.user(customer, Role.CUSTOMER_RFQ);
		User colleague = accounts.user(customer, Role.CUSTOMER_RFQ);
		User stranger = accounts.user(accounts.company(CUSTOMER), Role.CUSTOMER_RFQ);
		String shadow = Accounts.bearer(start(buyer.id));

		String list = mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, shadow)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		List<Integer> ids = JsonPath.read(list, "$[*].id");
		assertThat(ids).contains(buyer.id.intValue(), colleague.id.intValue()).doesNotContain(stranger.id.intValue());
		assertThat((List<Integer>) JsonPath.read(list, "$[*].companyId")).containsOnly(customer.id.intValue());

		// the admin's own powers are not available through it
		assertThat(statusOf(shadow, "/api/admin/permissions/CUSTOMER")).isEqualTo(403);
		String seen = mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, shadow)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String own = mvc.perform(get("/api/auth/access").header(HttpHeaders.AUTHORIZATION, adminBearer)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat((Map<String, String>) JsonPath.read(seen, "$.permissions")).isNotEqualTo(JsonPath.read(own, "$.permissions"));
	}

	// ---- view only

	@Test
	void everyEndpointThatIsNotARead_isRefusedUnderAShadowToken_exceptLeavingRenewingAndSwitchingRole() throws Exception {
		User target = accounts.user(customer, Role.CUSTOMER_RFQ, Role.CUSTOMER_NPD);
		String shadow = Accounts.bearer(start(target.id));
		Set<String> allowed = Set.of("POST /api/auth/refresh", "POST /api/auth/logout", "POST /api/auth/switch-role",
				"POST /api/auth/impersonation/end");

		List<String> notRefused = new ArrayList<>();
		int swept = 0;
		for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
			if (e.getKey().getPathPatternsCondition() == null) {
				continue;
			}
			Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
			for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.POST) : methods) {
				if (method == RequestMethod.GET || method == RequestMethod.HEAD || method == RequestMethod.OPTIONS) {
					continue;
				}
				for (String pattern : e.getKey().getPathPatternsCondition().getPatternValues()) {
					if (!pattern.startsWith("/api/") || allowed.contains(method + " " + pattern)) {
						continue;
					}
					swept++;
					String path = pattern.replaceAll("\\{[^/}]+}", "1");
					MvcResult r = mvc.perform(request(HttpMethod.valueOf(method.name()), path)
						.header(HttpHeaders.AUTHORIZATION, shadow).contentType(MediaType.APPLICATION_JSON).content("{}"))
						.andReturn();
					if (r.getResponse().getStatus() != 403 || !r.getResponse().getContentAsString().contains("View-only")) {
						notRefused.add(method + " " + pattern + " -> " + r.getResponse().getStatus());
					}
				}
			}
		}
		assertThat(swept).as("endpoints swept").isGreaterThan(100);
		assertThat(notRefused).as("endpoints a view-only session got past the filter on").isEmpty();

		// the three allowed ones work, and switching role keeps it view-only
		String switched = mvc.perform(json(post("/api/auth/switch-role"), "{\"role\": \"CUSTOMER_NPD\"}", shadow))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat((String) JsonPath.read(switched, "$.activeRole")).isEqualTo("CUSTOMER_NPD");
		assertThat((String) JsonPath.read(switched, "$.impersonation.adminName")).isNotBlank();
		String again = "Bearer " + JsonPath.read(switched, "$.accessToken");
		mvc.perform(json(post("/api/users/{id}/login-as", target.id), "{\"reason\": \"" + REASON + "\"}", again))
			.andExpect(status().isForbidden()); // still view-only, and not an admin anyway
		// the role the viewed user does not hold cannot be switched to
		mvc.perform(json(post("/api/auth/switch-role"), "{\"role\": \"CUSTOMER_OFP\"}", again)).andExpect(status().isForbidden());
	}

	@Test
	void theAuditWriterRefusesToWriteForAViewOnlySession_soNoUnforeseenWritePathCanSave() {
		CurrentUser shadow = new CurrentUser(1L, 1L, CompanyType.CUSTOMER, Role.CUSTOMER_RFQ, 1L, 2L);
		assertThatThrownBy(() -> CurrentUser.runAs(shadow, () -> {
			audit.record(User.class, 1L, "ANYTHING", null, null, null);
			return null;
		})).isInstanceOf(ApiException.class).hasMessageContaining("View-only");
	}

	// ---- how it ends

	@Test
	void exitEndsOnlyTheViewAndIsAuditedAsTheAdmin() throws Exception {
		User target = accounts.user(supplier, Role.SUPPLIER_RFQ);
		MvcResult started = start(target.id);
		String shadow = Accounts.bearer(started);

		// a normal session has nothing to exit
		mvc.perform(post("/api/auth/impersonation/end").header(HttpHeaders.AUTHORIZATION, adminBearer))
			.andExpect(status().isUnprocessableEntity());

		MvcResult ended = mvc.perform(post("/api/auth/impersonation/end").header(HttpHeaders.AUTHORIZATION, shadow))
			.andExpect(status().isNoContent()).andReturn();
		assertThat(ended.getResponse().getHeader(HttpHeaders.SET_COOKIE)).startsWith("ergo_shadow=;").contains("Max-Age=0");
		assertThat(statusOf(shadow, "/api/auth/access")).isEqualTo(401);
		assertThat(statusOf(adminBearer, "/api/auth/access")).isEqualTo(200);
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT changed_by_user_id, changed_by_role_code FROM audit_logs WHERE event_type = 'IMPERSONATION_ENDED' AND record_id = ?",
				target.id);
		assertThat(((Number) row.get("changed_by_user_id")).longValue()).isEqualTo(admin.id);
		assertThat(row.get("changed_by_role_code")).isEqualTo("ERGO_ADMIN");
	}

	@Test
	void refreshPrefersALiveViewAndOtherwiseFallsBackToTheAdminsOwnSession() throws Exception {
		User target = accounts.user(customer, Role.CUSTOMER_RFQ);
		MvcResult started = start(target.id);
		Cookie shadowCookie = shadowCookie(started);
		Cookie adminCookie = Accounts.sessionCookie(adminLogin);

		String live = mvc.perform(post("/api/auth/refresh").cookie(adminCookie, shadowCookie)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat((Integer) JsonPath.read(live, "$.user.id")).isEqualTo(target.id.intValue());
		assertThat((String) JsonPath.read(live, "$.impersonation.adminName")).isNotBlank();

		// without the admin's own cookie a view cannot be renewed (and is not left open)
		assertThat(refreshStatus(shadowCookie)).isEqualTo(401);
		// a view-only token is not accepted as a login cookie
		assertThat(refreshStatus(new Cookie("ergo_session", shadowCookie.getValue()))).isEqualTo(401);

		// a new view replaces the old one: only one at a time
		MvcResult second = start(accounts.user(customer, Role.CUSTOMER_RFQ).id);
		assertThat(statusOf(Accounts.bearer(started), "/api/auth/access")).isEqualTo(401);
		assertThat(statusOf(Accounts.bearer(second), "/api/auth/access")).isEqualTo(200);

		// once it has ended the same cookie lands back on the admin and the cookie is dropped
		jdbc.update("UPDATE user_sessions SET revoked_at = now() WHERE impersonated_by_user_id = ?", admin.id);
		MvcResult back = mvc.perform(post("/api/auth/refresh").cookie(adminCookie, shadowCookie(second))).andExpect(status().isOk())
			.andReturn();
		String body = back.getResponse().getContentAsString();
		assertThat((Integer) JsonPath.read(body, "$.user.id")).isEqualTo(admin.id.intValue());
		assertThat((Object) JsonPath.read(body, "$.impersonation")).isNull();
		assertThat(back.getResponse().getHeader(HttpHeaders.SET_COOKIE)).startsWith("ergo_shadow=;");
	}

	@Test
	void theViewEndsAtThirtyMinutesAndWhenEitherAccountChanges() throws Exception {
		Cookie adminCookie = Accounts.sessionCookie(adminLogin);

		// time is up
		User t1 = accounts.user(customer, Role.CUSTOMER_RFQ);
		MvcResult s1 = start(t1.id);
		jdbc.update("UPDATE user_sessions SET expires_at = now() - interval '1 second' WHERE impersonated_by_user_id = ?", admin.id);
		assertThat(statusOf(Accounts.bearer(s1), "/api/auth/access")).isEqualTo(401);
		assertThat((Integer) JsonPath.read(mvc.perform(post("/api/auth/refresh").cookie(adminCookie, shadowCookie(s1)))
			.andReturn().getResponse().getContentAsString(), "$.user.id")).isEqualTo(admin.id.intValue());

		// the viewed user is deactivated, or their company is, or their password is reset by the admin
		User t2 = accounts.user(customer, Role.CUSTOMER_RFQ);
		String s2 = Accounts.bearer(start(t2.id));
		assertThat(statusOf(s2, "/api/auth/access")).isEqualTo(200);
		jdbc.update("UPDATE users SET active = false WHERE user_id = ?", t2.id);
		assertThat(statusOf(s2, "/api/auth/access")).isEqualTo(401);

		User t3 = accounts.user(customer, Role.CUSTOMER_RFQ);
		String s3 = Accounts.bearer(start(t3.id));
		jdbc.update("UPDATE companies SET status = 'INACTIVE' WHERE company_id = ?", customer.id);
		assertThat(statusOf(s3, "/api/auth/access")).isEqualTo(401);
		jdbc.update("UPDATE companies SET status = 'ACTIVE' WHERE company_id = ?", customer.id);

		User t4 = accounts.user(supplier, Role.SUPPLIER_RFQ);
		String s4 = Accounts.bearer(start(t4.id));
		mvc.perform(post("/api/users/{id}/reset-password", t4.id).header(HttpHeaders.AUTHORIZATION, adminBearer))
			.andExpect(status().isOk());
		assertThat(statusOf(s4, "/api/auth/access")).isEqualTo(401);

		// the admin's own login ends: logging out, or losing the role, or being deactivated, ends the view at once
		User t5 = accounts.user(customer, Role.CUSTOMER_RFQ);
		MvcResult s5 = start(t5.id);
		mvc.perform(post("/api/auth/logout").cookie(adminCookie, shadowCookie(s5))).andExpect(status().isNoContent());
		assertThat(statusOf(Accounts.bearer(s5), "/api/auth/access")).isEqualTo(401);

		User admin2 = accounts.user(ergo, Role.ERGO_ADMIN);
		MvcResult login2 = accounts.login(admin2);
		String bearer2 = Accounts.bearer(login2);
		MvcResult s6 = mvc.perform(json(post("/api/users/{id}/login-as", t1.id), "{\"reason\": \"" + REASON + "\"}", bearer2))
			.andExpect(status().isOk()).andReturn();
		String shadow6 = Accounts.bearer(s6);
		assertThat(statusOf(shadow6, "/api/auth/access")).isEqualTo(200);
		jdbc.update("DELETE FROM user_role_mappings WHERE user_id = ? AND role_code = 'ERGO_ADMIN'", admin2.id);
		assertThat(statusOf(shadow6, "/api/auth/access")).isEqualTo(401);
		jdbc.update("INSERT INTO user_role_mappings (user_id, role_code) VALUES (?, 'ERGO_ADMIN')", admin2.id);

		User admin3 = accounts.user(ergo, Role.ERGO_ADMIN);
		String bearer3 = accounts.bearer(admin3);
		String shadow7 = Accounts.bearer(mvc.perform(json(post("/api/users/{id}/login-as", t1.id), "{\"reason\": \"" + REASON + "\"}", bearer3))
			.andExpect(status().isOk()).andReturn());
		jdbc.update("UPDATE users SET active = false WHERE user_id = ?", admin3.id);
		assertThat(statusOf(shadow7, "/api/auth/access")).isEqualTo(401);
	}

	@Test
	void theDatabaseHoldsTheShapeEvenForManualSql() {
		User target = accounts.user(customer, Role.CUSTOMER_RFQ);
		// all three columns or none
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO user_sessions (user_id, token_hash, active_role_code, expires_at, impersonated_by_user_id)
				VALUES (?, repeat('a', 64), 'CUSTOMER_RFQ', now() + interval '1 hour', ?)""", target.id, admin.id))
			.hasMessageContaining("ck_user_sessions_impersonation");
		// nobody views as themselves
		Long own = jdbc.queryForObject("""
				INSERT INTO user_sessions (user_id, token_hash, active_role_code, expires_at)
				VALUES (?, repeat('b', 64), 'ERGO_ADMIN', now() + interval '1 hour') RETURNING session_id""", Long.class, admin.id);
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO user_sessions (user_id, token_hash, active_role_code, expires_at, impersonated_by_user_id,
				                           parent_session_id, impersonation_reason)
				VALUES (?, repeat('c', 64), 'ERGO_ADMIN', now() + interval '1 hour', ?, ?, 'x')""", admin.id, admin.id, own))
			.hasMessageContaining("ck_user_sessions_impersonation");
	}

	// ---- security review follow-ups

	@Test
	void anAdminWhoSwitchesTheirOwnRoleEndsTheViewAndCannotStartANewOneWithTheOlderToken() throws Exception {
		User both = accounts.user(ergo, Role.ERGO_ADMIN, Role.ERGO_RFQ);
		String adminToken = accounts.bearer(both); // signs in as ERGO_ADMIN
		User target = accounts.user(customer, Role.CUSTOMER_RFQ);
		String shadow = "Bearer " + JsonPath.read(mvc.perform(json(post("/api/users/{id}/login-as", target.id),
				"{\"reason\": \"" + REASON + "\"}", adminToken)).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(), "$.accessToken");
		assertThat(statusOf(shadow, "/api/auth/access")).isEqualTo(200);

		mvc.perform(json(post("/api/auth/switch-role"), "{\"role\": \"ERGO_RFQ\"}", adminToken)).andExpect(status().isOk());
		assertThat(statusOf(shadow, "/api/auth/access")).isEqualTo(401); // the admin is no longer acting as Super Admin
		// the access token from before the switch still says ERGO_ADMIN, but the session no longer does
		mvc.perform(json(post("/api/users/{id}/login-as", target.id), "{\"reason\": \"" + REASON + "\"}", adminToken))
			.andExpect(status().isForbidden());
	}

	@Test
	void aViewCookieWithSomeoneElsesLoginCookieIsEndedNotHonoured() throws Exception {
		User target = accounts.user(customer, Role.CUSTOMER_RFQ);
		Cookie shadowCookie = shadowCookie(start(target.id));
		MvcResult other = accounts.login(accounts.user(ergo, Role.ERGO_ADMIN));

		String body = mvc.perform(post("/api/auth/refresh").cookie(Accounts.sessionCookie(other), shadowCookie))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat((Object) JsonPath.read(body, "$.impersonation")).isNull(); // the other admin's own session answered
		assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM user_sessions WHERE impersonated_by_user_id = ?",
				Boolean.class, admin.id)).isTrue();
	}

	@Test
	void aViewedUserMadeSuperAdminMidViewCannotBecomeOneInsideTheView() throws Exception {
		User target = accounts.user(ergo, Role.ERGO_RFQ);
		MvcResult started = start(target.id);
		String shadow = Accounts.bearer(started);
		jdbc.update("INSERT INTO user_role_mappings (user_id, role_code) VALUES (?, 'ERGO_ADMIN')", target.id);

		mvc.perform(json(post("/api/auth/switch-role"), "{\"role\": \"ERGO_ADMIN\"}", shadow)).andExpect(status().isForbidden());
		// and the view itself no longer stands the next time it is renewed
		String body = mvc.perform(post("/api/auth/refresh").cookie(Accounts.sessionCookie(adminLogin), shadowCookie(started)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat((Object) JsonPath.read(body, "$.impersonation")).isNull();
		assertThat((Integer) JsonPath.read(body, "$.user.id")).isEqualTo(admin.id.intValue());
	}

	@Test
	void everyEndOfAViewIsAuditedWithItsCause() throws Exception {
		Cookie adminCookie = Accounts.sessionCookie(adminLogin);
		User exited = accounts.user(customer, Role.CUSTOMER_RFQ);
		mvc.perform(post("/api/auth/impersonation/end").header(HttpHeaders.AUTHORIZATION, Accounts.bearer(start(exited.id))))
			.andExpect(status().isNoContent());

		User replaced = accounts.user(customer, Role.CUSTOMER_RFQ);
		User replacing = accounts.user(customer, Role.CUSTOMER_RFQ);
		start(replaced.id);
		start(replacing.id);

		MvcResult viewed = start(accounts.user(supplier, Role.SUPPLIER_RFQ).id);
		long loggedOut = jdbc.queryForObject("SELECT user_id FROM user_sessions WHERE session_id = (SELECT max(session_id) FROM user_sessions WHERE impersonated_by_user_id = ?)",
				Long.class, admin.id);
		mvc.perform(post("/api/auth/logout").cookie(adminCookie, shadowCookie(viewed))).andExpect(status().isNoContent());

		for (Object[] expected : new Object[][] { { exited.id, "EXIT" }, { replaced.id, "REPLACED" }, { loggedOut, "LOGOUT" } }) {
			Map<String, Object> row = jdbc.queryForMap(
					"SELECT new_value, changed_by_user_id FROM audit_logs WHERE event_type = 'IMPERSONATION_ENDED' AND record_id = ?",
					expected[0]);
			assertThat(row.get("new_value")).isEqualTo(expected[1]);
			assertThat(((Number) row.get("changed_by_user_id")).longValue()).isEqualTo(admin.id);
		}
	}

}
