package com.ticketmind.backend.ticket;

import com.ticketmind.backend.TestcontainersConfiguration;
import com.ticketmind.backend.security.jwt.JwtService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TicketSortTest {

	@Autowired private MockMvc mockMvc;
	@Autowired private UserRepository userRepository;
	@Autowired private TicketRepository ticketRepository;
	@Autowired private TicketHistoryRepository historyRepository;
	@Autowired private RefreshTokenRepository refreshTokenRepository;
	@Autowired private JwtService jwtService;

	private User agent;

	@BeforeEach
	void seed() {
		historyRepository.deleteAllInBatch();
		ticketRepository.deleteAllInBatch();
		refreshTokenRepository.deleteAllInBatch();
		userRepository.deleteAllInBatch();
		agent = createUser("agent@example.com", UserRole.AGENT);

		// Created oldest to newest so creation order and urgency order disagree.
		// If they matched, a broken priority sort would still look correct.
		triaged("Low one", TicketPriority.LOW);
		triaged("Critical one", TicketPriority.CRITICAL);
		untriaged("Never triaged");
		triaged("Medium one", TicketPriority.MEDIUM);
		triaged("High one", TicketPriority.HIGH);
	}

	@Test
	void defaultsToNewestFirst() throws Exception {
		mockMvc.perform(get("/tickets").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].title").value("High one"))
				.andExpect(jsonPath("$.content[4].title").value("Low one"));
	}

	@Test
	void oldestFirstReversesIt() throws Exception {
		mockMvc.perform(get("/tickets?sort=OLDEST").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].title").value("Low one"))
				.andExpect(jsonPath("$.content[4].title").value("High one"));
	}

	@Test
	void mostUrgentFirstOrdersByUrgencyNotAlphabetically() throws Exception {
		// Sorting the stored enum names as text would give CRITICAL, HIGH, LOW,
		// MEDIUM. Medium ahead of low is the assertion that catches that.
		mockMvc.perform(get("/tickets?sort=PRIORITY_HIGH_FIRST")
						.header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].title").value("Critical one"))
				.andExpect(jsonPath("$.content[1].title").value("High one"))
				.andExpect(jsonPath("$.content[2].title").value("Medium one"))
				.andExpect(jsonPath("$.content[3].title").value("Low one"))
				// Untriaged has no priority and sinks to the bottom rather than
				// jumping the queue.
				.andExpect(jsonPath("$.content[4].title").value("Never triaged"));
	}

	@Test
	void leastUrgentFirstPutsUntriagedAtTheTop() throws Exception {
		mockMvc.perform(get("/tickets?sort=PRIORITY_LOW_FIRST")
						.header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].title").value("Never triaged"))
				.andExpect(jsonPath("$.content[1].title").value("Low one"))
				.andExpect(jsonPath("$.content[4].title").value("Critical one"));
	}

	@Test
	void sortSurvivesPagingAndCombinesWithTheStatusFilter() throws Exception {
		mockMvc.perform(get("/tickets?sort=PRIORITY_HIGH_FIRST&status=OPEN&page=0&size=2")
						.header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].title").value("Critical one"))
				.andExpect(jsonPath("$.content[1].title").value("High one"))
				.andExpect(jsonPath("$.totalElements").value(5));

		mockMvc.perform(get("/tickets?sort=PRIORITY_HIGH_FIRST&page=1&size=2")
						.header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].title").value("Medium one"));
	}

	@Test
	void anUnknownSortIsRejectedRatherThanIgnored() throws Exception {
		mockMvc.perform(get("/tickets?sort=BANANA").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isBadRequest());
	}

	private void triaged(String title, TicketPriority priority) {
		Ticket ticket = Ticket.open(agent, title, "body");
		ticket.applyTriage("general", priority, "do the thing", Instant.now());
		ticketRepository.saveAndFlush(ticket);
	}

	private void untriaged(String title) {
		ticketRepository.saveAndFlush(Ticket.open(agent, title, "body"));
	}

	private String bearer() {
		return "Bearer " + jwtService
				.issueAccessToken(agent.getId(), agent.getEmail(), agent.getRole().name()).token();
	}

	private User createUser(String email, UserRole role) {
		User u = User.create(email,
				"{bcrypt}$2a$04$0000000000000000000000000000000000000000000000000000", "Agent");
		try {
			Field field = User.class.getDeclaredField("role");
			field.setAccessible(true);
			field.set(u, role);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		return userRepository.saveAndFlush(u);
	}
}
