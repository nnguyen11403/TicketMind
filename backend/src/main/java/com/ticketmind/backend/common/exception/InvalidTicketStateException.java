package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

public final class InvalidTicketStateException extends ApiException {

	public InvalidTicketStateException(String message) {
		super(message);
	}

	@Override
	public HttpStatus status() {
		return HttpStatus.CONFLICT;
	}

	@Override
	public String code() {
		return "invalid_ticket_state";
	}
}
