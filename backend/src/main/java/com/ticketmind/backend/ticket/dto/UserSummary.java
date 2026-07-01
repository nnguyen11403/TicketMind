package com.ticketmind.backend.ticket.dto;

import com.ticketmind.backend.user.User;

import java.util.UUID;

public record UserSummary(UUID id, String displayName, String email) {

	public static UserSummary from(User user) {
		if (user == null) {
			return null;
		}
		return new UserSummary(user.getId(), user.getDisplayName(), user.getEmail());
	}
}
