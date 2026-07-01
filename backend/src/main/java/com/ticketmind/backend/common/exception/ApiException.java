package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

// Base class for any exception that the global handler is allowed to
// translate into an API error envelope. Subclasses pick the HTTP status
// and the stable machine-readable code; the exception message itself is
// never serialised to the client.
public abstract class ApiException extends RuntimeException {

	protected ApiException(String message) {
		super(message);
	}

	public abstract HttpStatus status();

	public abstract String code();
}
