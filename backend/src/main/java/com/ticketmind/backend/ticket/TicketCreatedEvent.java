package com.ticketmind.backend.ticket;

import java.util.UUID;

// Carries only the id: listeners run after commit, on another thread, so a
// detached entity would be a lazy-loading trap.
public record TicketCreatedEvent(UUID ticketId) {
}
