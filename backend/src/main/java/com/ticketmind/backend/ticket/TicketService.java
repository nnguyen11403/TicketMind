package com.ticketmind.backend.ticket;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.ticketmind.backend.common.exception.AgentRequiredException;
import com.ticketmind.backend.common.exception.InvalidTicketStateException;
import com.ticketmind.backend.common.exception.TicketNotFoundException;
import com.ticketmind.backend.security.jwt.JwtPrincipal;
import com.ticketmind.backend.ticket.dto.AddCommentRequest;
import com.ticketmind.backend.ticket.dto.AssignTicketRequest;
import com.ticketmind.backend.ticket.dto.ChangeStatusRequest;
import com.ticketmind.backend.ticket.dto.CreateTicketRequest;
import com.ticketmind.backend.ticket.dto.UpdateTicketRequest;
import com.ticketmind.backend.user.User;
import com.ticketmind.backend.user.UserRepository;
import com.ticketmind.backend.user.UserRole;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class TicketService {

	private static final int MAX_PAGE_SIZE = 100;

	private final TicketRepository ticketRepository;
	private final TicketHistoryRepository historyRepository;
	private final UserRepository userRepository;
	private final ObjectMapper objectMapper;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	public TicketService(
			TicketRepository ticketRepository,
			TicketHistoryRepository historyRepository,
			UserRepository userRepository,
			ObjectMapper objectMapper,
			ApplicationEventPublisher events,
			Clock clock) {
		this.ticketRepository = ticketRepository;
		this.historyRepository = historyRepository;
		this.userRepository = userRepository;
		this.objectMapper = objectMapper;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public Ticket create(JwtPrincipal actor, CreateTicketRequest request) {
		User submitter = userRepository.getReferenceById(actor.id());
		Ticket ticket = Ticket.open(submitter, request.title().trim(), request.description().trim());
		ticketRepository.save(ticket);
		recordHistory(ticket, submitter, TicketHistoryEventType.CREATED, Map.of(
				"title", ticket.getTitle()));
		// Delivered after commit, on the RAG executor — see RagTicketListener.
		// The 201 response therefore carries a null category/priority and the
		// client sees them appear on a later fetch.
		events.publishEvent(new TicketCreatedEvent(ticket.getId()));
		// Re-fetch with eager submitter so the caller can serialise without
		// hitting a closed session on the lazy proxy.
		return ticketRepository.findWithUsersById(ticket.getId()).orElseThrow();
	}

	@Transactional(readOnly = true)
	public Ticket get(JwtPrincipal actor, UUID ticketId) {
		Ticket ticket = ticketRepository.findWithUsersById(ticketId)
				.orElseThrow(TicketNotFoundException::new);
		assertCanRead(actor, ticket);
		return ticket;
	}

	@Transactional(readOnly = true)
	public Page<Ticket> list(JwtPrincipal actor, TicketStatus status, int page, int size) {
		int safePage = Math.max(0, page);
		int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
		PageRequest pageRequest = PageRequest.of(safePage, safeSize,
				Sort.by(Sort.Direction.DESC, "createdAt"));
		// USER role only ever sees its own tickets — pin the submitter
		// filter regardless of what the caller asked for.
		UUID submitterFilter = isStaff(actor) ? null : actor.id();
		return ticketRepository.search(status, submitterFilter, pageRequest);
	}

	@Transactional
	public Ticket update(JwtPrincipal actor, UUID ticketId, UpdateTicketRequest request) {
		Ticket ticket = ticketRepository.findWithUsersById(ticketId)
				.orElseThrow(TicketNotFoundException::new);
		assertCanEditContent(actor, ticket);
		ticket.updateContent(request.title().trim(), request.description().trim());
		User actorUser = userRepository.getReferenceById(actor.id());
		recordHistory(ticket, actorUser, TicketHistoryEventType.UPDATED, Map.of(
				"title", ticket.getTitle()));
		return ticket;
	}

	@Transactional
	public Ticket changeStatus(JwtPrincipal actor, UUID ticketId, ChangeStatusRequest request) {
		Ticket ticket = ticketRepository.findWithUsersById(ticketId)
				.orElseThrow(TicketNotFoundException::new);
		assertCanChangeStatus(actor, ticket, request.status());
		TicketStatus previous = ticket.getStatus();
		try {
			ticket.changeStatus(request.status(), clock.instant());
		} catch (IllegalStateException ex) {
			throw new InvalidTicketStateException(ex.getMessage());
		}
		User actorUser = userRepository.getReferenceById(actor.id());
		TicketHistoryEventType eventType = switch (request.status()) {
			case RESOLVED -> TicketHistoryEventType.RESOLVED;
			case OPEN, IN_PROGRESS -> previous == TicketStatus.RESOLVED
					? TicketHistoryEventType.REOPENED
					: TicketHistoryEventType.STATUS_CHANGED;
			default -> TicketHistoryEventType.STATUS_CHANGED;
		};
		recordHistory(ticket, actorUser, eventType, Map.of(
				"from", previous.name(),
				"to", request.status().name()));
		if (request.status() == TicketStatus.RESOLVED) {
			// Feed the resolved ticket back into the RAG knowledge base so it
			// becomes retrievable context for future triage.
			events.publishEvent(new TicketResolvedEvent(ticket.getId()));
		}
		return ticket;
	}

	@Transactional
	public Ticket assign(JwtPrincipal actor, UUID ticketId, AssignTicketRequest request) {
		if (!isStaff(actor)) {
			throw new TicketNotFoundException();
		}
		Ticket ticket = ticketRepository.findWithUsersById(ticketId)
				.orElseThrow(TicketNotFoundException::new);
		User assignee = null;
		if (request.assigneeId() != null) {
			assignee = userRepository.findById(request.assigneeId())
					.orElseThrow(AgentRequiredException::new);
			if (assignee.getRole() == UserRole.USER || !assignee.isActive()) {
				throw new AgentRequiredException();
			}
			ticket.assignTo(assignee);
		} else {
			ticket.unassign();
		}
		User actorUser = userRepository.getReferenceById(actor.id());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("assigneeId", assignee == null ? null : assignee.getId().toString());
		recordHistory(ticket, actorUser, TicketHistoryEventType.ASSIGNED, payload);
		return ticket;
	}

	@Transactional
	public TicketHistory comment(JwtPrincipal actor, UUID ticketId, AddCommentRequest request) {
		Ticket ticket = ticketRepository.findWithUsersById(ticketId)
				.orElseThrow(TicketNotFoundException::new);
		assertCanRead(actor, ticket);
		User actorUser = userRepository.findById(actor.id()).orElseThrow();
		return recordHistory(ticket, actorUser, TicketHistoryEventType.COMMENT, Map.of(
				"body", request.body().trim()));
	}

	@Transactional(readOnly = true)
	public java.util.List<TicketHistory> history(JwtPrincipal actor, UUID ticketId) {
		Ticket ticket = ticketRepository.findWithUsersById(ticketId)
				.orElseThrow(TicketNotFoundException::new);
		assertCanRead(actor, ticket);
		return historyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
	}

	private TicketHistory recordHistory(Ticket ticket, User actor, TicketHistoryEventType type, Map<String, ?> payload) {
		String json;
		try {
			json = objectMapper.writeValueAsString(payload);
		} catch (JacksonException e) {
			// Payloads are constructed from internal data only — any failure
			// here is a programming error, never user-driven.
			throw new IllegalStateException("failed to serialise history payload", e);
		}
		TicketHistory event = TicketHistory.record(ticket, actor, type, json, clock.instant());
		return historyRepository.save(event);
	}

	private void assertCanRead(JwtPrincipal actor, Ticket ticket) {
		if (isStaff(actor)) {
			return;
		}
		if (ticket.getSubmitter() == null || !ticket.getSubmitter().getId().equals(actor.id())) {
			throw new TicketNotFoundException();
		}
	}

	private void assertCanEditContent(JwtPrincipal actor, Ticket ticket) {
		assertCanRead(actor, ticket);
		if (isStaff(actor)) {
			return;
		}
		// Submitters can only edit the body of the ticket while it is still
		// OPEN — afterwards an agent is working it and rewrites would
		// invalidate context.
		if (ticket.getStatus() != TicketStatus.OPEN) {
			throw new InvalidTicketStateException(
					"ticket is " + ticket.getStatus() + ", body is no longer editable by submitter");
		}
	}

	private void assertCanChangeStatus(JwtPrincipal actor, Ticket ticket, TicketStatus next) {
		assertCanRead(actor, ticket);
		if (isStaff(actor)) {
			return;
		}
		// Submitters are only allowed one self-driven status change: closing
		// an OPEN ticket they no longer need help with.
		boolean userSelfClose = ticket.getStatus() == TicketStatus.OPEN && next == TicketStatus.CLOSED;
		if (!userSelfClose) {
			throw new InvalidTicketStateException("only an agent can change this ticket's status");
		}
	}

	private boolean isStaff(JwtPrincipal actor) {
		return actor.role() == UserRole.AGENT || actor.role() == UserRole.ADMIN;
	}
}
