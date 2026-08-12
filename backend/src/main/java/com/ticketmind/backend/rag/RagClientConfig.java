package com.ticketmind.backend.rag;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class RagClientConfig {

	@Bean
	public RestClient ragRestClient(RagProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
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
