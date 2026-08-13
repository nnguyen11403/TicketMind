package com.ticketmind.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
public class User {

	@Id
	@UuidGenerator
	private UUID id;

	@Column(nullable = false, unique = true, columnDefinition = "citext")
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private String email;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@Column(name = "display_name", nullable = false, length = 120)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private UserRole role = UserRole.USER;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	@Column(name = "failed_login_attempts", nullable = false)
	private int failedLoginAttempts = 0;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected User() {
		// JPA
	}

	private User(String email, String passwordHash, String displayName, UserRole role) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.displayName = displayName;
		this.role = role;
	}

	public static User create(String email, String passwordHash, String displayName) {
		return new User(email.toLowerCase(), passwordHash, displayName, UserRole.USER);
	}

	@PrePersist
	@PreUpdate
	private void normalize() {
		if (email != null) {
			email = email.toLowerCase();
		}
	}

	public boolean isLocked(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public void registerFailedLogin(int maxAttempts, java.time.Duration lockoutDuration, Instant now) {
		this.failedLoginAttempts += 1;
		if (this.failedLoginAttempts >= maxAttempts) {
			this.lockedUntil = now.plus(lockoutDuration);
		}
	}

	public void registerSuccessfulLogin(Instant now) {
		this.failedLoginAttempts = 0;
		this.lockedUntil = null;
		this.lastLoginAt = now;
	}

	/**
	 * Change this user's role. Named rather than a bare setter so every role
	 * change is greppable, this is the only path to staff privileges.
	 */
	public void changeRole(UserRole next) {
		this.role = next;
	}

	public UUID getId() { return id; }
	public String getEmail() { return email; }
	public String getPasswordHash() { return passwordHash; }
	public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
	public String getDisplayName() { return displayName; }
	public UserRole getRole() { return role; }
	public boolean isActive() { return active; }
	public int getFailedLoginAttempts() { return failedLoginAttempts; }
	public Instant getLockedUntil() { return lockedUntil; }
	public Instant getLastLoginAt() { return lastLoginAt; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
