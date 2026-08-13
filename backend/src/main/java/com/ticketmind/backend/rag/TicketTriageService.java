package com.ticketmind.backend.rag;

import com.ticketmind.backend.common.exception.TicketNotFoundException;
import com.ticketmind.backend.common.exception.TriageDisabledException;
import com.ticketmind.backend.common.exception.TriageFailedException;
import com.ticketmind.backend.rag.dto.TriageResultPayload;
import com.ticketmind.backend.security.jwt.JwtPrincipal;
import com.ticketmind.backend.user.UserRole;
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
	 * Staff-initiated re-triage. Unlike the automatic path this is synchronous
	 * and reports failure: the whole point is that somebody clicked a button
	 * and needs to know whether it worked, so silently degrading the way the
	 * post-commit listener does would be useless here.
	 *
	 * <p>It overwrites an existing result and appends a second TRIAGED row -
	 * the audit trail should record that a human asked for another opinion.
	 *
	 * <p>Motivating case: provider rate limits. Voyage's free tier allows 3
	 * requests a minute, so a burst of tickets leaves some untriaged through no
	 * fault of the ticket or the operator.
	 */
	public void retriage(JwtPrincipal actor, UUID ticketId) {
		if (!isStaff(actor)) {
			// Matches TicketService.assign: a non-staff caller learns nothing
			// about which ticket ids exist.
			throw new TicketNotFoundException();
		}
		if (!ragClient.isEnabled()) {
			throw new TriageDisabledException();
		}
		Snapshot snapshot = loadSnapshot(ticketId);
		if (snapshot == null) {
			throw new TicketNotFoundException();
		}
		TriageResultPayload result = ragClient
				.triage(ticketId, snapshot.title(), snapshot.description())
				.orElseThrow(TriageFailedException::new);
		if (!triageWriter.applyTriage(ticketId, result, true)) {
			// The call succeeded but the result was unusable, an unknown
			// priority, or the ticket vanished mid-flight.
			throw new TriageFailedException();
		}
		log.info("re-triaged ticket {} as {}/{}", ticketId, result.category(), result.priority());
	}

	private boolean isStaff(JwtPrincipal actor) {
		return actor.role() == UserRole.AGENT || actor.role() == UserRole.ADMIN;
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
	// annotation would be silently inert. It doesn't need one, findById runs
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
