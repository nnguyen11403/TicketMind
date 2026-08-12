package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

// The RAG service could not produce a usable triage — it was unreachable, hit
// a provider rate limit, or returned something unparseable. 502 rather than
// 500 because nothing on our side is broken and a retry may well succeed.
public final class TriageFailedException extends ApiException {

	public TriageFailedException() {
		super("the RAG service did not return a usable triage");
	}

	@Override
	public HttpStatus status() {
		return HttpStatus.BAD_GATEWAY;
	}

	@Override
	public String code() {
		return "triage_failed";
	}
}
