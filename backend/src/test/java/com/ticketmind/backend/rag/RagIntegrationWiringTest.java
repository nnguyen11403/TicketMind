package com.ticketmind.backend.rag;

import com.ticketmind.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the integration is actually wired when switched on. The rest of the
 * suite runs with {@code app.rag.enabled=false}, so without this test a broken
 * listener or a missing executor bean would only surface in production.
 */
@SpringBootTest(properties = {
		"app.rag.enabled=true",
		"app.rag.base-url=http://rag.test",
		"app.rag.internal-key=test-internal-key"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RagIntegrationWiringTest {

	@Autowired private ApplicationContext context;

	@Test
	void theListenerAndItsDedicatedExecutorAreRegisteredWhenEnabled() {
		assertThat(context.getBeansOfType(RagTicketListener.class)).hasSize(1);
		assertThat(context.getBean("ragTaskExecutor", Executor.class)).isNotNull();
	}

	@Test
	void theRestClientCarriesTheConfiguredInternalKey() {
		// Bean creation alone proves base-url and the timeouts parsed; the
		// header itself is asserted against the wire in RagClientTest.
		assertThat(context.getBean("ragRestClient")).isNotNull();
		assertThat(context.getBean(RagProperties.class).internalKey()).isEqualTo("test-internal-key");
	}
}
