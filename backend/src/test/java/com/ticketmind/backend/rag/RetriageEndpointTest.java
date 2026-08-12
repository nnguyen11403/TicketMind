package com.ticketmind.backend.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmind.backend.TestcontainersConfiguration;
import com.ticketmind.backend.security.jwt.JwtService;
import com.ticketmind.backend.ticket.Ticket;
import com.ticketmind.backend.ticket.TicketHistory;
import com.ticketmind.backend.ticket.TicketHistoryEventType;
import com.ticketmind.backend.ticket.TicketHistoryRepository;
import com.ticketmind.backend.ticket.TicketPriority;
import com.ticketmind.backend.ticket.TicketRepository;
import com.ticketmind.backend.user.RefreshTokenRepository;
import com.ticketmind.backend.user.User;
import com.ticketmind.backend.user.UserRepository;
import com.ticketmind.backend.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The re-triage endpoint exists because provider rate limits make a failed
 * automatic triage routine — Voyage's free tier allows three requests a
 * minute, so a burst of tickets leaves some untriaged with nothing wrong.
 *
 * <p>The HTTP surface is driven through MockMvc, but the RAG call is stubbed
 * at the socket boundary, so this covers authorization, the overwrite, the
 * audit row, and every failure code.
 */
@SpringBootTest(properties = {
		"app.rag.enabled=true",
		"app.rag.base-url=http://rag.test",
		"app.rag.internal-key=test-internal-key"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RetriageEndpointTest.StubRagConfig.class})
class RetriageEndpointTest {

	/**
	 * MockRestServiceServer binds to a RestClient.Builder, not a built client,
	 * so the app's ragRestClient bean cannot be intercepted after the fact.
	 * This supplies a @Primary replacement built from a bound builder — the
	 * ordering matters, which is why the client bean depends on the server.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class StubRagConfig {

		@Bean
		RestClient.Builder ragStubBuilder() {
			return RestClient.builder().baseUrl(BASE_URL);
		}

		@Bean
		MockRestServiceServer mockRagServer(RestClient.Builder ragStubBuilder) {
			return MockRestServiceServer.bindTo(ragStubBuilder).build();
		}

		@Bean
		@Primary
		RestClient stubRagRestClient(RestClient.Builder ragStubBuilder, MockRestServiceServer mockRagServer) {
			return ragStubBuilder.build();
		}
	}

	private static final String BASE_URL = "http://rag.test";

	@Autowired private MockMvc mockMvc;
	@Autowired private TicketRepository ticketRepository;
	@Autowired private TicketHistoryRepository historyRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private RefreshTokenRepository refreshTokenRepository;
	@Autowired private JwtService jwtService;
	@Autowired private TicketTriageWriter triageWriter;
	@Autowired private MockRestServiceServer mockRagServer;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private User alice;
	private User agent;
	private UUID ticketId;

	@BeforeEach
	void resetState() {
		historyRepository.deleteAllInBatch();
		ticketRepository.deleteAllInBatch();
		refreshTokenRepository.deleteAllInBatch();
		userRepository.deleteAllInBatch();
		// The stub server is a singleton in this context, so expectations from
		// a previous test would otherwise leak into the next one.
		mockRagServer.reset();
		alice = createUser("alice@example.com", "Alice", UserRole.USER);
		agent = createUser("agent@example.com", "Agent", UserRole.AGENT);
		ticketId = ticketRepository
				.saveAndFlush(Ticket.open(alice, "Card charged twice", "billed 40 not 20"))
				.getId();
	}

	@Test
	void staffCanRetriageAnUntriagedTicket() throws Exception {
		MockRestServiceServer server = mockRagServer;
		server.expect(requestTo(BASE_URL + "/triage")).andRespond(triageResponse("billing", "HIGH"));

		mockMvc.perform(post("/tickets/" + ticketId + "/triage")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(agent)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.category").value("billing"))
				.andExpect(jsonPath("$.priority").value("HIGH"))
				.andExpect(jsonPath("$.triagedAt").isNotEmpty());

		server.verify();
	}

	@Test
	void retriageOverwritesAnEarlierResultAndAppendsASecondAuditRow() throws Exception {
		// Seed a first result the way the automatic path would.
		triageWriter.applyTriage(ticketId, payload("general", "LOW"));

		MockRestServiceServer server = mockRagServer;
		server.expect(requestTo(BASE_URL + "/triage")).andRespond(triageResponse("billing", "CRITICAL"));

		mockMvc.perform(post("/tickets/" + ticketId + "/triage")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(agent)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.priority").value("CRITICAL"));

		server.verify();
		assertThat(ticketRepository.findById(ticketId).orElseThrow().getPriority())
				.isEqualTo(TicketPriority.CRITICAL);

		// Two TRIAGED rows: the history should show a human asked again rather
		// than the first verdict being quietly rewritten.
		List<TicketHistory> triaged = historyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId)
				.stream().filter(h -> h.getEventType() == TicketHistoryEventType.TRIAGED).toList();
		assertThat(triaged).hasSize(2);
		JsonNode first = objectMapper.readTree(triaged.get(0).getPayload());
		JsonNode second = objectMapper.readTree(triaged.get(1).getPayload());
		assertThat(first.get("retriggered").asBoolean()).isFalse();
		assertThat(second.get("retriggered").asBoolean()).isTrue();
	}

	@Test
	void aSubmitterCannotRetriageTheirOwnTicket() throws Exception {
		// 404 rather than 403, matching assign: a non-staff caller learns
		// nothing about which ticket ids exist.
		mockMvc.perform(post("/tickets/" + ticketId + "/triage")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(alice)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ticket_not_found"));

		assertThat(ticketRepository.findById(ticketId).orElseThrow().getTriagedAt()).isNull();
	}

	@Test
	void anUpstreamFailureIsReportedRatherThanSwallowed() throws Exception {
		// The automatic path degrades silently on purpose. This one must not:
		// somebody clicked a button and needs to know it did not work.
		MockRestServiceServer server = mockRagServer;
		server.expect(requestTo(BASE_URL + "/triage"))
				.andRespond(withStatus(HttpStatus.BAD_GATEWAY)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"detail\":\"upstream_error\"}"));

		mockMvc.perform(post("/tickets/" + ticketId + "/triage")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(agent)))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("triage_failed"));

		server.verify();
		assertThat(ticketRepository.findById(ticketId).orElseThrow().getTriagedAt()).isNull();
	}

	@Test
	void anUnknownPriorityFromTheModelIsReportedAsAFailure() throws Exception {
		MockRestServiceServer server = mockRagServer;
		server.expect(requestTo(BASE_URL + "/triage")).andRespond(triageResponse("billing", "URGENT"));

		mockMvc.perform(post("/tickets/" + ticketId + "/triage")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(agent)))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("triage_failed"));

		server.verify();
		assertThat(ticketRepository.findById(ticketId).orElseThrow().getPriority()).isNull();
	}

	@Test
	void retriagingAMissingTicketIs404() throws Exception {
		mockMvc.perform(post("/tickets/" + UUID.randomUUID() + "/triage")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(agent)))
				.andExpect(status().isNotFound());
	}

	private static org.springframework.test.web.client.ResponseCreator triageResponse(
			String category, String priority) {
		return withSuccess("""
				{"category":"%s","priority":"%s","summary":"s",
				 "suggested_resolution":"r","citations":[]}
				""".formatted(category, priority), MediaType.APPLICATION_JSON);
	}

	private static com.ticketmind.backend.rag.dto.TriageResultPayload payload(
			String category, String priority) {
		return new com.ticketmind.backend.rag.dto.TriageResultPayload(
				category, priority, "s", "r", List.of());
	}

	private String bearerFor(User user) {
		return "Bearer " + jwtService
				.issueAccessToken(user.getId(), user.getEmail(), user.getRole().name()).token();
	}

	private User createUser(String email, String displayName, UserRole role) {
		User u = User.create(email,
				"{bcrypt}$2a$04$0000000000000000000000000000000000000000000000000000", displayName);
		if (role != UserRole.USER) {
			try {
				Field field = User.class.getDeclaredField("role");
				field.setAccessible(true);
				field.set(u, role);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}
		return userRepository.saveAndFlush(u);
	}
}
