package com.ticketmind.backend.auth;

import com.ticketmind.backend.auth.dto.LoginRequest;
import com.ticketmind.backend.auth.dto.RegisterRequest;
import com.ticketmind.backend.auth.dto.TokenResponse;
import com.ticketmind.backend.auth.dto.UserResponse;
import com.ticketmind.backend.common.exception.AccountLockedException;
import com.ticketmind.backend.common.exception.EmailAlreadyExistsException;
import com.ticketmind.backend.common.exception.InvalidCredentialsException;
import com.ticketmind.backend.common.exception.InvalidRefreshTokenException;
import com.ticketmind.backend.security.jwt.JwtService;
import com.ticketmind.backend.user.User;
import com.ticketmind.backend.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicy passwordPolicy;
	private final JwtService jwtService;
	private final RefreshTokenService refreshTokenService;
	private final LoginAttemptRecorder loginAttemptRecorder;
	private final Clock clock;

	public AuthService(
			UserRepository userRepository,
			PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy,
			JwtService jwtService,
			RefreshTokenService refreshTokenService,
			LoginAttemptRecorder loginAttemptRecorder,
			Clock clock) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.jwtService = jwtService;
		this.refreshTokenService = refreshTokenService;
		this.loginAttemptRecorder = loginAttemptRecorder;
		this.clock = clock;
	}

	@Transactional
	public AuthResult register(RegisterRequest request, String userAgent, String ipAddress) {
		String email = request.email().toLowerCase().trim();
		passwordPolicy.verify(request.password(), email);
		if (userRepository.existsByEmailIgnoreCase(email)) {
			throw new EmailAlreadyExistsException();
		}
		String hash = passwordEncoder.encode(request.password());
		User user = User.create(email, hash, request.displayName().trim());
		userRepository.save(user);
		return issueSession(user, userAgent, ipAddress);
	}

	public AuthResult login(LoginRequest request, String userAgent, String ipAddress) {
		Instant now = clock.instant();
		User user = userRepository.findByEmailIgnoreCase(request.email().toLowerCase().trim())
				.orElse(null);
		if (user == null || !user.isActive()) {
			// Run the password hash to keep timing similar to the real path
			// (mitigates user-enumeration by response-time side channel).
			passwordEncoder.matches(request.password(),
					"{bcrypt}$2a$12$invalidplaceholderhashvalueforuse..........................");
			throw new InvalidCredentialsException();
		}
		if (user.isLocked(now)) {
			throw new AccountLockedException();
		}
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			loginAttemptRecorder.recordFailedLogin(user.getId(), now);
			throw new InvalidCredentialsException();
		}
		User refreshed = loginAttemptRecorder.markLoginSuccessful(user.getId(), now);
		return issueSession(refreshed, userAgent, ipAddress);
	}

	// Intentionally NOT @Transactional: refreshTokenService.rotate manages its
	// own transaction with noRollbackFor=InvalidRefreshTokenException so that
	// the family revocation on detected reuse commits even when we then throw.
	public AuthResult refresh(String refreshToken, String userAgent, String ipAddress) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new InvalidRefreshTokenException();
		}
		RefreshTokenService.Rotated rotated = refreshTokenService.rotate(refreshToken, userAgent, ipAddress);
		User user = rotated.user();
		JwtService.Issued access = jwtService.issueAccessToken(user.getId(), user.getEmail(),
				user.getRole().name());
		return new AuthResult(
				TokenResponse.of(access.token(), access.expiresAt(), UserResponse.from(user)),
				rotated.rawToken(),
				rotated.expiresAt());
	}

	@Transactional
	public void logout(String refreshToken) {
		if (refreshToken != null && !refreshToken.isBlank()) {
			refreshTokenService.revoke(refreshToken);
		}
	}

	private AuthResult issueSession(User user, String userAgent, String ipAddress) {
		JwtService.Issued access = jwtService.issueAccessToken(user.getId(), user.getEmail(),
				user.getRole().name());
		RefreshTokenService.Issued refresh = refreshTokenService.issue(user, userAgent, ipAddress);
		return new AuthResult(
				TokenResponse.of(access.token(), access.expiresAt(), UserResponse.from(user)),
				refresh.rawToken(),
				refresh.expiresAt());
	}

	public record AuthResult(TokenResponse body, String refreshToken, Instant refreshExpiresAt) {}
}
