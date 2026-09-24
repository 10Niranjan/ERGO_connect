package com.ergo.connect.common.exception;

import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Spring's RFC 9457 error handling, plus one change: a rejected request body says which fields failed
 * ("primaryContactPhone: size must be between 0 and 30") instead of only "Invalid request content".
 */
@RestControllerAdvice
class ValidationErrorHandler extends ResponseEntityExceptionHandler {

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

}
