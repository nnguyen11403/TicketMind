package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

public final class AgentRequiredException extends ApiException {

	public AgentRequiredException() {
		super("assignee must be an AGENT or ADMIN");
	}

	@Override
	public HttpStatus status() {
		return HttpStatus.BAD_REQUEST;
	}

	@Override
	public String code() {
		return "agent_required";
	}
}
