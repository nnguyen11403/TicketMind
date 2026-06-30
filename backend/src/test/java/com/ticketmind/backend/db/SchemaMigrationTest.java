package com.ticketmind.backend.db;

import com.ticketmind.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalog-level smoke tests: after Flyway runs, every table, index, and
 * extension the application relies on is in place.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SchemaMigrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void allExpectedTablesExist() {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables " +
						"WHERE table_schema = 'public' ORDER BY table_name",
				String.class);
		assertThat(tables).contains(
				"flyway_schema_history",
				"refresh_tokens",
				"ticket_embeddings",
				"ticket_history",
				"tickets",
				"users");
	}

	@Test
	void pgvectorAndCitextExtensionsAreInstalled() {
		List<String> extensions = jdbc.queryForList(
				"SELECT extname FROM pg_extension ORDER BY extname",
				String.class);
		assertThat(extensions).contains("vector", "citext");
	}

	@Test
	void usersEmailIsCitextAndUnique() {
		String dataType = jdbc.queryForObject(
				"SELECT data_type FROM information_schema.columns " +
						"WHERE table_name = 'users' AND column_name = 'email'",
				String.class);
		assertThat(dataType).isEqualTo("USER-DEFINED"); // citext shows up here

		Integer uniqueCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM pg_indexes " +
						"WHERE tablename = 'users' AND indexdef ILIKE '%UNIQUE%email%'",
				Integer.class);
		assertThat(uniqueCount).isGreaterThanOrEqualTo(1);
	}

	@Test
	void ticketEmbeddingsHasHnswCosineIndex() {
		Integer hnswCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM pg_indexes " +
						"WHERE tablename = 'ticket_embeddings' AND indexdef ILIKE '%hnsw%vector_cosine_ops%'",
				Integer.class);
		assertThat(hnswCount).isEqualTo(1);
	}

	@Test
	void updatedAtTriggerIsAttachedToUsersAndTickets() {
		List<String> triggers = jdbc.queryForList(
				"SELECT event_object_table || '.' || trigger_name " +
						"FROM information_schema.triggers " +
						"WHERE trigger_name LIKE '%updated_at%'",
				String.class);
		assertThat(triggers).containsExactlyInAnyOrder(
				"users.users_updated_at",
				"tickets.tickets_updated_at");
	}
}
