package com.ticketmind.backend.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmind.backend.TestcontainersConfiguration;
import com.ticketmind.backend.security.AuthProperties;
import com.ticketmind.backend.security.jwt.JwtService;
import com.ticketmind.backend.ticket.TicketHistoryRepository;
import com.ticketmind.backend.ticket.TicketRepository;
import com.ticketmind.backend.user.dto.ChangeRoleRequest;
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

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the path from "fresh deployment with only USERs" to a working staff
 * account. Before this existed the only way to create an AGENT was an UPDATE
 * against Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UserRoleManagementTest {

	@Autowired private MockMvc mockMvc;
	@Autowired private UserRepository userRepository;
	@Autowired private TicketRepository ticketRepository;
	@Autowired private TicketHistoryRepository historyRepository;
	@Autowired private RefreshTokenRepository refreshTokenRepository;
	@Autowired private JwtService jwtService;
	@Autowired private UserService userService;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private User alice;  // USER
	private User admin;  // ADMIN

	@BeforeEach
	void resetState() {
		historyRepository.deleteAllInBatch();
		ticketRepository.deleteAllInBatch();
		refreshTokenRepository.deleteAllInBatch();
		userRepository.deleteAllInBatch();
		alice = createUser("alice@example.com", "Alice", UserRole.USER);
		admin = createUser("admin@example.com", "Admin", UserRole.ADMIN);
	}

	@Test
	void anAdminCanPromoteAUserToAgent() throws Exception {
		mockMvc.perform(patch("/users/" + alice.getId() + "/role")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(admin))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeRoleRequest(UserRole.AGENT))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("AGENT"))
				.andExpect(jsonPath("$.email").value("alice@example.com"));

		assertThat(userRepository.findById(alice.getId()).orElseThrow().getRole())
				.isEqualTo(UserRole.AGENT);
	}

	@Test
	void aNonAdminCannotChangeRoles() throws Exception {
		// The whole point of the endpoint is that privilege cannot be
		// self-granted; a USER promoting itself would defeat it entirely.
		mockMvc.perform(patch("/users/" + alice.getId() + "/role")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(alice))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeRoleRequest(UserRole.ADMIN))))
				.andExpect(status().isForbidden());

		assertThat(userRepository.findById(alice.getId()).orElseThrow().getRole())
				.isEqualTo(UserRole.USER);
	}

	@Test
	void theLastAdminCannotBeDemoted() throws Exception {
		mockMvc.perform(patch("/users/" + admin.getId() + "/role")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(admin))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeRoleRequest(UserRole.USER))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("last_admin"));

		assertThat(userRepository.findById(admin.getId()).orElseThrow().getRole())
				.isEqualTo(UserRole.ADMIN);
	}

	@Test
	void anAdminCanBeDemotedOnceAnotherAdminExists() throws Exception {
		User second = createUser("admin2@example.com", "Admin Two", UserRole.ADMIN);

		mockMvc.perform(patch("/users/" + admin.getId() + "/role")
						.header(HttpHeaders.AUTHORIZATION, bearerFor(second))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeRoleRequest(UserRole.AGENT))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("AGENT"));
	}

	@Test
	void staffCanListTheRosterButSubmittersCannot() throws Exception {
		createUser("agent@example.com", "Agent", UserRole.AGENT);

		mockMvc.perform(get("/users").header(HttpHeaders.AUTHORIZATION, bearerFor(admin)))
				.andExpect(status().isOk())
				// Only AGENT and ADMIN. The roster exists for assignment, so
				// listing every submitter would be useless and leaky.
				.andExpect(jsonPath("$.length()").value(2));

		mockMvc.perform(get("/users").header(HttpHeaders.AUTHORIZATION, bearerFor(alice)))
				.andExpect(status().isForbidden());
	}

	@Test
	void meReturnsTheCallersOwnAccount() throws Exception {
		mockMvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, bearerFor(alice)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("alice@example.com"))
				.andExpect(jsonPath("$.role").value("USER"));
	}

	@Test
	void theBootstrapPromotesTheConfiguredAccountOnlyWhileNoAdminExists() {
		// With an ADMIN present the bootstrap must be inert, otherwise a
		// deliberate demotion would silently undo itself on every restart.
		AdminBootstrap bootstrap = new AdminBootstrap();
		bootstrap.promote(userRepository, propertiesWithBootstrap("alice@example.com"));
		assertThat(userRepository.findById(alice.getId()).orElseThrow().getRole())
				.isEqualTo(UserRole.USER);

		// Remove the only admin and it takes effect.
		userRepository.delete(admin);
		userRepository.flush();
		bootstrap.promote(userRepository, propertiesWithBootstrap("alice@example.com"));
		assertThat(userRepository.findById(alice.getId()).orElseThrow().getRole())
				.isEqualTo(UserRole.ADMIN);
	}

	@Test
	void theBootstrapIsANoOpWhenUnsetOrPointingAtNobody() {
		userRepository.delete(admin);
		userRepository.flush();
		AdminBootstrap bootstrap = new AdminBootstrap();

		bootstrap.promote(userRepository, propertiesWithBootstrap(null));
		bootstrap.promote(userRepository, propertiesWithBootstrap("   "));
		bootstrap.promote(userRepository, propertiesWithBootstrap("nobody@example.com"));

		assertThat(userRepository.existsByRole(UserRole.ADMIN)).isFalse();
	}

	private AuthProperties propertiesWithBootstrap(String email) {
		return new AuthProperties(4, 12, 5, Duration.ofMinutes(15), email);
	}

	private String bearerFor(User user) {
		return "Bearer " + jwtService
				.issueAccessToken(user.getId(), user.getEmail(), user.getRole().name()).token();
	}

	private User createUser(String email, String displayName, UserRole role) {
		User u = User.create(email,
				"{bcrypt}$2a$04$0000000000000000000000000000000000000000000000000000", displayName);
		if (role != UserRole.USER) {
			forceRole(u, role);
		}
		return userRepository.saveAndFlush(u);
	}

	private void forceRole(User u, UserRole role) {
		try {
			Field field = User.class.getDeclaredField("role");
			field.setAccessible(true);
			field.set(u, role);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
