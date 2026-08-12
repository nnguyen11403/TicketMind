package com.ticketmind.backend.rag;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagPropertiesTest {

	private static RagProperties build(boolean enabled, String internalKey) {
		return new RagProperties(
				enabled, URI.create("http://rag.test"), internalKey,
				Duration.ofSeconds(2), Duration.ofSeconds(45));
	}

	@Test
	void enablingTheIntegrationWithoutAKeyFailsFast() {
		// Silently sending unauthenticated requests would 401 on every call and
		// look like "triage just doesn't work" — fail at startup instead.
		assertThatThrownBy(() -> build(true, "  "))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.rag.internal-key");
		assertThatThrownBy(() -> build(true, null))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aBlankKeyIsFineWhileTheIntegrationIsOff() {
		assertThatCode(() -> build(false, "")).doesNotThrowAnyException();
	}

	@Test
	void aConfiguredKeyIsAccepted() {
		assertThatCode(() -> build(true, "secret")).doesNotThrowAnyException();
	}
}
