package com.ticketmind.backend.ticket;

import java.util.UUID;

public record TicketResolvedEvent(UUID ticketId) {
}
