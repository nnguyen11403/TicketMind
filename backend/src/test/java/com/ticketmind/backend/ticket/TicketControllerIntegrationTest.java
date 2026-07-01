package com.ticketmind.backend.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmind.backend.TestcontainersConfiguration;
import com.ticketmind.backend.security.jwt.JwtService;
import com.ticketmind.backend.ticket.dto.AddCommentRequest;
import com.ticketmind.backend.ticket.dto.AssignTicketRequest;
import com.ticketmind.backend.ticket.dto.ChangeStatusRequest;
import com.ticketmind.backend.ticket.dto.CreateTicketRequest;
import com.ticketmind.backend.ticket.dto.UpdateTicketRequest;
import com.ticketmind.backend.user.RefreshTokenRepository;
import com.ticketmind.backend.user.User;
import com.ticketmind.backend.user.UserRepository;
import com.ticketmind.backend.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TicketControllerIntegrationTest {

	@Autowired private MockMvc mockMvc;
	@Autowired private UserRepository userRepository;
	@Autowired private TicketRepository ticketRepository;
	@Autowired private TicketHistoryRepository historyRepository;
	@Autowired private RefreshTokenRepository refreshTokenRepository;
	@Autowired private JwtService jwtService;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private User alice; // USER, submitter
	private User bob;   // USER, other
	private User agent; // AGENT
	private User admin; // ADMIN

	@BeforeEach
	void resetState() {
		historyRepository.deleteAllInBatch();
		ticketRepository.deleteAllInBatch();
		refreshTokenRepository.deleteAllInBatch();
		userRepository.deleteAllInBatch();
		alice = createUser("alice@example.com", "Alice", UserRole.USER);
		bob = createUser("bob@example.com", "Bob", UserRole.USER);
		agent = createUser("agent@example.com", "Agent", UserRole.AGENT);
		admin = createUser("admin@example.com", "Admin", UserRole.ADMIN);
	}

	@Test
	void userCanSubmitTicketAndSeeItButNotOthers() throws Exception {
		UUID aliceTicket = createTicket(alice, "Cannot log in", "Password reset link never arrives");
		UUID bobTicket = createTicket(bob, "Slow dashboard", "Loads in 12 seconds");

		mockMvc.perform(get("/tickets/" + aliceTicket).header(HttpHeaders.AUTHORIZATION, bearerFor(alice)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Cannot log in"))
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.submitter.email").value("alice@example.com"));

		// Bob's ticket is invisible to Alice — must surface as 404, not 403.
		mockMvc.perform(get("/tickets/" + bobTicket).header(HttpHeaders.AUTHORIZATION, bearerFor(alice)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ticket_not_found"));
	}

	@Test
	void staffSeeAllTicketsUsersOnlySeeOwn() throws Exception {
		createTicket(alice, "A1", "desc");
		createTicket(alice, "A2", "desc");
		createTicket(bob, "B1", "desc");

		mockMvc.perform(get("/tickets").header(HttpHeaders.AUTHORIZATION, bearerFor(alice)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2));

		mockMvc.perform(get("/tickets").header(HttpHeaders.AUTHORIZATION, bearerFor(agent)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3));

		mockMvc.perform(get("/tickets?status=OPEN&page=0&size=1").header(HttpHeaders.AUTHORIZATION, bearerFor(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(3))
				.andExpect(jsonPath("$.content.length()").value(1));
	}

	@Test
	void submitterCanEditWhileOpenButNotAfterStatusChanges() throws Exception {
		UUID id = createTicket(alice, "Original", "old body");

		mockMvc.perform(patch("/tickets/" + id)
						.header(HttpHeaders.AUTHORIZATION, bearerFor(alice))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new UpdateTicketRequest("Edited", "new body"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Edited"));

		// Agent moves ticket to IN_PROGRESS — now Alice can no longer edit body.
		mockMvc.perform(post("/tickets/" + id + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(agent))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeStatusRequest(TicketStatus.IN_PROGRESS))))
				.andExpect(status().isOk());

		mockMvc.perform(patch("/tickets/" + id)
						.header(HttpHeaders.AUTHORIZATION, bearerFor(alice))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new UpdateTicketRequest("t", "b"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("invalid_ticket_state"));
	}

	@Test
	void submitterCanSelfCloseOpenTicketButNotChangeToOtherStatuses() throws Exception {
		UUID id = createTicket(alice, "T", "d");

		mockMvc.perform(post("/tickets/" + id + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(alice))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeStatusRequest(TicketStatus.CLOSED))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CLOSED"));

		UUID id2 = createTicket(alice, "T2", "d2");
		mockMvc.perform(post("/tickets/" + id2 + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(alice))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeStatusRequest(TicketStatus.IN_PROGRESS))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("invalid_ticket_state"));
	}

	@Test
	void onlyStaffCanAssignAndOnlyToAgentsOrAdmins() throws Exception {
		UUID id = createTicket(alice, "T", "d");

		// USER cannot assign — surfaces as 404 (same as any non-authz read).
		mockMvc.perform(post("/tickets/" + id + "/assign")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(alice))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new AssignTicketRequest(agent.getId()))))
				.andExpect(status().isNotFound());

		// Agent tries to assign to a plain USER — rejected as agent_required.
		mockMvc.perform(post("/tickets/" + id + "/assign")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(agent))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new AssignTicketRequest(bob.getId()))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("agent_required"));

		// Admin assigns to agent successfully, and then unassigns.
		mockMvc.perform(post("/tickets/" + id + "/assign")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(admin))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new AssignTicketRequest(agent.getId()))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignee.email").value("agent@example.com"));

		mockMvc.perform(post("/tickets/" + id + "/assign")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(admin))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new AssignTicketRequest(null))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignee").doesNotExist());
	}

	@Test
	void commentsAndHistoryVisibleToParticipants() throws Exception {
		UUID id = createTicket(alice, "Broken", "help");

		mockMvc.perform(post("/tickets/" + id + "/comments")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(agent))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new AddCommentRequest("Looking into it"))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.eventType").value("COMMENT"));

		mockMvc.perform(get("/tickets/" + id + "/history")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(alice)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].eventType").value("CREATED"))
				.andExpect(jsonPath("$[1].eventType").value("COMMENT"));

		// Bob has no relationship to the ticket — history call surfaces 404.
		mockMvc.perform(get("/tickets/" + id + "/history")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(bob)))
				.andExpect(status().isNotFound());
	}

	@Test
	void statusChangeRecordsResolvedAndReopenedEvents() throws Exception {
		UUID id = createTicket(alice, "T", "d");

		mockMvc.perform(post("/tickets/" + id + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(agent))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeStatusRequest(TicketStatus.RESOLVED))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RESOLVED"))
				.andExpect(jsonPath("$.resolvedAt").exists());

		mockMvc.perform(post("/tickets/" + id + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(agent))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeStatusRequest(TicketStatus.OPEN))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.resolvedAt").doesNotExist());

		MvcResult historyResult = mockMvc.perform(get("/tickets/" + id + "/history")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(alice)))
				.andExpect(status().isOk())
				.andReturn();

		JsonNode events = objectMapper.readTree(historyResult.getResponse().getContentAsString());
		assertThat(events.get(1).get("eventType").asText()).isEqualTo("RESOLVED");
		assertThat(events.get(2).get("eventType").asText()).isEqualTo("REOPENED");
	}

	@Test
	void closedTicketCannotBeReopened() throws Exception {
		UUID id = createTicket(alice, "T", "d");
		mockMvc.perform(post("/tickets/" + id + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(agent))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeStatusRequest(TicketStatus.CLOSED))))
				.andExpect(status().isOk());

		mockMvc.perform(post("/tickets/" + id + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(agent))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeStatusRequest(TicketStatus.OPEN))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("invalid_ticket_state"));
	}

	@Test
	void createValidatesTitleAndDescription() throws Exception {
		mockMvc.perform(post("/tickets")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(alice))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"\",\"description\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("validation_error"));
	}

	@Test
	void unauthenticatedRequestsAreRejected() throws Exception {
		mockMvc.perform(get("/tickets")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/tickets")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnauthorized());
	}

	private UUID createTicket(User submitter, String title, String description) throws Exception {
		MvcResult result = mockMvc.perform(post("/tickets")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(submitter))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateTicketRequest(title, description))))
				.andExpect(status().isCreated())
				.andReturn();
		return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
	}

	private String bearerFor(User user) {
		return "Bearer " + jwtService.issueAccessToken(user.getId(), user.getEmail(), user.getRole().name()).token();
	}

	private User createUser(String email, String displayName, UserRole role) {
		User u = User.create(email, "{bcrypt}$2a$04$0000000000000000000000000000000000000000000000000000", displayName);
		if (role != UserRole.USER) {
			forceRole(u, role);
		}
		return userRepository.saveAndFlush(u);
	}

	// Direct role assignment: User.create() intentionally seals the role to
	// USER so the register endpoint can't be tricked into minting an agent.
	// In tests we need seed data with elevated roles, so reflect it in.
	private void forceRole(User u, UserRole role) {
		try {
			Field f = User.class.getDeclaredField("role");
			f.setAccessible(true);
			f.set(u, role);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}
}
