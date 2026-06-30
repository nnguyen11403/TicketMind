package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

public final class PasswordPolicyException extends AuthException {

	public PasswordPolicyException(String reason) {
		super(reason);
	}

	@Override
	public HttpStatus status() {
		return HttpStatus.BAD_REQUEST;
	}

	@Override
	public String code() {
		return "password_policy";
	}
}
