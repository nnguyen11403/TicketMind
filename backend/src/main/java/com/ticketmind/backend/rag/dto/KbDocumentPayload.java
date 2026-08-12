package com.ticketmind.backend.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Wire shape of POST /kb/documents. The RAG service upserts by external_id,
// so re-sending the same ticket is idempotent.
public record KbDocumentPayload(
		@JsonProperty("external_id") String externalId,
		@JsonProperty("title") String title,
		@JsonProperty("body") String body,
		@JsonProperty("category") String category) {
}
