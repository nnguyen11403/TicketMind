package com.ticketmind.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ErrorResponseHardeningTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void unknownRouteDoesNotLeakStackTrace() throws Exception {
		// Unauthenticated access to any unmapped route hits the auth wall first
		// (401). The secure default. The sanitized error body still applies.
		mockMvc.perform(get("/does-not-exist").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.trace").doesNotExist())
				.andExpect(jsonPath("$.exception").doesNotExist());
	}

	@Test
	void unknownRouteDoesNotLeakInternalMessage() throws Exception {
		// include-message=never causes Spring to omit the field entirely,
		// which is stronger than emitting an empty string.
		mockMvc.perform(get("/does-not-exist").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").doesNotExist())
				.andExpect(jsonPath("$.errors").doesNotExist())
				.andExpect(jsonPath("$.path").doesNotExist());
	}
}
