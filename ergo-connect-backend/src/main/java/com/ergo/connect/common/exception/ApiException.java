package com.ergo.connect.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Rendered by Spring as an RFC 9457 ProblemDetail. Messages are shown to users: never put internal data in them. */
public class ApiException extends ErrorResponseException {

	private ApiException(HttpStatus status, String detail) {
		super(status, ProblemDetail.forStatusAndDetail(status, detail), null);
	}

	/** Also used for records outside the caller's scope, so their existence is not revealed. */
	public static ApiException notFound(String what) {
		return new ApiException(HttpStatus.NOT_FOUND, what + " not found");
	}

	public static ApiException unauthorized(String detail) {
		return new ApiException(HttpStatus.UNAUTHORIZED, detail);
	}

	public static ApiException forbidden(String detail) {
		return new ApiException(HttpStatus.FORBIDDEN, detail);
	}

	public static ApiException conflict(String detail) {
		return new ApiException(HttpStatus.CONFLICT, detail);
	}

	public static ApiException tooManyRequests(String detail) {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, detail);
	}

	public static ApiException unprocessable(String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, detail);
	}

	public static ApiException serviceUnavailable(String detail) {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, detail);
	}

}
