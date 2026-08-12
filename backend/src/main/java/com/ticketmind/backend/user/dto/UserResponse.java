package com.ticketmind.backend.user.dto;

import com.ticketmind.backend.user.User;
import com.ticketmind.backend.user.UserRole;

import java.util.UUID;

// Deliberately excludes the password hash, lockout counters, and login
// timestamps: this is the staff-facing view, not an admin dump.
public record UserResponse(UUID id, String email, String displayName, UserRole role, boolean active) {

	public static UserResponse from(User user) {
		return new UserResponse(
				user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(), user.isActive());
	}
}
