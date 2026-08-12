package com.ticketmind.backend.rag;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
		boolean enabled,
		@NotNull URI baseUrl,
		String internalKey,
		@NotNull Duration connectTimeout,
		@NotNull Duration readTimeout) {

	public RagProperties {
		// Validated here rather than with @NotBlank because the key is only
		// required when the integration is switched on — the test profile and
		// any deployment running without triage leaves it empty on purpose.
		if (enabled && (internalKey == null || internalKey.isBlank())) {
			throw new IllegalStateException(
					"app.rag.internal-key must be set when app.rag.enabled is true");
		}
	}
}
