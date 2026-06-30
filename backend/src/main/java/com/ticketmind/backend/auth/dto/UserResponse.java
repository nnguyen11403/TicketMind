package com.ticketmind.backend.auth.dto;

import com.ticketmind.backend.user.User;
import com.ticketmind.backend.user.UserRole;

import java.util.UUID;

public record UserResponse(UUID id, String email, String displayName, UserRole role) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
	}
}
