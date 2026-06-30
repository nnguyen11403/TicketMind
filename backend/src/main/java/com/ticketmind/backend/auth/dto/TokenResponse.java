package com.ticketmind.backend.auth.dto;

import java.time.Instant;

public record TokenResponse(
		String accessToken,
		String tokenType,
		Instant expiresAt,
		UserResponse user) {

	public static TokenResponse of(String accessToken, Instant expiresAt, UserResponse user) {
		return new TokenResponse(accessToken, "Bearer", expiresAt, user);
	}
}
