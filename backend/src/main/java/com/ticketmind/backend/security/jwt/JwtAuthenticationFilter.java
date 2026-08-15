package com.ticketmind.backend.security.jwt;

import com.ticketmind.backend.user.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	/**
	 * The credential-issuing endpoints, which are {@code permitAll} and must
	 * behave identically whether or not the caller happens to be carrying a
	 * token. Since row-level security derives the database session identity
	 * from the principal, seating one here would run registration and login
	 * under whoever the stale token names instead of as SYSTEM — and those
	 * paths have to read and write user rows before anyone is authenticated.
	 * {@code /auth/me} is not in the list; it is the one authenticated
	 * endpoint on this controller.
	 */
	private static final List<String> ANONYMOUS_PATHS =
			List.of("/auth/register", "/auth/login", "/auth/refresh", "/auth/logout");

	private final JwtService jwtService;

	public JwtAuthenticationFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// See HttpsEnforcementFilter: getServletPath() depends on the container's
		// servlet mapping and is empty under MockMvc.
		return ANONYMOUS_PATHS.contains(
				request.getRequestURI().substring(request.getContextPath().length()));
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		extractBearer(request)
				.flatMap(jwtService::verify)
				.ifPresent(v -> {
					var auth = new UsernamePasswordAuthenticationToken(
							new JwtPrincipal(v.userId(), v.email(), UserRole.valueOf(v.role())),
							null,
							List.of(new SimpleGrantedAuthority("ROLE_" + v.role())));
					auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
					SecurityContextHolder.getContext().setAuthentication(auth);
				});
		filterChain.doFilter(request, response);
	}

	private Optional<String> extractBearer(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			return Optional.empty();
		}
		String token = header.substring(BEARER_PREFIX.length()).trim();
		return token.isEmpty() ? Optional.empty() : Optional.of(token);
	}
}
