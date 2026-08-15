package com.ticketmind.backend.security;

import com.ticketmind.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the full context with TLS enforcement ON.
 *
 * <p>This class exists because the enforcement was originally keyed off the
 * active profile, which made it unreachable from the suite: dev and test both
 * disable it and those are the only profiles the tests run under. The first
 * thing to exercise the enabled path was a production container, and it
 * crash-looped — the Spring Security 7 removal of {@code requiresChannel()}
 * turns into a {@code NoClassDefFoundError} at context startup, not at compile
 * time. Simply starting the context here is most of the value.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "app.security.require-https=true")
class HttpsEnforcementIntegrationTest {

	@Autowired private MockMvc mockMvc;

	@Test
	void insecureRequestIsRefused() throws Exception {
		mockMvc.perform(get("/tickets"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("https_required"));
	}

	/**
	 * Refused before authentication, so an unauthenticated caller and one
	 * holding a valid token get the same answer, and the token is never parsed.
	 */
	@Test
	void insecureRequestIsRefusedBeforeAuthentication() throws Exception {
		mockMvc.perform(post("/auth/login")
						.contentType("application/json")
						.content("{\"email\":\"a@example.com\",\"password\":\"whatever12345\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("https_required"));
	}

	@Test
	void secureRequestPassesThroughToNormalAuthentication() throws Exception {
		mockMvc.perform(get("/tickets").secure(true))
				.andExpect(status().isUnauthorized());
	}

	/**
	 * The compose healthcheck probes this over loopback http. Without the
	 * exemption the backend never reports healthy, and compose gates both the
	 * RAG service and the frontend on exactly that — so this assertion is the
	 * difference between a stack that starts and one that does not.
	 */
	@Test
	void actuatorHealthIsReachableWithoutTls() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk());
	}
}
