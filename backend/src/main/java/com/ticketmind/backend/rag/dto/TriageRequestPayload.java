package com.ticketmind.backend.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Wire shape of POST /triage on the RAG service. Field names are explicit
// rather than relying on a global naming strategy so the contract with the
// Python service is readable at the call site.
public record TriageRequestPayload(
		@JsonProperty("ticket_id") String ticketId,
		@JsonProperty("title") String title,
		@JsonProperty("body") String body) {
}
