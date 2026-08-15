package com.ticketmind.backend.security.rls;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled  when false no role switch and no session variables are set,
 *                 and every policy therefore sees an empty {@code app.user_role}
 *                 and denies. Only useful for diagnosing a suspected policy bug
 *                 against a database where RLS was never applied.
 * @param appRole  the Postgres role every runtime connection switches into.
 *                 Must not own the tables and must not have BYPASSRLS.
 */
@ConfigurationProperties(prefix = "app.rls")
public record RlsProperties(boolean enabled, String appRole) {

	public RlsProperties {
		if (enabled && (appRole == null || !appRole.matches("[a-z_][a-z0-9_]{0,62}"))) {
			// SET ROLE cannot take a bind parameter, so this value is
			// interpolated into SQL. It comes from configuration rather than
			// from a request, but validating it here is what keeps that true.
			throw new IllegalStateException(
					"app.rls.app-role must be a plain lowercase identifier, got: " + appRole);
		}
	}
}
