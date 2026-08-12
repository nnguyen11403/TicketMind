package com.ticketmind.backend.user;

import com.ticketmind.backend.security.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promotes one configured account to ADMIN so a fresh deployment has a way in.
 *
 * <p>Registration always creates a USER, and role changes require an ADMIN, so
 * without this the very first staff account can only be made by editing
 * Postgres by hand. This is the bootstrap that breaks that cycle.
 *
 * <p>It runs <em>only</em> while the system has no ADMIN at all. That keeps it
 * idempotent and, more importantly, means it cannot be used to silently
 * re-grant privileges: once a real admin exists, demoting the bootstrap
 * account stays demoted across restarts.
 */
@Configuration
public class AdminBootstrap {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	@Bean
	public ApplicationRunner promoteBootstrapAdmin(
			UserRepository userRepository, AuthProperties authProperties) {
		return args -> promote(userRepository, authProperties);
	}

	@Transactional
	void promote(UserRepository userRepository, AuthProperties authProperties) {
		String email = authProperties.bootstrapAdminEmail();
		if (email == null || email.isBlank()) {
			return;
		}
		if (userRepository.existsByRole(UserRole.ADMIN)) {
			log.debug("bootstrap admin skipped: an ADMIN already exists");
			return;
		}
		userRepository.findByEmailIgnoreCase(email.trim()).ifPresentOrElse(
				user -> {
					user.changeRole(UserRole.ADMIN);
					userRepository.save(user);
					log.warn("promoted {} to ADMIN via app.auth.bootstrap-admin-email", user.getEmail());
				},
				() -> log.warn(
						"app.auth.bootstrap-admin-email is set to {} but no such account exists yet — "
								+ "register it, then restart to receive ADMIN",
						email));
	}
}
