package com.ergo.connect.masterdata.api;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.masterdata.access.AuthDtos.ChangePasswordRequest;
import com.ergo.connect.masterdata.access.AuthDtos.ForgotPasswordRequest;
import com.ergo.connect.masterdata.access.AuthDtos.LoginRequest;
import com.ergo.connect.masterdata.access.AuthDtos.OtpChallengeResponse;
import com.ergo.connect.masterdata.access.AuthDtos.ResetPasswordRequest;
import com.ergo.connect.masterdata.access.AuthDtos.SessionResponse;
import com.ergo.connect.masterdata.access.AuthDtos.SwitchRoleRequest;
import com.ergo.connect.masterdata.access.AuthDtos.TwoFactorRequest;
import com.ergo.connect.masterdata.access.AuthDtos.VerifyOtpRequest;
import com.ergo.connect.masterdata.access.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** Login flow (SRS §12.4). Access rules for these paths are in SecurityConfig. */
@RestController
@RequestMapping("/api/auth")
class AuthController {

	/** HttpOnly + SameSite=Strict + only sent to /api/auth: scripts cannot read it, other sites cannot use it. */
	static final String SESSION_COOKIE = "ergo_session";

	private final AuthService auth;

	AuthController(AuthService auth) {
		this.auth = auth;
	}

	@PostMapping("/login")
	ResponseEntity<Object> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
		AuthService.LoginResult result = auth.login(body.email(), body.password(), request.getRemoteAddr());
		if (result.otpRequired()) {
			return ResponseEntity.ok(new OtpChallengeResponse(true));
		}
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, sessionCookie(result.sessionToken(), result.sessionTtl()))
			.body(result.session());
	}

	@PostMapping("/verify-otp")
	ResponseEntity<SessionResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest body, HttpServletRequest request) {
		AuthService.LoginResult result = auth.verifyOtp(body.email(), body.code(), request.getRemoteAddr());
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, sessionCookie(result.sessionToken(), result.sessionTtl()))
			.body(result.session());
	}

	@PostMapping("/2fa")
	ResponseEntity<Void> setTwoFactor(@Valid @RequestBody TwoFactorRequest body) {
		auth.setTwoFactor(body.enabled());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/refresh")
	SessionResponse refresh(@CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
		if (sessionToken == null) {
			throw ApiException.unauthorized("Not signed in");
		}
		return auth.refresh(sessionToken);
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(@CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
		if (sessionToken != null) {
			auth.logout(sessionToken);
		}
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO)).build();
	}

	@PostMapping("/switch-role")
	SessionResponse switchRole(@Valid @RequestBody SwitchRoleRequest body) {
		return auth.switchRole(body.role());
	}

	@PostMapping("/change-password")
	SessionResponse changePassword(@Valid @RequestBody ChangePasswordRequest body) {
		return auth.changePassword(body.currentPassword(), body.newPassword());
	}

	@PostMapping("/forgot-password")
	ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest body, HttpServletRequest request) {
		// captured here, not in the @Async handler: RequestContextHolder (and so AuditLogService's own ip/channel
		// lookup) is empty once that work moves off this request thread
		auth.forgotPassword(body.email(), request.getRemoteAddr(), AuditLogService.channelOf(request));
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/reset-password")
	ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest body) {
		auth.resetPassword(body.token(), body.newPassword());
		return ResponseEntity.noContent().build();
	}

	private static String sessionCookie(String value, Duration maxAge) {
		return ResponseCookie.from(SESSION_COOKIE, value)
			.httpOnly(true)
			.secure(true) // browsers accept Secure cookies on http://localhost, so this holds in dev too
			.sameSite("Strict")
			.path("/api/auth")
			.maxAge(maxAge)
			.build()
			.toString();
	}

}
