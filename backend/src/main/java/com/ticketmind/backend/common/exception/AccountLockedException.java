package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

public final class AccountLockedException extends ApiException {

	public AccountLockedException() {
		super("account temporarily locked");
	}

	@Override
	public HttpStatus status() {
		return HttpStatus.LOCKED;
	}

	@Override
	public String code() {
		return "account_locked";
	}
}
