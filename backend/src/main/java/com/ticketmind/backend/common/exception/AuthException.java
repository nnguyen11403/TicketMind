package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

public abstract class AuthException extends RuntimeException {

	protected AuthException(String message) {
		super(message);
	}

	public abstract HttpStatus status();

	public abstract String code();
}
