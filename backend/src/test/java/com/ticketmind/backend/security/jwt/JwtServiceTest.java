package com.ticketmind.backend.security.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

	private static final String SECRET = "test-secret-test-secret-test-secret-test-secret-test-secret-test";
	private static final Instant FIXED_NOW = Instant.parse("2026-01-15T10:00:00Z");

	private final JwtProperties properties = new JwtProperties(
			SECRET, "ticketmind", "ticketmind-app",
			Duration.ofMinutes(15), Duration.ofDays(14));
	private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
	private final JwtService jwtService = new JwtService(properties, clock);

	@Test
	void issuedTokenVerifiesAndRoundTrips() {
		UUID userId = UUID.randomUUID();
		JwtService.Issued issued = jwtService.issueAccessToken(userId, "alice@example.com", "USER");

		Optional<JwtService.Verified> verified = jwtService.verify(issued.token());

		assertThat(verified).isPresent();
		assertThat(verified.get().userId()).isEqualTo(userId);
		assertThat(verified.get().email()).isEqualTo("alice@example.com");
		assertThat(verified.get().role()).isEqualTo("USER");
		assertThat(verified.get().jti()).isEqualTo(issued.jti());
		assertThat(issued.expiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(15)));
	}

	@Test
	void shortSecretIsRejectedAtConstruction() {
		JwtProperties weak = new JwtProperties(
				"too-short", "ticketmind", "ticketmind-app",
				Duration.ofMinutes(15), Duration.ofDays(14));
		assertThatThrownBy(() -> new JwtService(weak, clock))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("at least 32 bytes");
	}

	@Test
	void tamperedTokenIsRejected() {
		JwtService.Issued issued = jwtService.issueAccessToken(UUID.randomUUID(), "u@example.com", "USER");
		String tampered = issued.token().substring(0, issued.token().length() - 2) + "xx";
		assertThat(jwtService.verify(tampered)).isEmpty();
	}

	@Test
	void expiredTokenIsRejected() {
		JwtService.Issued issued = jwtService.issueAccessToken(UUID.randomUUID(), "u@example.com", "USER");
		Clock future = Clock.fixed(FIXED_NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC);
		JwtService later = new JwtService(properties, future);
		assertThat(later.verify(issued.token())).isEmpty();
	}

	@Test
	void tokenSignedWithDifferentSecretIsRejected() {
		SecretKey otherKey = Keys.hmacShaKeyFor(
				"other-secret-other-secret-other-secret-other-secret-other-secret".getBytes(StandardCharsets.UTF_8));
		String foreign = Jwts.builder()
				.subject(UUID.randomUUID().toString())
				.issuer("ticketmind")
				.audience().add("ticketmind-app").and()
				.claim("email", "x@example.com")
				.claim("role", "USER")
				.expiration(Date.from(FIXED_NOW.plus(Duration.ofMinutes(15))))
				.signWith(otherKey, Jwts.SIG.HS256)
				.compact();
		assertThat(jwtService.verify(foreign)).isEmpty();
	}

	@Test
	void tokenWithWrongIssuerIsRejected() {
		SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
		String wrongIssuer = Jwts.builder()
				.subject(UUID.randomUUID().toString())
				.issuer("attacker")
				.audience().add("ticketmind-app").and()
				.claim("email", "x@example.com")
				.claim("role", "USER")
				.expiration(Date.from(FIXED_NOW.plus(Duration.ofMinutes(15))))
				.signWith(key, Jwts.SIG.HS256)
				.compact();
		assertThat(jwtService.verify(wrongIssuer)).isEmpty();
	}

	@Test
	void tokenWithWrongAudienceIsRejected() {
		SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
		String wrongAudience = Jwts.builder()
				.subject(UUID.randomUUID().toString())
				.issuer("ticketmind")
				.audience().add("other-app").and()
				.claim("email", "x@example.com")
				.claim("role", "USER")
				.expiration(Date.from(FIXED_NOW.plus(Duration.ofMinutes(15))))
				.signWith(key, Jwts.SIG.HS256)
				.compact();
		assertThat(jwtService.verify(wrongAudience)).isEmpty();
	}

	@Test
	void garbageTokenIsRejected() {
		assertThat(jwtService.verify("not-a-jwt")).isEmpty();
		assertThat(jwtService.verify("")).isEmpty();
	}
}
