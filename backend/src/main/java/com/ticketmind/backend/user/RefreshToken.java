package com.ticketmind.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

	@Id
	@UuidGenerator
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	@Column(name = "token_hash", nullable = false, unique = true, length = 255)
	private String tokenHash;

	@Column(name = "issued_at", nullable = false, updatable = false)
	private Instant issuedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "replaced_by_id")
	private UUID replacedById;

	@Column(name = "user_agent", length = 512)
	private String userAgent;

	@Column(name = "ip_address", length = 45)
	private String ipAddress;

	protected RefreshToken() {
		// JPA
	}

	public static RefreshToken issue(
			User user,
			String tokenHash,
			Instant issuedAt,
			Instant expiresAt,
			String userAgent,
			String ipAddress) {
		RefreshToken token = new RefreshToken();
		token.user = user;
		token.tokenHash = tokenHash;
		token.issuedAt = issuedAt;
		token.expiresAt = expiresAt;
		token.userAgent = userAgent;
		token.ipAddress = ipAddress;
		return token;
	}

	public boolean isActive(Instant now) {
		return revokedAt == null && expiresAt.isAfter(now);
	}

	public void revoke(Instant now) {
		if (revokedAt == null) {
			revokedAt = now;
		}
	}

	public void revoke(Instant now, UUID replacedBy) {
		revoke(now);
		this.replacedById = replacedBy;
	}

	public UUID getId() { return id; }
	public User getUser() { return user; }
	public String getTokenHash() { return tokenHash; }
	public Instant getIssuedAt() { return issuedAt; }
	public Instant getExpiresAt() { return expiresAt; }
	public Instant getRevokedAt() { return revokedAt; }
	public UUID getReplacedById() { return replacedById; }
}
