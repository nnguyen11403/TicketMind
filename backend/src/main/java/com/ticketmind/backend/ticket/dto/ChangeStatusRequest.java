package com.ticketmind.backend.ticket.dto;

import com.ticketmind.backend.ticket.TicketStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeStatusRequest(@NotNull TicketStatus status) {
}
