package com.ticketmind.backend.rag;

import com.ticketmind.backend.ticket.TicketCreatedEvent;
import com.ticketmind.backend.ticket.TicketResolvedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges ticket lifecycle events to the RAG service.
 *
 * <p>{@code AFTER_COMMIT} matters: triage must never see a ticket that the
 * transaction then rolls back, and the write-back needs a row that is actually
 * visible to a second connection. {@code @Async} then moves the work off the
 * request thread so an LLM round-trip is not charged to the user's POST.
 *
 * <p>Both handlers swallow everything. An exception thrown from an after-commit
 * listener cannot undo the commit, it would only produce a confusing log with
 * a successful HTTP response already on the wire.
 */
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class RagTicketListener {

	private static final Logger log = LoggerFactory.getLogger(RagTicketListener.class);

	private final TicketTriageService triageService;

	public RagTicketListener(TicketTriageService triageService) {
		this.triageService = triageService;
	}

	@Async("ragTaskExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onTicketCreated(TicketCreatedEvent event) {
		try {
			triageService.triage(event.ticketId());
		} catch (RuntimeException ex) {
			log.error("triage failed for ticket {}", event.ticketId(), ex);
		}
	}

	@Async("ragTaskExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onTicketResolved(TicketResolvedEvent event) {
		try {
			triageService.publishResolvedTicket(event.ticketId());
		} catch (RuntimeException ex) {
			log.error("knowledge-base publish failed for ticket {}", event.ticketId(), ex);
		}
	}
}
