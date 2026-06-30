package com.ticketmind.backend.auth;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
		@NotNull Bucket login,
		@NotNull Bucket register,
		@NotNull Bucket refresh) {

	public record Bucket(@Min(1) int capacity, @NotNull Duration refillPeriod) {}
}
