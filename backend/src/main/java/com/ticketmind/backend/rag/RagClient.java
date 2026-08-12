package com.ticketmind.backend.rag;

import com.ticketmind.backend.rag.dto.KbDocumentPayload;
import com.ticketmind.backend.rag.dto.TriageRequestPayload;
import com.ticketmind.backend.rag.dto.TriageResultPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * Thin HTTP client for the Python RAG service.
 *
 * <p>Every method is best-effort and never throws: triage and knowledge-base
 * mirroring are enrichment, not part of the ticket contract. A RAG outage must
 * degrade the product (no category, no suggestion) rather than break it.
 */
@Component
public class RagClient {

	private static final Logger log = LoggerFactory.getLogger(RagClient.class);

	// The RAG service caps title at 256 and body at 32000 characters and
	// answers 422 above them. Ticket titles are already bounded at 200 by the
	// column, but descriptions are TEXT — truncate rather than let a long
	// ticket silently fail triage.
	private static final int MAX_TITLE = 256;
	private static final int MAX_BODY = 32_000;
	private static final int MAX_CATEGORY = 64;

	private final RestClient restClient;
	private final boolean enabled;

	public RagClient(RestClient ragRestClient, RagProperties properties) {
		this.restClient = ragRestClient;
		this.enabled = properties.enabled();
	}

	public Optional<TriageResultPayload> triage(UUID ticketId, String title, String description) {
		if (!enabled) {
			return Optional.empty();
		}
		TriageRequestPayload payload = new TriageRequestPayload(
				ticketId.toString(),
				truncate(title, MAX_TITLE),
				truncate(description, MAX_BODY));
		try {
			TriageResultPayload result = restClient.post()
					.uri("/triage")
					.body(payload)
					.retrieve()
					.body(TriageResultPayload.class);
			return Optional.ofNullable(result);
		} catch (RestClientException ex) {
			// Includes the RAG service's own 502 upstream_error, which means
			// Claude returned something unparseable. Retrying immediately would
			// likely fail the same way; the ticket simply stays untriaged.
			log.warn("triage call failed for ticket {}: {}", ticketId, ex.getMessage());
			return Optional.empty();
		}
	}

	public boolean upsertKnowledgeBaseDocument(UUID ticketId, String title, String body, String category) {
		if (!enabled) {
			return false;
		}
		KbDocumentPayload payload = new KbDocumentPayload(
				ticketId.toString(),
				truncate(title, MAX_TITLE),
				truncate(body, MAX_BODY),
				truncate(category, MAX_CATEGORY));
		try {
			restClient.post()
					.uri("/kb/documents")
					.body(payload)
					.retrieve()
					.toBodilessEntity();
			return true;
		} catch (RestClientException ex) {
			log.warn("knowledge-base upsert failed for ticket {}: {}", ticketId, ex.getMessage());
			return false;
		}
	}

	private static String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max);
	}
}
