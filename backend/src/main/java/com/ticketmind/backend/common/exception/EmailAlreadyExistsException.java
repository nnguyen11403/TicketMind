package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

public final class EmailAlreadyExistsException extends AuthException {

	public EmailAlreadyExistsException() {
		super("email already registered");
	}

	@Override
	public HttpStatus status() {
		return HttpStatus.CONFLICT;
	}

	@Override
	public String code() {
		return "email_already_registered";
	}
}
