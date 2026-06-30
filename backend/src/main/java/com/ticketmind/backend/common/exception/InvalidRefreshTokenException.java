package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

public final class InvalidRefreshTokenException extends AuthException {

	public InvalidRefreshTokenException() {
		super("invalid refresh token");
	}

	@Override
	public HttpStatus status() {
		return HttpStatus.UNAUTHORIZED;
	}

	@Override
	public String code() {
		return "invalid_refresh_token";
	}
}
