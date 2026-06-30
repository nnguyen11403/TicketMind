package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Surface a single generic error for "no such user" and "wrong password"
 * so attackers can't enumerate registered emails.
 */
public final class InvalidCredentialsException extends AuthException {

	public InvalidCredentialsException() {
		super("invalid credentials");
	}

	@Override
	public HttpStatus status() {
		return HttpStatus.UNAUTHORIZED;
	}

	@Override
	public String code() {
		return "invalid_credentials";
	}
}
