package com.ticketmind.backend.security;

import com.ticketmind.backend.security.jwt.JwtAuthenticationFilter;
import com.ticketmind.backend.security.jwt.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	private final boolean requireHttps;

	// A property rather than a profile check. Keying this off the active
	// profile made it unreachable from any test — dev and test both disable it,
	// and those are the only profiles the suite runs under — so the first thing
	// to exercise the enabled path was a container that then crash-looped. A
	// property can be flipped on in a test, which is what
	// HttpsEnforcementIntegrationTest does.
	public SecurityConfig(@Value("${app.security.require-https:true}") boolean requireHttps) {
		this.requireHttps = requireHttps;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			JwtService jwtService,
			CorsConfigurationSource corsConfigurationSource) throws Exception {
		if (requireHttps) {
			// Anchored to SecurityContextHolderFilter, which is a registered
			// position in Spring Security's filter order. JwtAuthenticationFilter
			// is not — it is one of ours — so naming it here would not compile
			// into a valid ordering. This lands ahead of it either way: an
			// insecure request is refused before its bearer token is parsed.
			http.addFilterBefore(new HttpsEnforcementFilter(), SecurityContextHolderFilter.class);
		}
		http
				.cors(c -> c.configurationSource(corsConfigurationSource))
				.csrf(csrf -> csrf.disable())
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.logout(logout -> logout.disable())
				.headers(headers -> headers
						.contentTypeOptions(c -> {})
						.frameOptions(f -> f.deny())
						.httpStrictTransportSecurity(hsts -> hsts
								.includeSubDomains(true)
								.maxAgeInSeconds(31536000))
						.referrerPolicy(rp -> rp.policy(
								org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
						// The API returns JSON and nothing else. A CSP this
						// strict costs nothing here and neuters a reflected
						// payload that ever manages to be rendered as a page.
						.contentSecurityPolicy(csp -> csp.policyDirectives(
								"default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
						.permissionsPolicyHeader(pp -> pp.policy(
								"accelerometer=(), camera=(), geolocation=(), gyroscope=(), "
										+ "magnetometer=(), microphone=(), payment=(), usb=()")))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/**").permitAll()
						.requestMatchers("/error").permitAll()
						.requestMatchers(HttpMethod.POST, "/auth/register").permitAll()
						.requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
						.requestMatchers(HttpMethod.POST, "/auth/refresh").permitAll()
						.requestMatchers(HttpMethod.POST, "/auth/logout").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint((req, res, e) ->
								res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
						.accessDeniedHandler((req, res, e) ->
								res.sendError(HttpServletResponse.SC_FORBIDDEN)))
				.addFilterBefore(new JwtAuthenticationFilter(jwtService),
						UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder(AuthProperties authProperties) {
		BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(authProperties.bcryptStrength());
		Map<String, PasswordEncoder> encoders = Map.of("bcrypt", bcrypt);
		return new DelegatingPasswordEncoder("bcrypt", encoders);
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(properties.allowedOrigins());
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
		config.setExposedHeaders(List.of("Retry-After"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
