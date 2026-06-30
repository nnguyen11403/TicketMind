package com.ticketmind.backend.db;

import com.ticketmind.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the pgvector column in ticket_embeddings:
 * - accepts 1024-dimensional vectors
 * - rejects wrong dimensions
 * - supports cosine-similarity ranking via the HNSW index
 * - enforces (ticket_id, chunk_index, embedding_model) uniqueness
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PgvectorIntegrationTest {

	private static final int VECTOR_DIMENSION = 1024;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID userId;

	@BeforeEach
	void seed() {
		jdbc.update("DELETE FROM ticket_embeddings");
		jdbc.update("DELETE FROM ticket_history");
		jdbc.update("DELETE FROM tickets");
		jdbc.update("DELETE FROM refresh_tokens");
		jdbc.update("DELETE FROM users");
		userId = jdbc.queryForObject(
				"INSERT INTO users(email, password_hash, display_name) " +
						"VALUES(?, ?, ?) RETURNING id",
				UUID.class, "u@example.com", "$2a$10$dummy", "U");
	}

	@Test
	void canStoreAndRetrieveA1024DimVector() {
		UUID ticketId = insertTicket("Wifi down", "Office wifi unreachable from floor 3.");
		insertEmbedding(ticketId, 0, unitVector(0));

		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM ticket_embeddings WHERE ticket_id = ?",
				Integer.class, ticketId);
		assertThat(count).isEqualTo(1);
	}

	@Test
	void rejectsVectorWithWrongDimension() {
		UUID ticketId = insertTicket("Wifi down", "...");
		String tooShort = "[" + IntStream.range(0, 8).mapToObj(i -> "0.1").collect(Collectors.joining(",")) + "]";

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO ticket_embeddings(ticket_id, embedding, embedding_model, chunk_index, chunk_text) " +
						"VALUES(?, ?::vector, ?, ?, ?)",
				ticketId, tooShort, "voyage-3", 0, "x"))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void duplicateChunkPerModelIsRejected() {
		UUID ticketId = insertTicket("Wifi down", "...");
		insertEmbedding(ticketId, 0, unitVector(0));
		assertThatThrownBy(() -> insertEmbedding(ticketId, 0, unitVector(1)))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void cosineSimilarityRanksNearestNeighbourFirst() {
		UUID t1 = insertTicket("A", "first ticket");
		UUID t2 = insertTicket("B", "second ticket");
		UUID t3 = insertTicket("C", "third ticket");

		// Each ticket gets a unit vector along a distinct axis.
		insertEmbedding(t1, 0, unitVector(0));
		insertEmbedding(t2, 0, unitVector(1));
		insertEmbedding(t3, 0, unitVector(2));

		// Query vector aligned with axis 1 -> t2 must be nearest.
		String queryVec = unitVector(1);
		List<UUID> ranked = jdbc.queryForList(
				"SELECT ticket_id FROM ticket_embeddings " +
						"ORDER BY embedding <=> ?::vector LIMIT 3",
				UUID.class, queryVec);

		assertThat(ranked).hasSize(3);
		assertThat(ranked.get(0)).isEqualTo(t2);
	}

	@Test
	void embeddingsCascadeWhenTicketDeleted() {
		UUID ticketId = insertTicket("A", "ticket");
		insertEmbedding(ticketId, 0, unitVector(0));

		jdbc.update("DELETE FROM tickets WHERE id = ?", ticketId);

		Integer remaining = jdbc.queryForObject(
				"SELECT COUNT(*) FROM ticket_embeddings WHERE ticket_id = ?",
				Integer.class, ticketId);
		assertThat(remaining).isZero();
	}

	// ---------- helpers ----------

	private UUID insertTicket(String title, String description) {
		return jdbc.queryForObject(
				"INSERT INTO tickets(submitter_id, title, description) " +
						"VALUES(?, ?, ?) RETURNING id",
				UUID.class, userId, title, description);
	}

	private void insertEmbedding(UUID ticketId, int chunkIndex, String vectorLiteral) {
		jdbc.update(
				"INSERT INTO ticket_embeddings(ticket_id, embedding, embedding_model, chunk_index, chunk_text) " +
						"VALUES(?, ?::vector, ?, ?, ?)",
				ticketId, vectorLiteral, "voyage-3", chunkIndex, "chunk-" + chunkIndex);
	}

	/** Build a unit vector with 1.0 at the given axis index and 0.0 elsewhere. */
	private static String unitVector(int axis) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < VECTOR_DIMENSION; i++) {
			if (i > 0) sb.append(',');
			sb.append(i == axis ? "1" : "0");
		}
		return sb.append(']').toString();
	}
}
