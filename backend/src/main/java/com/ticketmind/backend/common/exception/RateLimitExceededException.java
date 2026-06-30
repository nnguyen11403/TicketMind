package com.ticketmind.backend.common.exception;

import org.springframework.http.HttpStatus;

public final class RateLimitExceededException extends AuthException {

	private final long retryAfterSeconds;

	public RateLimitExceededException(long retryAfterSeconds) {
		super("rate limit exceeded");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}

	@Override
	public HttpStatus status() {
		return HttpStatus.TOO_MANY_REQUESTS;
	}

	@Override
	public String code() {
		return "rate_limit_exceeded";
	}
}
