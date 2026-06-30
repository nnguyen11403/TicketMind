package com.ticketmind.backend.auth;

import com.ticketmind.backend.security.AuthProperties;
import com.ticketmind.backend.user.User;
import com.ticketmind.backend.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Login bookkeeping lives in its own bean so the failed-attempt counter
 * commits in its own transaction, even when the surrounding login flow
 * throws InvalidCredentialsException (which would otherwise roll back
 * the increment).
 */
@Component
public class LoginAttemptRecorder {

	private final UserRepository userRepository;
	private final AuthProperties authProperties;

	public LoginAttemptRecorder(UserRepository userRepository, AuthProperties authProperties) {
		this.userRepository = userRepository;
		this.authProperties = authProperties;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordFailedLogin(UUID userId, Instant now) {
		userRepository.findById(userId).ifPresent(u -> u.registerFailedLogin(
				authProperties.maxFailedAttempts(),
				authProperties.lockoutDuration(),
				now));
	}

	@Transactional
	public User markLoginSuccessful(UUID userId, Instant now) {
		User user = userRepository.findById(userId).orElseThrow();
		user.registerSuccessfulLogin(now);
		return user;
	}
}
