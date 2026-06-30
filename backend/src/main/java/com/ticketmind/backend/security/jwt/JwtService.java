package com.ticketmind.backend.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {

	private static final String CLAIM_ROLE = "role";
	private static final String CLAIM_EMAIL = "email";

	private final JwtProperties properties;
	private final SecretKey signingKey;
	private final Clock clock;

	public JwtService(JwtProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
		if (secret.length < 32) {
			throw new IllegalStateException(
					"app.jwt.secret must be at least 32 bytes (256 bits)");
		}
		this.signingKey = Keys.hmacShaKeyFor(secret);
	}

	public Issued issueAccessToken(UUID userId, String email, String role) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(properties.accessTokenTtl());
		String jti = UUID.randomUUID().toString();
		String token = Jwts.builder()
				.id(jti)
				.issuer(properties.issuer())
				.audience().add(properties.audience()).and()
				.subject(userId.toString())
				.claim(CLAIM_EMAIL, email)
				.claim(CLAIM_ROLE, role)
				.issuedAt(Date.from(now))
				.expiration(Date.from(expiresAt))
				.signWith(signingKey, Jwts.SIG.HS256)
				.compact();
		return new Issued(token, jti, expiresAt);
	}

	public Optional<Verified> verify(String token) {
		try {
			Jws<Claims> jws = Jwts.parser()
					.verifyWith(signingKey)
					.requireIssuer(properties.issuer())
					.requireAudience(properties.audience())
					.clock(() -> Date.from(clock.instant()))
					.build()
					.parseSignedClaims(token);
			Claims claims = jws.getPayload();
			UUID userId = UUID.fromString(claims.getSubject());
			String email = claims.get(CLAIM_EMAIL, String.class);
			String role = claims.get(CLAIM_ROLE, String.class);
			if (email == null || role == null) {
				return Optional.empty();
			}
			return Optional.of(new Verified(userId, email, role, claims.getId()));
		} catch (JwtException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	public record Issued(String token, String jti, Instant expiresAt) {}

	public record Verified(UUID userId, String email, String role, String jti) {}
}
