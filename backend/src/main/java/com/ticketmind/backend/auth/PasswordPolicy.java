package com.ticketmind.backend.auth;

import com.ticketmind.backend.common.exception.PasswordPolicyException;
import com.ticketmind.backend.security.AuthProperties;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {

	private static final int MAX_LENGTH = 256;

	private final AuthProperties properties;

	public PasswordPolicy(AuthProperties properties) {
		this.properties = properties;
	}

	public void verify(String rawPassword, String email) {
		if (rawPassword == null) {
			throw new PasswordPolicyException("missing");
		}
		int len = rawPassword.length();
		if (len < properties.passwordMinLength()) {
			throw new PasswordPolicyException("too_short");
		}
		if (len > MAX_LENGTH) {
			throw new PasswordPolicyException("too_long");
		}
		if (email != null && rawPassword.equalsIgnoreCase(email)) {
			throw new PasswordPolicyException("contains_email");
		}
	}
}
