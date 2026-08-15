package com.ticketmind.backend.common.crypto;

import com.ticketmind.backend.TestcontainersConfiguration;
import com.ticketmind.backend.ticket.Ticket;
import com.ticketmind.backend.ticket.TicketHistoryRepository;
import com.ticketmind.backend.ticket.TicketRepository;
import com.ticketmind.backend.user.RefreshTokenRepository;
import com.ticketmind.backend.user.User;
import com.ticketmind.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEncryptionTest {

	@Nested
	class Cipher {

		private final FieldCipher cipher = new FieldCipher(
				new CryptoProperties(Base64.getEncoder().encodeToString(
						"unit-test-key-that-is-32-bytes!!".getBytes())));

		@Test
		void roundTrips() {
			assertThat(cipher.decrypt(cipher.encrypt("hello"))).isEqualTo("hello");
		}

		@Test
		void nullsPassThrough() {
			assertThat(cipher.encrypt(null)).isNull();
			assertThat(cipher.decrypt(null)).isNull();
		}

		/**
		 * A random IV per value is the reason for this. Without it two tickets
		 * reporting the same problem would be visibly identical in the dump,
		 * which leaks more than it looks like it does.
		 */
		@Test
		void sameInputEncryptsDifferentlyEveryTime() {
			assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
		}

		@Test
		void legacyPlaintextReadsThrough() {
			assertThat(cipher.decrypt("written before the converter existed"))
					.isEqualTo("written before the converter existed");
		}

		/** GCM authenticates, so an edit in the database is loud rather than silent. */
		@Test
		void tamperedCiphertextIsRejected() {
			String encrypted = cipher.encrypt("original");
			String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";
			assertThatThrownBy(() -> cipher.decrypt(tampered))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("decryption failed");
		}

		@Test
		void shortKeyIsRejectedAtConstruction() {
			CryptoProperties tooShort = new CryptoProperties(
					Base64.getEncoder().encodeToString("only-16-bytes!!!".getBytes()));
			assertThatThrownBy(() -> new FieldCipher(tooShort))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("32 bytes");
		}

		@Test
		void missingKeyIsRejectedAtConstruction() {
			assertThatThrownBy(() -> new FieldCipher(new CryptoProperties("")))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("APP_ENCRYPTION_KEY");
		}
	}

	@Nested
	@SpringBootTest
	@ActiveProfiles("test")
	@Import(TestcontainersConfiguration.class)
	class AtRest {

		@Autowired private DataSource dataSource;
		@Autowired private UserRepository userRepository;
		@Autowired private TicketRepository ticketRepository;
		@Autowired private TicketHistoryRepository historyRepository;
		@Autowired private RefreshTokenRepository refreshTokenRepository;

		private JdbcTemplate jdbc;
		private User submitter;

		@BeforeEach
		void seed() {
			jdbc = new JdbcTemplate(dataSource);
			historyRepository.deleteAllInBatch();
			ticketRepository.deleteAllInBatch();
			refreshTokenRepository.deleteAllInBatch();
			userRepository.deleteAllInBatch();
			submitter = userRepository.save(
					User.create("submitter@example.com", "{bcrypt}$2a$04$x", "Submitter"));
		}

		/**
		 * Reads the column with raw SQL rather than through the entity, because
		 * the converter is transparent — a JPA round-trip would return the
		 * plaintext whether or not anything was ever encrypted.
		 */
		@Test
		void ticketTextIsCiphertextInTheDatabase() {
			Ticket saved = ticketRepository.save(
					Ticket.open(submitter, "Payment declined", "Card ending 4242 was refused"));

			String storedTitle = jdbc.queryForObject(
					"SELECT title FROM tickets WHERE id = ?", String.class, saved.getId());
			String storedBody = jdbc.queryForObject(
					"SELECT description FROM tickets WHERE id = ?", String.class, saved.getId());

			assertThat(storedTitle).startsWith("v1:").doesNotContain("Payment declined");
			assertThat(storedBody).startsWith("v1:").doesNotContain("4242");

			assertThat(ticketRepository.findById(saved.getId()).orElseThrow().getDescription())
					.isEqualTo("Card ending 4242 was refused");
		}
	}
}
