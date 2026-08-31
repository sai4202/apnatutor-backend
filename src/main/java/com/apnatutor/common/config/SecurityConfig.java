package com.apnatutor.common.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Baseline HTTP security.
 *
 * <p>Spring Security secures everything by default, so without this the health endpoint the
 * frontend polls would sit behind a generated login page. This opens exactly the routes that must
 * be public and leaves the rest closed.
 *
 * <p>Deliberately default-deny: {@code anyRequest().authenticated()} is last, so a new endpoint is
 * private until someone consciously opens it. Adding a public route should require thinking about
 * it.
 *
 * <p>JWT authentication itself arrives in M1. Today there is no way to authenticate, which is
 * correct — there are no protected endpoints to reach yet.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	private final String frontendUrl;

	public SecurityConfig(@Value("${apnatutor.frontend-url}") String frontendUrl) {
		this.frontendUrl = frontendUrl;
	}

	/**
	 * Note the CORS source is referenced by calling {@link #corsConfigurationSource()} rather than
	 * taking it as a parameter. Spring MVC's {@code mvcHandlerMappingIntrospector} also implements
	 * {@code CorsConfigurationSource}, so injecting by type is ambiguous and fails context startup.
	 * The direct call is unambiguous, and still returns the singleton because {@code @Configuration}
	 * classes are proxied.
	 */
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				// Safe while every authenticated request is authorised by a Bearer token in a
				// header, which a cross-site form cannot set.
				// M1 CAVEAT: the refresh token will live in a cookie, and cookies ARE sent
				// cross-site. The refresh endpoint must therefore get its own CSRF defence
				// (SameSite=Strict plus an explicit CSRF token) when it is built. Do not let
				// this line silently cover it.
				.csrf(AbstractHttpConfigurer::disable)
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.sessionManagement(session ->
						session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
						.permitAll()
						// Public marketplace browsing: search, tutor profiles, catalog, SEO pages.
						// Contact details are masked in the response, not protected by this layer.
						.requestMatchers("/api/v1/public/**").permitAll()
						.requestMatchers("/api/v1/auth/**").permitAll()
						// API docs. Disabled entirely in production via
						// APNATUTOR_API_DOCS_ENABLED — a public schema dump is free
						// reconnaissance for anyone probing the API.
						.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
								"/swagger-ui/**", "/swagger-ui.html")
						.permitAll()
						.anyRequest().authenticated())
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable);

		return http.build();
	}

	/**
	 * CORS for the Next.js dev server and, later, the deployed frontend.
	 *
	 * <p>Origins come from configuration rather than a wildcard: credentials are allowed, and the
	 * two are mutually exclusive in the CORS spec for good reason.
	 */
	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(List.of(frontendUrl));
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
		config.setExposedHeaders(List.of("Location"));
		// Required for the refresh-token cookie in M1.
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		source.registerCorsConfiguration("/actuator/**", config);
		return source;
	}

	/**
	 * BCrypt for the optional password login path. OTP is the primary identity mechanism
	 * (SOURCE_OF_TRUTH.md ADR #9), so many accounts will have a null password hash.
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
