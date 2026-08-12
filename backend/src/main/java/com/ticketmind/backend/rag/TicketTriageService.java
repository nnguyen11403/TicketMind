package com.ticketmind.backend.rag;

import com.ticketmind.backend.rag.dto.TriageResultPayload;
import com.ticketmind.backend.ticket.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the two directions of the RAG integration.
 *
 * <p>Intentionally not {@code @Transactional} at the class level: both methods
 * make an outbound HTTP call that can take seconds, and holding a Hikari
 * connection for that long would exhaust the pool under load. Reads run in the
 * repository's own short transaction, the network call happens with no
 * transaction open, and the write-back is delegated to
 * {@link TicketTriageWriter}.
 */
@Service
public class TicketTriageService {

	private static final Logger log = LoggerFactory.getLogger(TicketTriageService.class);

	private final TicketRepository ticketRepository;
	private final RagClient ragClient;
	private final TicketTriageWriter triageWriter;

	public TicketTriageService(
			TicketRepository ticketRepository,
			RagClient ragClient,
			TicketTriageWriter triageWriter) {
		this.ticketRepository = ticketRepository;
		this.ragClient = ragClient;
		this.triageWriter = triageWriter;
	}

	/** Triage a freshly created ticket. No-op if it is already triaged. */
	public void triage(UUID ticketId) {
		Snapshot snapshot = loadSnapshot(ticketId);
		if (snapshot == null) {
			return;
		}
		if (snapshot.triaged()) {
			return;
		}
		Optional<TriageResultPayload> result =
				ragClient.triage(ticketId, snapshot.title(), snapshot.description());
		if (result.isEmpty()) {
			return;
		}
		if (triageWriter.applyTriage(ticketId, result.get())) {
			log.info("triaged ticket {} as {}/{}", ticketId,
					result.get().category(), result.get().priority());
		}
	}

	/**
	 * Mirror a resolved ticket into the RAG knowledge base so later triage runs
	 * can retrieve it. Upsert-by-external-id makes repeated resolution
	 * idempotent, which matters because a ticket can be reopened and resolved
	 * again.
	 */
	public void publishResolvedTicket(UUID ticketId) {
		Snapshot snapshot = loadSnapshot(ticketId);
		if (snapshot == null) {
			return;
		}
		ragClient.upsertKnowledgeBaseDocument(
				ticketId, snapshot.title(), snapshot.description(), snapshot.category());
	}

	// No @Transactional: this is called from within the class, so a proxy-based
	// annotation would be silently inert. It doesn't need one — findById runs
	// in the repository's own transaction and every field read here is eager.
	private Snapshot loadSnapshot(UUID ticketId) {
		return ticketRepository.findById(ticketId)
				.map(t -> new Snapshot(
						t.getTitle(), t.getDescription(), t.getCategory(), t.getTriagedAt() != null))
				.orElse(null);
	}

	/**
	 * Detached copy of the fields the RAG service needs. Passing the entity
	 * around would risk lazy loading outside a session on the async thread.
	 */
	record Snapshot(String title, String description, String category, boolean triaged) {
	}
}
