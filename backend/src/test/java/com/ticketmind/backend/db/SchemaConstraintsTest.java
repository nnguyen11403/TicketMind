package com.ticketmind.backend.db;

import com.ticketmind.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for the constraints, triggers, and cascades declared
 * in V1__init.sql. The catalog says they're there; these prove they work.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SchemaConstraintsTest {

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void cleanData() {
		// Order matters: ticket_history -> tickets -> refresh_tokens -> users
		jdbc.update("DELETE FROM ticket_embeddings");
		jdbc.update("DELETE FROM ticket_history");
		jdbc.update("DELETE FROM tickets");
		jdbc.update("DELETE FROM refresh_tokens");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void duplicateEmailIsRejectedCaseInsensitively() {
		insertUser("user@example.com", "Alice");
		assertThatThrownBy(() -> insertUser("USER@example.com", "Mallory"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void invalidRoleIsRejected() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO users(email, password_hash, display_name, role) " +
						"VALUES(?, ?, ?, ?)",
				"bad@example.com", "$2a$dummy", "Bad", "ROOT"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void invalidTicketStatusIsRejected() {
		UUID userId = insertUser("u@example.com", "U");
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO tickets(submitter_id, title, description, status) " +
						"VALUES(?, ?, ?, ?)",
				userId, "Title", "Description", "EXPLODED"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void blankTitleIsRejected() {
		UUID userId = insertUser("u@example.com", "U");
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO tickets(submitter_id, title, description) " +
						"VALUES(?, ?, ?)",
				userId, "   ", "Has body"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingUserWithTicketsIsBlocked() {
		UUID userId = insertUser("u@example.com", "U");
		insertTicket(userId, "T", "D");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", userId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingTicketCascadesToHistory() {
		UUID userId = insertUser("u@example.com", "U");
		UUID ticketId = insertTicket(userId, "T", "D");
		jdbc.update(
				"INSERT INTO ticket_history(ticket_id, actor_id, event_type) " +
						"VALUES(?, ?, ?)",
				ticketId, userId, "CREATED");

		jdbc.update("DELETE FROM tickets WHERE id = ?", ticketId);

		Integer historyCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM ticket_history WHERE ticket_id = ?",
				Integer.class, ticketId);
		assertThat(historyCount).isZero();
	}

	@Test
	void deletingUserCascadesToRefreshTokens() {
		UUID userId = insertUser("u@example.com", "U");
		jdbc.update(
				"INSERT INTO refresh_tokens(user_id, token_hash, expires_at) " +
						"VALUES(?, ?, NOW() + INTERVAL '14 days')",
				userId, "hash-" + UUID.randomUUID());

		jdbc.update("DELETE FROM users WHERE id = ?", userId);

		Integer tokenCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?",
				Integer.class, userId);
		assertThat(tokenCount).isZero();
	}

	@Test
	void updatedAtAdvancesOnUpdate() throws InterruptedException {
		UUID userId = insertUser("u@example.com", "U");
		Timestamp before = jdbc.queryForObject(
				"SELECT updated_at FROM users WHERE id = ?", Timestamp.class, userId);

		Thread.sleep(20); // ensure NOW() ticks past the original timestamp
		jdbc.update("UPDATE users SET display_name = 'U2' WHERE id = ?", userId);

		Timestamp after = jdbc.queryForObject(
				"SELECT updated_at FROM users WHERE id = ?", Timestamp.class, userId);
		assertThat(after).isAfter(before);
	}

	@Test
	void invalidTicketHistoryEventTypeIsRejected() {
		UUID userId = insertUser("u@example.com", "U");
		UUID ticketId = insertTicket(userId, "T", "D");
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO ticket_history(ticket_id, actor_id, event_type) " +
						"VALUES(?, ?, ?)",
				ticketId, userId, "PIZZA_ORDERED"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID insertUser(String email, String name) {
		return jdbc.queryForObject(
				"INSERT INTO users(email, password_hash, display_name) " +
						"VALUES(?, ?, ?) RETURNING id",
				UUID.class, email, "$2a$10$dummyhashdummyhashdummyha", name);
	}

	private UUID insertTicket(UUID submitterId, String title, String description) {
		return jdbc.queryForObject(
				"INSERT INTO tickets(submitter_id, title, description) " +
						"VALUES(?, ?, ?) RETURNING id",
				UUID.class, submitterId, title, description);
	}
}
