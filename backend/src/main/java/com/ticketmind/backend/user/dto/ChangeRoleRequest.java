package com.ticketmind.backend.user.dto;

import com.ticketmind.backend.user.UserRole;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(@NotNull UserRole role) {
}
