package com.ticketmind.backend.security.rls;

import com.ticketmind.backend.security.jwt.JwtPrincipal;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Stamps the caller's identity onto every connection so Postgres row-level
 * security policies can see it.
 *
 * <p>On checkout the connection switches into the unprivileged application role
 * and sets {@code app.user_id} / {@code app.user_role} from the authenticated
 * principal. On return both are cleared. The reset is not optional: HikariCP
 * hands the same physical connection to the next request, and a leftover
 * {@code app.user_role=ADMIN} would hand that request an administrator's view.
 *
 * <p>Work with no authenticated principal — login, token refresh, the async
 * triage write-back, the admin bootstrap — runs as {@code SYSTEM}. Those paths
 * are pre-authentication or out-of-band by nature: the login lookup has to read
 * a user row before it knows who is asking. {@code SYSTEM} is therefore a
 * documented hole in the policies rather than an accident, and it is only
 * reachable from code that runs off a request thread or before authentication.
 *
 * <p>The session variables are set through {@code set_config()} rather than
 * {@code SET}, because {@code SET} takes no bind parameters and this carries a
 * value derived from a token.
 */
public class RlsDataSource extends DelegatingDataSource {

	static final String SYSTEM_ROLE = "SYSTEM";

	private static final String APPLY_SQL =
			"SELECT set_config('app.user_id', ?, false), set_config('app.user_role', ?, false)";
	private static final String CLEAR_SQL =
			"SELECT set_config('app.user_id', '', false), set_config('app.user_role', '', false)";

	private final String appRole;

	/**
	 * Suspended for the duration of the Flyway migration, which has to run as
	 * the owning role to create the policies in the first place. Migration
	 * completes on a single thread before the application serves any request,
	 * so a plain volatile is enough sequencing.
	 */
	private volatile boolean active = true;

	public RlsDataSource(DataSource target, String appRole) {
		super(target);
		this.appRole = appRole;
	}

	public void runWithoutRoleSwitch(Runnable action) {
		active = false;
		try {
			action.run();
		} finally {
			active = true;
		}
	}

	@Override
	public Connection getConnection() throws SQLException {
		return wrap(super.getConnection());
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		return wrap(super.getConnection(username, password));
	}

	private Connection wrap(Connection connection) throws SQLException {
		if (!active) {
			return connection;
		}
		try {
			applyContext(connection);
		} catch (SQLException ex) {
			connection.close();
			throw ex;
		}
		return (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(),
				new Class<?>[] {Connection.class},
				new ResettingHandler(connection));
	}

	private void applyContext(Connection connection) throws SQLException {
		try (var statement = connection.createStatement()) {
			statement.execute("SET ROLE " + appRole);
		}
		try (PreparedStatement statement = connection.prepareStatement(APPLY_SQL)) {
			statement.setString(1, currentUserId());
			statement.setString(2, currentUserRole());
			statement.execute();
		}
	}

	private static String currentUserId() {
		JwtPrincipal principal = currentPrincipal();
		return principal == null ? "" : principal.id().toString();
	}

	private static String currentUserRole() {
		JwtPrincipal principal = currentPrincipal();
		return principal == null ? SYSTEM_ROLE : principal.role().name();
	}

	private static JwtPrincipal currentPrincipal() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		return authentication.getPrincipal() instanceof JwtPrincipal principal ? principal : null;
	}

	/**
	 * Clears the session state before the connection goes back to the pool.
	 * {@code RESET ROLE} returns to the session user, which in a correct
	 * deployment is the same unprivileged login role — the switch is defence in
	 * depth for environments (tests, a misconfigured deploy) where the session
	 * user is more privileged than it should be.
	 */
	private record ResettingHandler(Connection delegate) implements InvocationHandler {

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if ("close".equals(method.getName()) && !delegate.isClosed()) {
				clear();
			}
			try {
				return method.invoke(delegate, args);
			} catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		}

		private void clear() throws SQLException {
			try (PreparedStatement statement = delegate.prepareStatement(CLEAR_SQL)) {
				statement.execute();
			}
			try (var statement = delegate.createStatement()) {
				statement.execute("RESET ROLE");
			}
		}
	}
}
