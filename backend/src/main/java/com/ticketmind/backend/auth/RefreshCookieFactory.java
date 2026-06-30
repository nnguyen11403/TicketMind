package com.ticketmind.backend.auth;

import com.ticketmind.backend.security.CookieProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class RefreshCookieFactory {

	public static final String COOKIE_NAME = "tm_refresh";
	private static final String COOKIE_PATH = "/auth";

	private final CookieProperties properties;

	public RefreshCookieFactory(CookieProperties properties) {
		this.properties = properties;
	}

	public ResponseCookie build(String value, Instant expiresAt) {
		Duration maxAge = Duration.between(Instant.now(), expiresAt);
		if (maxAge.isNegative()) {
			maxAge = Duration.ZERO;
		}
		return ResponseCookie.from(COOKIE_NAME, value)
				.httpOnly(true)
				.secure(properties.secure())
				.sameSite(properties.sameSite())
				.path(COOKIE_PATH)
				.maxAge(maxAge)
				.build();
	}

	public ResponseCookie clear() {
		return ResponseCookie.from(COOKIE_NAME, "")
				.httpOnly(true)
				.secure(properties.secure())
				.sameSite(properties.sameSite())
				.path(COOKIE_PATH)
				.maxAge(Duration.ZERO)
				.build();
	}
}
