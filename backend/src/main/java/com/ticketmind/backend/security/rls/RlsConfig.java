package com.ticketmind.backend.security.rls;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

@Configuration
public class RlsConfig {

	/**
	 * Wraps the application's {@link DataSource} so every connection carries the
	 * caller's identity into Postgres.
	 *
	 * <p>Declared {@code static} and binding its own configuration through
	 * {@link Binder}: a {@code BeanPostProcessor} is instantiated before the
	 * regular bean graph, so injecting {@code RlsProperties} here would force
	 * the properties infrastructure to initialise early and Spring would log
	 * about it for the rest of the application's life.
	 */
	@Bean
	static BeanPostProcessor rlsDataSourcePostProcessor(Environment environment) {
		RlsProperties properties = Binder.get(environment)
				.bind("app.rls", RlsProperties.class)
				.orElseGet(() -> new RlsProperties(true, "ticketmind_app"));
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
				if (properties.enabled() && bean instanceof DataSource dataSource
						&& !(bean instanceof RlsDataSource)) {
					return new RlsDataSource(dataSource, properties.appRole());
				}
				return bean;
			}
		};
	}

	/**
	 * Runs the migration with the role switch suspended.
	 *
	 * <p>V3 creates the very role every other connection switches into, and it
	 * grants the privileges that role holds. Migrating through it would be
	 * circular on a fresh database and privilege-starved on an existing one, so
	 * Flyway keeps the owning credential it connected with.
	 */
	@Bean
	FlywayMigrationStrategy rlsAwareFlywayMigration(DataSource dataSource) {
		return flyway -> {
			if (dataSource instanceof RlsDataSource rls) {
				rls.runWithoutRoleSwitch(flyway::migrate);
			} else {
				flyway.migrate();
			}
		};
	}
}
