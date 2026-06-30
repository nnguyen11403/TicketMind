package com.ticketmind.backend.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
		// 4 is BCrypt's floor — tests dial down to keep CPU cost negligible;
		// production must stay >= 12 (enforced via application.yml default).
		@Min(4) int bcryptStrength,
		@Min(12) int passwordMinLength,
		@Min(3) int maxFailedAttempts,
		@NotNull Duration lockoutDuration) {
}
