package com.ticketmind.backend.rag;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class RagClientConfig {

	/**
	 * Pinned to HTTP/1.1 on purpose.
	 *
	 * <p>{@code HttpClient.newBuilder()} defaults to {@code HTTP_2}, which makes
	 * the JDK send an HTTP/1.1 upgrade handshake on the first request. uvicorn's
	 * h11 parser — what the RAG service runs on — rejects that with
	 * "Invalid HTTP request received" and the body never reaches FastAPI, which
	 * then answers 422 for a missing request body. Nothing below the socket sees
	 * it: {@code MockRestServiceServer} intercepts at the request-factory level,
	 * so the client tests pass either way. {@code scripts/smoke-test.sh} against
	 * the real stack is what actually guards this.
	 *
	 * <p>Package-private and static so {@code RagClientConfigTest} can assert the
	 * version without standing up a context.
	 */
	static HttpClient buildHttpClient(RagProperties properties) {
		return HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.version(HttpClient.Version.HTTP_1_1)
				.build();
	}

	@Bean
	public RestClient ragRestClient(RagProperties properties) {
		JdkClientHttpRequestFactory requestFactory =
				new JdkClientHttpRequestFactory(buildHttpClient(properties));
		// Read timeout has to cover a Claude round-trip, so it is far longer
		// than the connect timeout — a slow model is normal, an unreachable
		// host is not.
		requestFactory.setReadTimeout(properties.readTimeout());

		RestClient.Builder builder = RestClient.builder()
				.baseUrl(properties.baseUrl().toString())
				.requestFactory(requestFactory);
		if (properties.internalKey() != null && !properties.internalKey().isBlank()) {
			builder.defaultHeader("X-Internal-Key", properties.internalKey());
		}
		return builder.build();
	}
}
