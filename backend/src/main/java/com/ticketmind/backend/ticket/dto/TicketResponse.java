package com.ticketmind.backend.ticket.dto;

import com.ticketmind.backend.ticket.Ticket;
import com.ticketmind.backend.ticket.TicketPriority;
import com.ticketmind.backend.ticket.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
		UUID id,
		String title,
		String description,
		TicketStatus status,
		String category,
		TicketPriority priority,
		String suggestedResolution,
		UserSummary submitter,
		UserSummary assignee,
		Instant triagedAt,
		Instant resolvedAt,
		Instant createdAt,
		Instant updatedAt) {

	public static TicketResponse from(Ticket t) {
		return new TicketResponse(
				t.getId(),
				t.getTitle(),
				t.getDescription(),
				t.getStatus(),
				t.getCategory(),
				t.getPriority(),
				t.getSuggestedResolution(),
				UserSummary.from(t.getSubmitter()),
				UserSummary.from(t.getAssignee()),
				t.getTriagedAt(),
				t.getResolvedAt(),
				t.getCreatedAt(),
				t.getUpdatedAt());
	}
}
