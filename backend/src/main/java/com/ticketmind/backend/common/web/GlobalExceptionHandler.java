package com.ticketmind.backend.common.web;

import com.ticketmind.backend.common.exception.ApiException;
import com.ticketmind.backend.common.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiError> handleApi(ApiException ex) {
		HttpHeaders headers = new HttpHeaders();
		if (ex instanceof RateLimitExceededException rl) {
			headers.add(HttpHeaders.RETRY_AFTER, Long.toString(rl.retryAfterSeconds()));
		}
		return ResponseEntity
				.status(ex.status())
				.headers(headers)
				.body(ApiError.of(ex.status().value(), ex.code()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
		List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
				.map(fe -> new ApiError.FieldError(fe.getField(),
						fe.getCode() == null ? "invalid" : fe.getCode().toLowerCase()))
				.toList();
		return ResponseEntity
				.status(HttpStatus.BAD_REQUEST)
				.body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "validation_error", fieldErrors));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
		return ResponseEntity
				.status(HttpStatus.NOT_FOUND)
				.body(ApiError.of(HttpStatus.NOT_FOUND.value(), "not_found"));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnknown(Exception ex) {
		// Log the real exception for operators; return only a generic code
		// to the client so internal details (class names, messages, stack
		// traces) do not leak across the security boundary.
		log.error("Unhandled exception", ex);
		return ResponseEntity
				.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "internal_error"));
	}
}
