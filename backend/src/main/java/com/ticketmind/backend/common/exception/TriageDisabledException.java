package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

// app.rag.enabled is false, so there is nothing to call. Distinct from
// triage_failed on purpose: one is a deployment that never wired up the RAG
// service, the other is a call that was made and did not work. Telling them
// apart is the difference between "check your config" and "try again".
public final class TriageDisabledException extends ApiException {

	public TriageDisabledException() {
		super("the RAG integration is disabled");
	}

	@Override
	public HttpStatus status() {
		return HttpStatus.SERVICE_UNAVAILABLE;
	}

	@Override
	public String code() {
		return "triage_disabled";
	}
}
