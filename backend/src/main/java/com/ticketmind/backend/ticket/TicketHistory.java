package com.ticketmind.backend.ticket;

import com.ticketmind.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket_history")
public class TicketHistory {

	@Id
	@UuidGenerator
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "ticket_id", nullable = false, updatable = false)
	private Ticket ticket;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "actor_id", updatable = false)
	private User actor;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 48, updatable = false)
	private TicketHistoryEventType eventType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String payload = "{}";

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected TicketHistory() {
		// JPA
	}

	private TicketHistory(Ticket ticket, User actor, TicketHistoryEventType eventType, String payload, Instant createdAt) {
		this.ticket = ticket;
		this.actor = actor;
		this.eventType = eventType;
		this.payload = payload == null ? "{}" : payload;
		this.createdAt = createdAt;
	}

	public static TicketHistory record(Ticket ticket, User actor, TicketHistoryEventType eventType, String payloadJson, Instant now) {
		return new TicketHistory(ticket, actor, eventType, payloadJson, now);
	}

	public UUID getId() { return id; }
	public Ticket getTicket() { return ticket; }
	public User getActor() { return actor; }
	public TicketHistoryEventType getEventType() { return eventType; }
	public String getPayload() { return payload; }
	public Instant getCreatedAt() { return createdAt; }
}
