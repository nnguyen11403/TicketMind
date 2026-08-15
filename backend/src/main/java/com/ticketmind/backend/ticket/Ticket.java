package com.ticketmind.backend.ticket;

import com.ticketmind.backend.common.crypto.EncryptedStringConverter;
import com.ticketmind.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tickets")
@EntityListeners(AuditingEntityListener.class)
public class Ticket {

	@Id
	@UuidGenerator
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "submitter_id", nullable = false, updatable = false)
	private User submitter;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "assignee_id")
	private User assignee;

	// Encrypted at rest, so the column is TEXT rather than VARCHAR(200): the
	// ciphertext envelope is longer than the plaintext it wraps. The 200-char
	// limit users actually see is enforced by CreateTicketRequest.
	@Convert(converter = EncryptedStringConverter.class)
	@Column(nullable = false, columnDefinition = "text")
	private String title;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(nullable = false, columnDefinition = "text")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private TicketStatus status = TicketStatus.OPEN;

	@Column(length = 64)
	private String category;

	@Enumerated(EnumType.STRING)
	@Column(length = 16)
	private TicketPriority priority;

	// Claude's suggested fix quotes the ticket back at itself and cites past
	// resolutions, so it carries the same content sensitivity as the body.
	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "suggested_resolution", columnDefinition = "text")
	private String suggestedResolution;

	@Column(name = "triaged_at")
	private Instant triagedAt;

	// Read-only ordering key. The priority column stores enum names, so sorting
	// on it directly is alphabetical (CRITICAL, HIGH, LOW, MEDIUM) rather than
	// by urgency. A derived column keeps this out of the schema and out of the
	// write path; untriaged tickets rank 0.
	@Formula("CASE priority WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 "
			+ "WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0 END")
	private int priorityRank;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Ticket() {
		// JPA
	}

	private Ticket(User submitter, String title, String description) {
		this.submitter = submitter;
		this.title = title;
		this.description = description;
	}

	public static Ticket open(User submitter, String title, String description) {
		return new Ticket(submitter, title, description);
	}

	public void updateContent(String title, String description) {
		this.title = title;
		this.description = description;
	}

	public void assignTo(User assignee) {
		this.assignee = assignee;
	}

	public void unassign() {
		this.assignee = null;
	}

	public void changeStatus(TicketStatus next, Instant now) {
		if (!this.status.canTransitionTo(next)) {
			throw new IllegalStateException("invalid transition: " + this.status + " -> " + next);
		}
		this.status = next;
		if (next == TicketStatus.RESOLVED) {
			this.resolvedAt = now;
		} else if (this.resolvedAt != null && !next.isTerminal()) {
			// Reopened after resolution, wipe resolution timestamp so it
			// reflects only the most recent resolution event.
			this.resolvedAt = null;
		}
	}

	public void applyTriage(String category, TicketPriority priority, String suggestedResolution, Instant now) {
		this.category = category;
		this.priority = priority;
		this.suggestedResolution = suggestedResolution;
		this.triagedAt = now;
	}

	public UUID getId() { return id; }
	public User getSubmitter() { return submitter; }
	public User getAssignee() { return assignee; }
	public String getTitle() { return title; }
	public String getDescription() { return description; }
	public TicketStatus getStatus() { return status; }
	public String getCategory() { return category; }
	public TicketPriority getPriority() { return priority; }
	public String getSuggestedResolution() { return suggestedResolution; }
	public Instant getTriagedAt() { return triagedAt; }
	public int getPriorityRank() { return priorityRank; }
	public Instant getResolvedAt() { return resolvedAt; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
