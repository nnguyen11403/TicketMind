package com.ticketmind.backend.rag;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RagClientConfigTest {

	private static RagProperties propertiesFor(String baseUrl) {
		return new RagProperties(
				true, URI.create(baseUrl), "test-internal-key",
				Duration.ofSeconds(2), Duration.ofSeconds(5));
	}

	@Test
	void theHttpClientIsPinnedToHttp11() {
		// The JDK default of HTTP_2 sends an upgrade handshake that uvicorn's
		// h11 parser rejects, so the request body never reaches FastAPI. This
		// assertion is cheap; the behavioural check lives in
		// scripts/smoke-test.sh, because a tolerant test server accepts the
		// upgrade header and proves nothing.
		assertThat(buildClient().version()).isEqualTo(HttpClient.Version.HTTP_1_1);
	}

	private HttpClient buildClient() {
		return RagClientConfig.buildHttpClient(propertiesFor("http://localhost:1"));
	}

	@Test
	void aRealRequestOverASocketCarriesTheSnakeCaseBodyAndTheInternalKey() throws Exception {
		// Unlike RagClientTest, this goes over a real socket through the real
		// request factory and message converters, it would catch a client that
		// silently drops the body or mangles the headers.
		AtomicReference<String> receivedBody = new AtomicReference<>();
		AtomicReference<String> receivedKey = new AtomicReference<>();
		AtomicReference<String> receivedPath = new AtomicReference<>();

		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/triage", exchange -> {
			receivedKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Key"));
			receivedPath.set(exchange.getRequestURI().getPath());
			try (InputStream in = exchange.getRequestBody()) {
				receivedBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			byte[] response = ("""
					{"category":"billing","priority":"HIGH","summary":"s",
					 "suggested_resolution":"r","citations":[]}
					""").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			RestClient restClient = new RagClientConfig().ragRestClient(propertiesFor(baseUrl));
			RagClient client = new RagClient(restClient, propertiesFor(baseUrl));

			UUID ticketId = UUID.randomUUID();
			var result = client.triage(ticketId, "Card charged twice", "billed 40 not 20");

			assertThat(result).isPresent();
			assertThat(result.get().category()).isEqualTo("billing");
			assertThat(receivedPath.get()).isEqualTo("/triage");
			assertThat(receivedKey.get()).isEqualTo("test-internal-key");
			// The Python service binds on snake_case; camelCase would 422.
			assertThat(receivedBody.get())
					.contains("\"ticket_id\":\"" + ticketId + "\"")
					.contains("\"title\":\"Card charged twice\"")
					.contains("\"body\":\"billed 40 not 20\"");
		} finally {
			server.stop(0);
		}
	}
}
