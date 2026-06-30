package com.ticketmind.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the security-sensitive defaults declared in application.yml.
 * If anyone weakens these (e.g. exposing actuator/env, enabling stack traces in
 * error responses, switching JPA to update mode), this test fails fast.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SecurityHeadersConfigTest {

	@Autowired
	private Environment env;

	@Test
	void jpaUsesValidateDdlMode() {
		assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
	}

	@Test
	void openInViewIsDisabled() {
		assertThat(env.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
	}

	@Test
	void errorResponsesAreSanitized() {
		assertThat(env.getProperty("server.error.include-message")).isEqualTo("never");
		assertThat(env.getProperty("server.error.include-binding-errors")).isEqualTo("never");
		assertThat(env.getProperty("server.error.include-stacktrace")).isEqualTo("never");
		assertThat(env.getProperty("server.error.include-exception", Boolean.class)).isFalse();
	}

	@Test
	void actuatorExposesOnlyHealthAndInfo() {
		assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info");
		assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
		assertThat(env.getProperty("management.info.env.enabled", Boolean.class)).isFalse();
	}

	@Test
	void httpHeaderAndBodySizesAreCapped() {
		assertThat(env.getProperty("server.max-http-request-header-size")).isEqualTo("16KB");
		assertThat(env.getProperty("server.tomcat.max-http-form-post-size")).isEqualTo("1MB");
	}

	@Test
	void flywayIsEnabledAsSchemaOwner() {
		assertThat(env.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
		assertThat(env.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
	}
}
