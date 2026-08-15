package com.ticketmind.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses requests that did not reach us over TLS.
 *
 * <p>Spring Security's {@code requiresChannel()} is gone in Spring Security 7,
 * so this is the replacement. It also does the more correct thing for an API:
 * it <em>refuses</em> rather than redirecting. A 301 on a POST loses the body,
 * browsers will not re-send it, and a cross-origin redirect drops the
 * {@code Authorization} header — so a redirect here would turn a
 * misconfiguration into a set of confusing partial failures instead of one
 * clear one. Browser traffic is redirected at the edge, in nginx, where the
 * request is a GET for a document and a redirect is the right answer.
 *
 * <p>{@code request.isSecure()} reflects {@code X-Forwarded-Proto} because
 * {@code server.forward-headers-strategy: framework} installs Spring's
 * {@code ForwardedHeaderFilter} ahead of this one.
 *
 * <p>Actuator is exempt. The container healthcheck probes
 * {@code http://localhost:8080/actuator/health} over the loopback interface,
 * which never involves TLS; without the exemption the backend never reports
 * healthy, and compose gates the RAG service and the frontend on exactly that.
 */
public class HttpsEnforcementFilter extends OncePerRequestFilter {

	private static final String ACTUATOR_PREFIX = "/actuator/";

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// Derived from the request URI rather than getServletPath(): the latter
		// is populated by the container's servlet mapping and comes back empty
		// under MockMvc, which silently turned this exemption off in the very
		// test written to prove it works.
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return path.equals("/actuator") || path.startsWith(ACTUATOR_PREFIX);
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (request.isSecure()) {
			filterChain.doFilter(request, response);
			return;
		}
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		// Same envelope shape as ApiError, written directly because a filter
		// runs outside the range GlobalExceptionHandler covers.
		response.getWriter().write("{\"status\":403,\"code\":\"https_required\"}");
	}
}
