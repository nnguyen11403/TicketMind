package com.ticketmind.backend.security.jwt;

import com.ticketmind.backend.user.UserRole;

import java.util.UUID;

public record JwtPrincipal(UUID id, String email, UserRole role) {
}
