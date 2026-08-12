package com.ticketmind.backend.rag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TriageResultPayload(
		@JsonProperty("category") String category,
		@JsonProperty("priority") String priority,
		@JsonProperty("summary") String summary,
		@JsonProperty("suggested_resolution") String suggestedResolution,
		@JsonProperty("citations") List<Citation> citations) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Citation(
			@JsonProperty("external_id") String externalId,
			@JsonProperty("title") String title,
			@JsonProperty("score") double score) {
	}

	public List<Citation> citationsOrEmpty() {
		return citations == null ? List.of() : citations;
	}
}
