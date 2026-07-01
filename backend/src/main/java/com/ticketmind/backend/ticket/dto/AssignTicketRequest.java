package com.ticketmind.backend.ticket.dto;

import java.util.UUID;

// assigneeId == null is interpreted as "unassign".
public record AssignTicketRequest(UUID assigneeId) {
}
