package com.ergo.connect.common.exception;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.springframework.beans.TypeMismatchException;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Spring's RFC 9457 error handling, plus two additions: a rejected request body says which fields failed
 * ("primaryContactPhone: size must be between 0 and 30") instead of only "Invalid request content", and a change that
 * lost an optimistic-lock race is a 409 the user can retry.
 */
@RestControllerAdvice
class ValidationErrorHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ValidationErrorHandler.class);

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		String fields = ex.getFieldErrors()
			.stream()
			.map(error -> error.getField() + ": " + error.getDefaultMessage())
			.sorted()
			.collect(Collectors.joining("; "));
		if (!fields.isEmpty()) {
			ex.getBody().setDetail("Please correct: " + fields);
		}
		return super.handleMethodArgumentNotValid(ex, headers, status, request);
	}

	/** A malformed id or parameter in the URL: say so, not Spring's "Failed to convert 'id' with value: 'NaN'". */
	@Override
	protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The address or a value in it is not valid.");
		return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ProblemDetail concurrentChange() {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"Someone else changed this record at the same time. Reload it and try again.");
	}

	/**
	 * A unique index or constraint that fired is what a lost race between two writers looks like (a double click on
	 * "generate", two users submitting the same quote): the same retryable 409, not a 500. Logged, because the same
	 * exception would also be a real bug.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail conflictingChange(DataIntegrityViolationException e) {
		// the constraint name only: the driver's message carries the whole failing row (prices, hidden margins)
		String constraint = e.getCause() instanceof org.hibernate.exception.ConstraintViolationException c ? c.getConstraintName() : null;
		log.warn("Request rejected by a database constraint: {}", constraint);
		return concurrentChange();
	}

	/** Role refusals from {@code @PreAuthorize} are RFC 9457 documents like every other error. */
	@ExceptionHandler(AccessDeniedException.class)
	ProblemDetail forbidden() {
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Your role may not do this.");
	}

}
