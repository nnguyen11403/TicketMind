package com.ticketmind.backend.ticket.dto;

import com.ticketmind.backend.ticket.TicketHistory;
import com.ticketmind.backend.ticket.TicketHistoryEventType;

import java.time.Instant;
import java.util.UUID;

public record TicketHistoryResponse(
		UUID id,
		TicketHistoryEventType eventType,
		UserSummary actor,
		String payload,
		Instant createdAt) {

	public static TicketHistoryResponse from(TicketHistory h) {
		return new TicketHistoryResponse(
				h.getId(),
				h.getEventType(),
				UserSummary.from(h.getActor()),
				h.getPayload(),
				h.getCreatedAt());
	}
}
