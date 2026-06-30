package com.ticketmind.backend.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
		Instant timestamp,
		int status,
		String code,
		List<FieldError> fieldErrors) {

	public static ApiError of(int status, String code) {
		return new ApiError(Instant.now(), status, code, null);
	}

	public static ApiError of(int status, String code, List<FieldError> fieldErrors) {
		return new ApiError(Instant.now(), status, code, fieldErrors);
	}

	public record FieldError(String field, String code) {}
}
