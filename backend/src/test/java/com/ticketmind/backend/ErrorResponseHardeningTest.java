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
	void notFoundResponseDoesNotLeakStackTrace() throws Exception {
		mockMvc.perform(get("/does-not-exist").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.trace").doesNotExist())
				.andExpect(jsonPath("$.exception").doesNotExist());
	}

	@Test
	void notFoundResponseDoesNotLeakInternalMessage() throws Exception {
		mockMvc.perform(get("/does-not-exist").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(""));
	}
}
