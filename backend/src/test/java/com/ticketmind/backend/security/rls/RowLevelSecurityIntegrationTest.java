package com.ticketmind.backend.security.rls;

import com.ticketmind.backend.TestcontainersConfiguration;
import com.ticketmind.backend.security.jwt.JwtPrincipal;
import com.ticketmind.backend.ticket.TicketHistoryRepository;
import com.ticketmind.backend.ticket.TicketRepository;
import com.ticketmind.backend.user.RefreshTokenRepository;
import com.ticketmind.backend.user.User;
import com.ticketmind.backend.user.UserRepository;
import com.ticketmind.backend.user.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the isolation is enforced by Postgres, not by
 * {@code TicketService.assertCanRead}.
 *
 * <p>Every assertion here goes straight to SQL through {@link JdbcTemplate},
 * skipping the service layer entirely. That is the whole point: the Java checks
 * are already covered by {@code TicketControllerIntegrationTest}, and a test
 * that went through them again would pass just as happily with every policy
 * dropped.
 *
 * <p>Testcontainers connects as the superuser it provisions, which would be
 * exempt from RLS — {@code RlsDataSource} switching into {@code ticketmind_app}
 * on checkout is what makes these queries subject to the policies at all.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RowLevelSecurityIntegrationTest {

	@Autowired private DataSource dataSource;
	@Autowired private UserRepository userRepository;
	@Autowired private TicketRepository ticketRepository;
	@Autowired private TicketHistoryRepository historyRepository;
	@Autowired private RefreshTokenRepository refreshTokenRepository;

	private JdbcTemplate jdbc;
	private User alice;
	private User bob;
	private User agent;
	private UUID aliceTicket;
	private UUID bobTicket;

	@BeforeEach
	void seed() {
		jdbc = new JdbcTemplate(dataSource);
		SecurityContextHolder.clearContext();
		historyRepository.deleteAllInBatch();
		ticketRepository.deleteAllInBatch();
		refreshTokenRepository.deleteAllInBatch();
		userRepository.deleteAllInBatch();

		alice = userRepository.save(User.create("alice@example.com", "{bcrypt}$2a$04$x", "Alice"));
		bob = userRepository.save(User.create("bob@example.com", "{bcrypt}$2a$04$x", "Bob"));
		agent = userRepository.save(User.create("agent@example.com", "{bcrypt}$2a$04$x", "Agent"));
		agent.changeRole(UserRole.AGENT);
		userRepository.save(agent);

		// Seeded with no principal, so these inserts run as SYSTEM.
		aliceTicket = insertTicket(alice, "Alice ticket");
		bobTicket = insertTicket(bob, "Bob ticket");
	}

	@AfterEach
	void clearPrincipal() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void submitterSelectsOnlyTheirOwnTickets() {
		authenticateAs(alice, UserRole.USER);
		assertThat(ticketIds()).containsExactly(aliceTicket);

		authenticateAs(bob, UserRole.USER);
		assertThat(ticketIds()).containsExactly(bobTicket);
	}

	@Test
	void staffSelectsEveryTicket() {
		authenticateAs(agent, UserRole.AGENT);
		assertThat(ticketIds()).containsExactlyInAnyOrder(aliceTicket, bobTicket);
	}

	/**
	 * The interesting case for an UPDATE policy: the row is not merely
	 * unwritable, it is invisible, so the statement reports zero rows affected
	 * rather than raising. Anything relying on an exception here would be
	 * relying on the wrong signal.
	 */
	@Test
	void submitterCannotUpdateAnotherSubmittersTicket() {
		authenticateAs(alice, UserRole.USER);
		int updated = jdbc.update("UPDATE tickets SET status = 'CLOSED' WHERE id = ?", bobTicket);
		assertThat(updated).isZero();

		authenticateAs(agent, UserRole.AGENT);
		String status = jdbc.queryForObject(
				"SELECT status FROM tickets WHERE id = ?", String.class, bobTicket);
		assertThat(status).isEqualTo("OPEN");
	}

	@Test
	void submitterCannotInsertATicketInSomeoneElsesName() {
		authenticateAs(alice, UserRole.USER);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO tickets (id, submitter_id, title, description) VALUES (?, ?, ?, ?)",
				UUID.randomUUID(), bob.getId(), "forged", "forged"))
				// Spring wraps this in a DataIntegrityViolationException whose
				// own message does not carry the Postgres text.
				.hasStackTraceContaining("violates row-level security policy");
	}

	@Test
	void submitterCannotReadAnotherSubmittersHistory() {
		insertHistory(aliceTicket, "alice comment");
		insertHistory(bobTicket, "bob comment");

		authenticateAs(alice, UserRole.USER);
		assertThat(jdbc.queryForList("SELECT ticket_id FROM ticket_history", UUID.class))
				.containsExactly(aliceTicket);
	}

	/**
	 * The escalation this is really about. Role lives on the user row, so a
	 * writeable {@code users} row is a path to ADMIN.
	 */
	@Test
	void submitterCannotPromoteThemselves() {
		authenticateAs(alice, UserRole.USER);
		assertThat(jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", alice.getId()))
				.isZero();

		SecurityContextHolder.clearContext();
		assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, alice.getId()))
				.isEqualTo("USER");
	}

	@Test
	void adminCanChangeRoles() {
		User admin = userRepository.save(User.create("admin@example.com", "{bcrypt}$2a$04$x", "Admin"));
		admin.changeRole(UserRole.ADMIN);
		userRepository.save(admin);

		authenticateAs(admin, UserRole.ADMIN);
		assertThat(jdbc.update("UPDATE users SET role = 'AGENT' WHERE id = ?", bob.getId()))
				.isEqualTo(1);
	}

	@Test
	void submitterSeesOnlyThemselvesAndTheirTicketsCounterparties() {
		jdbc.update("UPDATE tickets SET assignee_id = ? WHERE id = ?", agent.getId(), aliceTicket);

		authenticateAs(alice, UserRole.USER);
		assertThat(jdbc.queryForList("SELECT email FROM users", String.class))
				.containsExactlyInAnyOrder("alice@example.com", "agent@example.com");
	}

	@Test
	void submitterCannotReadAnotherUsersRefreshTokens() {
		insertRefreshToken(alice, "alice-hash");
		insertRefreshToken(bob, "bob-hash");

		authenticateAs(alice, UserRole.USER);
		assertThat(jdbc.queryForList("SELECT token_hash FROM refresh_tokens", String.class))
				.containsExactly("alice-hash");
	}

	/** Staff are not system: an agent has no business reading session material. */
	@Test
	void agentCannotReadRefreshTokens() {
		insertRefreshToken(alice, "alice-hash");

		authenticateAs(agent, UserRole.AGENT);
		assertThat(jdbc.queryForList("SELECT token_hash FROM refresh_tokens", String.class)).isEmpty();
	}

	@Test
	void nobodyBelowSystemCanDeleteTickets() {
		authenticateAs(agent, UserRole.AGENT);
		assertThat(jdbc.update("DELETE FROM tickets WHERE id = ?", aliceTicket)).isZero();
	}

	private List<UUID> ticketIds() {
		return jdbc.queryForList("SELECT id FROM tickets ORDER BY created_at", UUID.class);
	}

	private UUID insertTicket(User submitter, String title) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO tickets (id, submitter_id, title, description) VALUES (?, ?, ?, ?)",
				id, submitter.getId(), title, "body");
		return id;
	}

	private void insertHistory(UUID ticketId, String body) {
		jdbc.update("INSERT INTO ticket_history (id, ticket_id, event_type, payload) VALUES (?, ?, ?, ?)",
				UUID.randomUUID(), ticketId, "COMMENT", "{\"body\":\"" + body + "\"}");
	}

	private void insertRefreshToken(User user, String hash) {
		jdbc.update("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at) "
						+ "VALUES (?, ?, ?, NOW() + INTERVAL '1 day')",
				UUID.randomUUID(), user.getId(), hash);
	}

	private void authenticateAs(User user, UserRole role) {
		JwtPrincipal principal = new JwtPrincipal(user.getId(), user.getEmail(), role);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null,
						List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}
}
