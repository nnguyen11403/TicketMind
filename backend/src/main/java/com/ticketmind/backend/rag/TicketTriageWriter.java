package com.ticketmind.backend.rag;

import com.ticketmind.backend.rag.dto.TriageResultPayload;
import com.ticketmind.backend.ticket.Ticket;
import com.ticketmind.backend.ticket.TicketHistory;
import com.ticketmind.backend.ticket.TicketHistoryEventType;
import com.ticketmind.backend.ticket.TicketHistoryRepository;
import com.ticketmind.backend.ticket.TicketPriority;
import com.ticketmind.backend.ticket.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists a triage result.
 *
 * <p>Deliberately a separate bean from {@link TicketTriageService}: the
 * orchestration there must not hold a database transaction open across the
 * (multi-second) call to Claude, while the ticket update and its TRIAGED
 * history row must land atomically. Calling a {@code @Transactional} method on
 * the same class would self-invoke past the proxy and split them into two
 * transactions.
 */
@Component
public class TicketTriageWriter {

	private static final Logger log = LoggerFactory.getLogger(TicketTriageWriter.class);

	private final TicketRepository ticketRepository;
	private final TicketHistoryRepository historyRepository;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public TicketTriageWriter(
			TicketRepository ticketRepository,
			TicketHistoryRepository historyRepository,
			ObjectMapper objectMapper,
			Clock clock) {
		this.ticketRepository = ticketRepository;
		this.historyRepository = historyRepository;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/** Automatic path: first result wins, never overwrites. */
	@Transactional
	public boolean applyTriage(UUID ticketId, TriageResultPayload result) {
		return applyTriage(ticketId, result, false);
	}

	/**
	 * @param force when true an already-triaged ticket is overwritten. Only the
	 *              staff-initiated re-triage sets this: the automatic path must
	 *              stay first-write-wins so two listeners racing on the same
	 *              ticket cannot produce two TRIAGED rows.
	 */
	@Transactional
	public boolean applyTriage(UUID ticketId, TriageResultPayload result, boolean force) {
		Optional<TicketPriority> priority = parsePriority(result.priority());
		if (priority.isEmpty()) {
			log.warn("discarding triage for ticket {}: unknown priority {}", ticketId, result.priority());
			return false;
		}
		Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
		if (ticket == null) {
			// Deleted between the triage call and the write-back.
			return false;
		}
		if (ticket.getTriagedAt() != null && !force) {
			// A concurrent triage won the race; first result wins so the
			// history stays a single TRIAGED entry.
			return false;
		}

		Instant now = clock.instant();
		ticket.applyTriage(result.category(), priority.get(), result.suggestedResolution(), now);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("category", result.category());
		payload.put("priority", priority.get().name());
		payload.put("summary", result.summary());
		payload.put("retriggered", force);
		payload.put("citations", result.citationsOrEmpty().stream()
				.map(TriageResultPayload.Citation::externalId)
				.toList());
		// actor is null: triage is performed by the system, not a user. The
		// actor_id column is nullable precisely for this case.
		historyRepository.save(TicketHistory.record(
				ticket, null, TicketHistoryEventType.TRIAGED, toJson(payload), now));
		return true;
	}

	private Optional<TicketPriority> parsePriority(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(TicketPriority.valueOf(raw.trim().toUpperCase()));
		} catch (IllegalArgumentException ex) {
			// The RAG service constrains this to the same four values, but it
			// is an out-of-process contract, an unknown value must not reach
			// the tickets_priority_check constraint and blow up the write.
			return Optional.empty();
		}
	}

	private String toJson(Map<String, ?> payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JacksonException e) {
			throw new IllegalStateException("failed to serialise triage history payload", e);
		}
	}
}
