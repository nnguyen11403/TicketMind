package com.ticketmind.backend.ticket.dto;

import com.ticketmind.backend.ticket.Ticket;
import com.ticketmind.backend.ticket.TicketPriority;
import com.ticketmind.backend.ticket.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record TicketSummaryResponse(
		UUID id,
		String title,
		TicketStatus status,
		TicketPriority priority,
		String category,
		UserSummary submitter,
		UserSummary assignee,
		Instant createdAt,
		Instant updatedAt) {

	public static TicketSummaryResponse from(Ticket t) {
		return new TicketSummaryResponse(
				t.getId(),
				t.getTitle(),
				t.getStatus(),
				t.getPriority(),
				t.getCategory(),
				UserSummary.from(t.getSubmitter()),
				UserSummary.from(t.getAssignee()),
				t.getCreatedAt(),
				t.getUpdatedAt());
	}
}
