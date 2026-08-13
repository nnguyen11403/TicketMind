package com.ticketmind.backend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmind.backend.TestcontainersConfiguration;
import com.ticketmind.backend.user.RefreshTokenRepository;
import com.ticketmind.backend.user.UserRepository;
import jakarta.servlet.http.Cookie;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthFlowIntegrationTest {

	@Autowired private MockMvc mockMvc;
	@Autowired private UserRepository userRepository;
	@Autowired private RefreshTokenRepository refreshTokenRepository;
	@Autowired private RateLimiterService rateLimiterService;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void resetState() {
		refreshTokenRepository.deleteAllInBatch();
		userRepository.deleteAllInBatch();
		rateLimiterService.reset();
	}

	@Test
	void registerLoginRefreshFlow() throws Exception {
		String email = "alice@example.com";
		String password = "correct horse battery staple";

		MvcResult registered = mockMvc.perform(post("/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(email, password, "Alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").exists())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.user.email").value(email))
				.andExpect(jsonPath("$.user.role").value("USER"))
				.andExpect(cookie().exists("tm_refresh"))
				.andExpect(cookie().httpOnly("tm_refresh", true))
				.andReturn();

		assertRefreshCookieAttrs(registered);

		String accessToken = json(registered).get("accessToken").asText();
		Cookie refreshCookie = registered.getResponse().getCookie("tm_refresh");
		assertThat(refreshCookie).isNotNull();

		mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email));

		MvcResult refreshed = mockMvc.perform(post("/auth/refresh").cookie(refreshCookie))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").exists())
				.andExpect(cookie().exists("tm_refresh"))
				.andReturn();
		Cookie rotated = refreshed.getResponse().getCookie("tm_refresh");
		assertThat(rotated).isNotNull();
		assertThat(rotated.getValue()).isNotEqualTo(refreshCookie.getValue());

		// Original refresh token now reused, must revoke entire family.
		mockMvc.perform(post("/auth/refresh").cookie(refreshCookie))
				.andExpect(status().isUnauthorized());

		// And the rotated one is now revoked too.
		mockMvc.perform(post("/auth/refresh").cookie(rotated))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void loginWithWrongPasswordReturnsGenericInvalidCredentials() throws Exception {
		mockMvc.perform(post("/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("bob@example.com", "correct horse battery staple", "Bob")))
				.andExpect(status().isOk());

		mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("bob@example.com", "wrong-password-12345")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("invalid_credentials"));
	}

	@Test
	void loginForUnknownEmailReturnsSameGenericError() throws Exception {
		mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("ghost@example.com", "any-old-password-12345")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("invalid_credentials"));
	}

	@Test
	void accountLocksAfterMaxFailedAttempts() throws Exception {
		String email = "carol@example.com";
		mockMvc.perform(post("/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(email, "correct horse battery staple", "Carol")))
				.andExpect(status().isOk());

		for (int i = 0; i < 5; i++) {
			mockMvc.perform(post("/auth/login")
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(email, "wrong-password-12345")))
					.andExpect(status().isUnauthorized());
		}

		mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(email, "correct horse battery staple")))
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.code").value("account_locked"));
	}

	@Test
	void duplicateEmailReturnsConflict() throws Exception {
		mockMvc.perform(post("/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("dave@example.com", "correct horse battery staple", "Dave")))
				.andExpect(status().isOk());

		mockMvc.perform(post("/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("DAVE@example.com", "different-password-67890", "Dave 2")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("email_already_registered"));
	}

	@Test
	void registerRejectsShortPassword() throws Exception {
		mockMvc.perform(post("/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("eve@example.com", "short", "Eve")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("validation_error"));
	}

	@Test
	void protectedRouteRejectsMissingToken() throws Exception {
		mockMvc.perform(get("/auth/me"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void protectedRouteRejectsGarbageToken() throws Exception {
		mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void logoutRevokesRefreshToken() throws Exception {
		MvcResult registered = mockMvc.perform(post("/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("frank@example.com", "correct horse battery staple", "Frank")))
				.andExpect(status().isOk())
				.andReturn();
		Cookie refreshCookie = registered.getResponse().getCookie("tm_refresh");
		assertThat(refreshCookie).isNotNull();

		mockMvc.perform(post("/auth/logout").cookie(refreshCookie))
				.andExpect(status().isNoContent())
				.andExpect(header().string(HttpHeaders.SET_COOKIE,
						org.hamcrest.Matchers.containsString("Max-Age=0")));

		mockMvc.perform(post("/auth/refresh").cookie(refreshCookie))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void passwordsArePersistedAsBcryptHashesNotPlaintext() throws Exception {
		String email = "grace@example.com";
		String password = "correct horse battery staple";
		mockMvc.perform(post("/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(email, password, "Grace")))
				.andExpect(status().isOk());

		var user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
		assertThat(user.getPasswordHash()).isNotEqualTo(password);
		assertThat(user.getPasswordHash()).startsWith("{bcrypt}$2a$");
	}

	@Test
	void refreshTokenIsStoredOnlyAsHash() throws Exception {
		MvcResult registered = mockMvc.perform(post("/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("hank@example.com", "correct horse battery staple", "Hank")))
				.andExpect(status().isOk())
				.andReturn();
		String rawRefresh = registered.getResponse().getCookie("tm_refresh").getValue();

		assertThat(refreshTokenRepository.findAll())
				.noneMatch(t -> t.getTokenHash().equals(rawRefresh));
		assertThat(refreshTokenRepository.findAll())
				.allMatch(t -> t.getTokenHash().length() > 30);
	}

	private void assertRefreshCookieAttrs(MvcResult result) {
		String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).isNotNull();
		assertThat(setCookie).contains("HttpOnly");
		assertThat(setCookie).contains("SameSite=Strict");
		assertThat(setCookie).contains("Path=/auth");
	}

	private String body(String email, String password, String displayName) throws Exception {
		return objectMapper.writeValueAsString(java.util.Map.of(
				"email", email,
				"password", password,
				"displayName", displayName));
	}

	private String body(String email, String password) throws Exception {
		return objectMapper.writeValueAsString(java.util.Map.of(
				"email", email,
				"password", password));
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}
