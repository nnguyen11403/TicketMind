package com.ticketmind.backend.auth;

import com.ticketmind.backend.common.exception.InvalidRefreshTokenException;
import com.ticketmind.backend.security.jwt.JwtProperties;
import com.ticketmind.backend.user.RefreshToken;
import com.ticketmind.backend.user.RefreshTokenRepository;
import com.ticketmind.backend.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

@Service
public class RefreshTokenService {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
	private static final int TOKEN_BYTES = 32; // 256 bits of entropy

	private final RefreshTokenRepository repository;
	private final JwtProperties jwtProperties;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	public RefreshTokenService(RefreshTokenRepository repository, JwtProperties jwtProperties, Clock clock) {
		this.repository = repository;
		this.jwtProperties = jwtProperties;
		this.clock = clock;
	}

	@Transactional
	public Issued issue(User user, String userAgent, String ipAddress) {
		String raw = generateRawToken();
		String hash = sha256(raw);
		Instant now = clock.instant();
		Instant expiresAt = now.plus(jwtProperties.refreshTokenTtl());
		RefreshToken token = RefreshToken.issue(user, hash, now, expiresAt, userAgent, ipAddress);
		repository.save(token);
		return new Issued(raw, expiresAt);
	}

	// noRollbackFor: when we detect a reused token we revoke the entire token
	// family and then throw to fail the caller. Without this, the throw rolls
	// back the revocation and the attacker's session stays alive.
	@Transactional(noRollbackFor = InvalidRefreshTokenException.class)
	public Rotated rotate(String presentedRawToken, String userAgent, String ipAddress) {
		String hash = sha256(presentedRawToken);
		RefreshToken stored = repository.findByTokenHash(hash)
				.orElseThrow(InvalidRefreshTokenException::new);
		Instant now = clock.instant();
		if (stored.getRevokedAt() != null) {
			log.warn("Refresh token reuse detected for user {}, revoking all sessions",
					stored.getUser().getId());
			repository.revokeAllForUser(stored.getUser().getId(), now);
			throw new InvalidRefreshTokenException();
		}
		if (!stored.isActive(now)) {
			throw new InvalidRefreshTokenException();
		}
		User user = stored.getUser();
		// Force lazy User to initialize inside this transaction; caller runs
		// outside any transaction so the session is gone by the time it
		// reads email/role.
		user.getEmail();
		String newRaw = generateRawToken();
		String newHash = sha256(newRaw);
		Instant expiresAt = now.plus(jwtProperties.refreshTokenTtl());
		RefreshToken replacement = RefreshToken.issue(user, newHash, now, expiresAt, userAgent, ipAddress);
		repository.save(replacement);
		stored.revoke(now, replacement.getId());
		return new Rotated(user, newRaw, expiresAt);
	}

	@Transactional
	public void revoke(String presentedRawToken) {
		String hash = sha256(presentedRawToken);
		repository.findByTokenHash(hash).ifPresent(t -> t.revoke(clock.instant()));
	}

	@Transactional
	public void revokeAllForUser(User user) {
		repository.revokeAllForUser(user.getId(), clock.instant());
	}

	private String generateRawToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String sha256(String raw) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	public record Issued(String rawToken, Instant expiresAt) {}

	public record Rotated(User user, String rawToken, Instant expiresAt) {}
}
