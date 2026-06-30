package com.ticketmind.backend.auth;

import com.ticketmind.backend.auth.dto.LoginRequest;
import com.ticketmind.backend.auth.dto.RegisterRequest;
import com.ticketmind.backend.auth.dto.TokenResponse;
import com.ticketmind.backend.auth.dto.UserResponse;
import com.ticketmind.backend.common.exception.RateLimitExceededException;
import com.ticketmind.backend.security.jwt.JwtPrincipal;
import com.ticketmind.backend.user.User;
import com.ticketmind.backend.user.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

	private final AuthService authService;
	private final RateLimiterService rateLimiter;
	private final RefreshCookieFactory cookieFactory;
	private final UserRepository userRepository;

	public AuthController(
			AuthService authService,
			RateLimiterService rateLimiter,
			RefreshCookieFactory cookieFactory,
			UserRepository userRepository) {
		this.authService = authService;
		this.rateLimiter = rateLimiter;
		this.cookieFactory = cookieFactory;
		this.userRepository = userRepository;
	}

	@PostMapping("/register")
	public ResponseEntity<TokenResponse> register(
			@Valid @RequestBody RegisterRequest request,
			HttpServletRequest http) {
		enforceLimit(RateLimiterService.BucketKind.REGISTER, http);
		var result = authService.register(request, userAgent(http), ipAddress(http));
		return withRefreshCookie(result);
	}

	@PostMapping("/login")
	public ResponseEntity<TokenResponse> login(
			@Valid @RequestBody LoginRequest request,
			HttpServletRequest http) {
		enforceLimit(RateLimiterService.BucketKind.LOGIN, http);
		var result = authService.login(request, userAgent(http), ipAddress(http));
		return withRefreshCookie(result);
	}

	@PostMapping("/refresh")
	public ResponseEntity<TokenResponse> refresh(HttpServletRequest http) {
		enforceLimit(RateLimiterService.BucketKind.REFRESH, http);
		String refreshToken = extractRefreshCookie(http);
		var result = authService.refresh(refreshToken, userAgent(http), ipAddress(http));
		return withRefreshCookie(result);
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest http) {
		String refreshToken = extractRefreshCookie(http);
		authService.logout(refreshToken);
		ResponseCookie cleared = cookieFactory.clear();
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cleared.toString())
				.build();
	}

	@GetMapping("/me")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<UserResponse> me(@AuthenticationPrincipal JwtPrincipal principal) {
		User user = userRepository.findById(principal.id()).orElseThrow();
		return ResponseEntity.ok(UserResponse.from(user));
	}

	private ResponseEntity<TokenResponse> withRefreshCookie(AuthService.AuthResult result) {
		ResponseCookie cookie = cookieFactory.build(result.refreshToken(), result.refreshExpiresAt());
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookie.toString())
				.body(result.body());
	}

	private void enforceLimit(RateLimiterService.BucketKind kind, HttpServletRequest http) {
		String key = ipAddress(http);
		var outcome = rateLimiter.tryConsume(kind, key);
		if (!outcome.allowed()) {
			throw new RateLimitExceededException(outcome.retryAfterSeconds());
		}
	}

	private String extractRefreshCookie(HttpServletRequest http) {
		Cookie[] cookies = http.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie c : cookies) {
			if (RefreshCookieFactory.COOKIE_NAME.equals(c.getName())) {
				return c.getValue();
			}
		}
		return null;
	}

	private String userAgent(HttpServletRequest http) {
		String ua = http.getHeader(HttpHeaders.USER_AGENT);
		if (ua == null) {
			return null;
		}
		return ua.length() > 512 ? ua.substring(0, 512) : ua;
	}

	private String ipAddress(HttpServletRequest http) {
		String forwarded = http.getHeader("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			int comma = forwarded.indexOf(',');
			String first = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
			if (!first.isEmpty()) {
				return first;
			}
		}
		return http.getRemoteAddr();
	}
}
