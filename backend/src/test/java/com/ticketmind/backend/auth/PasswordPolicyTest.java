package com.ticketmind.backend.auth;

import com.ticketmind.backend.common.exception.PasswordPolicyException;
import com.ticketmind.backend.security.AuthProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

	private final AuthProperties properties = new AuthProperties(12, 12, 5, Duration.ofMinutes(15));
	private final PasswordPolicy policy = new PasswordPolicy(properties);

	@Test
	void acceptsPasswordAtOrAboveMinLength() {
		assertThatCode(() -> policy.verify("correct horse battery staple", "u@example.com"))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsTooShortPassword() {
		assertThatThrownBy(() -> policy.verify("short1!", "u@example.com"))
				.isInstanceOf(PasswordPolicyException.class)
				.hasMessage("too_short");
	}

	@Test
	void rejectsExcessivelyLongPassword() {
		String huge = "a".repeat(300);
		assertThatThrownBy(() -> policy.verify(huge, "u@example.com"))
				.isInstanceOf(PasswordPolicyException.class)
				.hasMessage("too_long");
	}

	@Test
	void rejectsPasswordEqualToEmail() {
		assertThatThrownBy(() -> policy.verify("alice@example.com", "ALICE@example.com"))
				.isInstanceOf(PasswordPolicyException.class)
				.hasMessage("contains_email");
	}

	@Test
	void rejectsNullPassword() {
		assertThatThrownBy(() -> policy.verify(null, "u@example.com"))
				.isInstanceOf(PasswordPolicyException.class)
				.hasMessage("missing");
	}
}
