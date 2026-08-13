package com.ticketmind.backend.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmind.backend.TestcontainersConfiguration;
import com.ticketmind.backend.ticket.Ticket;
import com.ticketmind.backend.ticket.TicketHistory;
import com.ticketmind.backend.ticket.TicketHistoryEventType;
import com.ticketmind.backend.ticket.TicketHistoryRepository;
import com.ticketmind.backend.ticket.TicketPriority;
import com.ticketmind.backend.ticket.TicketRepository;
import com.ticketmind.backend.user.RefreshTokenRepository;
import com.ticketmind.backend.user.User;
import com.ticketmind.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the triage pipeline against a real database with the RAG service
 * stubbed at the HTTP boundary.
 *
 * <p>The service under test is wired by hand rather than autowired so the call
 * is synchronous: the production path runs it on the RAG executor after commit,
 * which would make these assertions racy for no added coverage. What the async
 * hop itself does, that the events fire at all, is asserted in
 * {@code TicketControllerIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TicketTriageServiceIntegrationTest {

	private static final String BASE_URL = "http://rag.test";

	@Autowired private TicketRepository ticketRepository;
	@Autowired private TicketHistoryRepository historyRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private RefreshTokenRepository refreshTokenRepository;
	@Autowired private TicketTriageWriter triageWriter;

	// ObjectMapper is not an autowireable bean in these slices.
	private final ObjectMapper objectMapper = new ObjectMapper();

	private MockRestServiceServer server;
	private TicketTriageService service;
	private User alice;

	@BeforeEach
	void setUp() {
		historyRepository.deleteAllInBatch();
		ticketRepository.deleteAllInBatch();
		refreshTokenRepository.deleteAllInBatch();
		userRepository.deleteAllInBatch();
		alice = userRepository.saveAndFlush(User.create(
				"alice@example.com",
				"{bcrypt}$2a$04$0000000000000000000000000000000000000000000000000000",
				"Alice"));

		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		this.server = MockRestServiceServer.bindTo(builder).build();
		RagProperties properties = new RagProperties(
				true, URI.create(BASE_URL), "test-internal-key",
				Duration.ofSeconds(1), Duration.ofSeconds(2));
		RagClient ragClient = new RagClient(builder.build(), properties);
		this.service = new TicketTriageService(ticketRepository, ragClient, triageWriter);
	}

	@Test
	void triageWritesTheResultOntoTheTicketAndRecordsAnAuditEvent() throws Exception {
		UUID ticketId = openTicket("Card charged twice", "I was billed 40 instead of 20");
		respondToTriageWith("billing", "HIGH", "Double charge.", "Refund the duplicate.");

		service.triage(ticketId);

		server.verify();
		Ticket triaged = ticketRepository.findById(ticketId).orElseThrow();
		assertThat(triaged.getCategory()).isEqualTo("billing");
		assertThat(triaged.getPriority()).isEqualTo(TicketPriority.HIGH);
		assertThat(triaged.getSuggestedResolution()).isEqualTo("Refund the duplicate.");
		assertThat(triaged.getTriagedAt()).isNotNull();

		List<TicketHistory> history = historyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
		TicketHistory triagedEvent = history.stream()
				.filter(h -> h.getEventType() == TicketHistoryEventType.TRIAGED)
				.findFirst()
				.orElseThrow();
		// No human performed this, so the audit row carries a null actor.
		assertThat(triagedEvent.getActor()).isNull();
		// Parsed rather than string-matched: the column is jsonb, so Postgres
		// re-serialises the payload with its own key order and spacing.
		JsonNode payload = objectMapper.readTree(triagedEvent.getPayload());
		assertThat(payload.get("category").asText()).isEqualTo("billing");
		assertThat(payload.get("priority").asText()).isEqualTo("HIGH");
		assertThat(payload.get("summary").asText()).isEqualTo("Double charge.");
		assertThat(payload.get("citations")).isEmpty();
	}

	@Test
	void everyPriorityTheRagServiceCanReturnIsAcceptedByTheDatabase() {
		// The RAG service's Priority literal and the tickets_priority_check
		// constraint have to agree; this fails loudly if they drift apart.
		// All expectations are registered up front, MockRestServiceServer
		// refuses new ones once a request has been made.
		TicketPriority[] priorities = TicketPriority.values();
		List<UUID> ticketIds = new ArrayList<>();
		for (TicketPriority priority : priorities) {
			ticketIds.add(openTicket("t-" + priority, "body"));
			respondToTriageWith("general", priority.name(), "s", "r");
		}

		for (int i = 0; i < priorities.length; i++) {
			service.triage(ticketIds.get(i));
			assertThat(ticketRepository.findById(ticketIds.get(i)).orElseThrow().getPriority())
					.isEqualTo(priorities[i]);
		}
		server.verify();
	}

	@Test
	void anUnknownPriorityIsDiscardedRatherThanReachingTheCheckConstraint() {
		UUID ticketId = openTicket("Weird", "body");
		respondToTriageWith("general", "URGENT", "s", "r");

		service.triage(ticketId);

		server.verify();
		Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();
		assertThat(ticket.getPriority()).isNull();
		assertThat(ticket.getTriagedAt()).isNull();
		assertThat(historyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId))
				.noneMatch(h -> h.getEventType() == TicketHistoryEventType.TRIAGED);
	}

	@Test
	void aFailedTriageLeavesTheTicketUntouched() {
		UUID ticketId = openTicket("Cannot log in", "reset link never arrives");
		server.expect(requestTo(BASE_URL + "/triage"))
				.andRespond(withStatus(HttpStatus.BAD_GATEWAY)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"detail\":\"upstream_error\"}"));

		service.triage(ticketId);

		server.verify();
		Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();
		assertThat(ticket.getCategory()).isNull();
		assertThat(ticket.getPriority()).isNull();
		assertThat(ticket.getTriagedAt()).isNull();
	}

	@Test
	void anAlreadyTriagedTicketIsNotSentASecondTime() {
		UUID ticketId = openTicket("Card charged twice", "billed twice");
		respondToTriageWith("billing", "HIGH", "s", "r");
		service.triage(ticketId);
		server.verify();
		server.reset();

		// No expectation registered, verify() fails if a second call goes out.
		service.triage(ticketId);
		server.verify();
	}

	@Test
	void triagingAMissingTicketIsANoOp() {
		service.triage(UUID.randomUUID());
		server.verify();
	}

	@Test
	void publishingAResolvedTicketSendsItToTheKnowledgeBaseKeyedByTicketId() {
		UUID ticketId = openTicket("Card charged twice", "I was billed 40 instead of 20");
		respondToTriageWith("billing", "HIGH", "s", "r");
		service.triage(ticketId);
		server.verify();
		server.reset();

		server.expect(requestTo(BASE_URL + "/kb/documents"))
				.andExpect(jsonPath("$.external_id").value(ticketId.toString()))
				.andExpect(jsonPath("$.title").value("Card charged twice"))
				.andExpect(jsonPath("$.body").value("I was billed 40 instead of 20"))
				// Category comes from the earlier triage, so the KB entry is
				// filed under the same taxonomy retrieval will search.
				.andExpect(jsonPath("$.category").value("billing"))
				.andRespond(withStatus(HttpStatus.CREATED)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"id\":\"1\",\"external_id\":\"x\",\"title\":\"t\",\"category\":null,"
								+ "\"updated_at\":\"2026-01-01T00:00:00Z\"}"));

		service.publishResolvedTicket(ticketId);
		server.verify();
	}

	private UUID openTicket(String title, String description) {
		Ticket ticket = Ticket.open(alice, title, description);
		return ticketRepository.saveAndFlush(ticket).getId();
	}

	private void respondToTriageWith(String category, String priority, String summary, String resolution) {
		server.expect(requestTo(BASE_URL + "/triage"))
				.andRespond(withSuccess("""
						{
						  "category": "%s",
						  "priority": "%s",
						  "summary": "%s",
						  "suggested_resolution": "%s",
						  "citations": []
						}
						""".formatted(category, priority, summary, resolution), MediaType.APPLICATION_JSON));
	}
}
