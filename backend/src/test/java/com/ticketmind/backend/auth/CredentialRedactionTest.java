package com.ticketmind.backend.auth;

import com.ticketmind.backend.auth.dto.LoginRequest;
import com.ticketmind.backend.auth.dto.RegisterRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Records generate a toString() that prints every component, and Spring logs
 * the deserialised request body at DEBUG, which the dev profile enables, and
 * which docker-compose runs by default. Without an override that combination
 * writes plaintext passwords into the application log on every login.
 */
class CredentialRedactionTest {

	private static final String SECRET = "a-good-long-password-1";

	@Test
	void loginRequestNeverPrintsThePassword() {
		String rendered = new LoginRequest("alice@example.com", SECRET).toString();
		assertThat(rendered).doesNotContain(SECRET);
		// The non-secret fields stay readable, a redacted log is still a log.
		assertThat(rendered).contains("alice@example.com");
	}

	@Test
	void registerRequestNeverPrintsThePassword() {
		String rendered = new RegisterRequest("alice@example.com", SECRET, "Alice").toString();
		assertThat(rendered).doesNotContain(SECRET);
		assertThat(rendered).contains("alice@example.com").contains("Alice");
	}

	@Test
	void stringInterpolationOfTheWholeRecordIsAlsoSafe() {
		// This is the shape the framework actually uses: the record is
		// concatenated into a log message rather than having getters called.
		assertThat("body=" + new RegisterRequest("bob@example.com", SECRET, "Bob"))
				.doesNotContain(SECRET);
	}
}
