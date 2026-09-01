package com.apnatutor.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.apnatutor.common.web.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
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
	private final Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter;

	public SecurityConfig(
			@Value("${apnatutor.frontend-url}") String frontendUrl,
			Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter) {
		this.frontendUrl = frontendUrl;
		this.jwtAuthenticationConverter = jwtAuthenticationConverter;
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
						// Public marketplace browsing: search, tutor profiles, catalog, SEO pages,
						// and profile photos. Contact details are masked in the response, not
						// protected by this layer.
						//
						// Note /api/v1/public/files/** serves ONLY file kinds marked public —
						// FileController refuses anything else with a 404. Private documents are
						// reachable only through /api/v1/admin/files/**, which is admin-gated.
						.requestMatchers("/api/v1/public/**").permitAll()
						.requestMatchers("/api/v1/auth/**").permitAll()
						// API docs. Disabled entirely in production via
						// APNATUTOR_API_DOCS_ENABLED — a public schema dump is free
						// reconnaissance for anyone probing the API.
						.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
								"/swagger-ui/**", "/swagger-ui.html")
						.permitAll()
						.anyRequest().authenticated())
				// Bearer token validation. Spring's resource-server support extracts, verifies and
				// populates the SecurityContext, so there is no hand-rolled authentication filter
				// to get subtly wrong.
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
						// Both handlers exist so auth failures come back as our ApiError shape with
						// an ErrorCode, rather than Spring's default empty body.
						.authenticationEntryPoint(this::writeUnauthenticated)
						.accessDeniedHandler(this::writeForbidden))
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(this::writeUnauthenticated)
						.accessDeniedHandler(this::writeForbidden))
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable);

		return http.build();
	}

	private void writeUnauthenticated(
			HttpServletRequest request, HttpServletResponse response, Exception ex)
			throws IOException {
		writeError(response, ErrorCode.UNAUTHENTICATED, "Authentication required");
	}

	private void writeForbidden(
			HttpServletRequest request, HttpServletResponse response, Exception ex)
			throws IOException {
		writeError(response, ErrorCode.FORBIDDEN, "You do not have permission to do that");
	}

	/**
	 * Security-filter failures happen before {@code @RestControllerAdvice} can see them, so the
	 * error body is written here by hand to keep every response in the same shape.
	 */
	private void writeError(HttpServletResponse response, ErrorCode code, String message)
			throws IOException {
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(
				"{\"code\":\"%s\",\"message\":\"%s\"}".formatted(code.name(), message));
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
		config.setAllowedHeaders(List.of(
				"Authorization", "Content-Type", "Idempotency-Key",
				// The refresh endpoint's CSRF marker — see RefreshCookie.
				"X-Refresh-Request", "X-Correlation-Id"));
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
