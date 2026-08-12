package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

// Demoting the only ADMIN would lock every staff capability out of the system
// with no way back except editing the database — the exact hole the bootstrap
// exists to close. Refuse rather than allow a self-inflicted lockout.
public final class LastAdminException extends ApiException {

	public LastAdminException() {
		super("cannot remove the last remaining ADMIN");
	}

	@Override
	public HttpStatus status() {
		return HttpStatus.CONFLICT;
	}

	@Override
	public String code() {
		return "last_admin";
	}
}
