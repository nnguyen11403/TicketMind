package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

// Also thrown when a caller is not authorised to see a ticket — surfacing
// the same 404 prevents enumerating ticket IDs.
public final class TicketNotFoundException extends ApiException {

	public TicketNotFoundException() {
		super("ticket not found");
	}

	@Override
	public HttpStatus status() {
		return HttpStatus.NOT_FOUND;
	}

	@Override
	public String code() {
		return "ticket_not_found";
	}
}
