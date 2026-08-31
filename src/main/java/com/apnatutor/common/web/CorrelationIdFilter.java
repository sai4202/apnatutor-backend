package com.apnatutor.common.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tags every request with a correlation ID, in the log context and in the response header.
 *
 * <p>The point is being able to answer "what happened to this user's unlock at 14:32?" without
 * grepping by timestamp and hoping. When a tutor reports a lost credit, the ID from their response
 * pulls the exact request's log lines.
 *
 * <p>An inbound {@code X-Correlation-Id} is honoured so a trace survives across hops; otherwise one
 * is generated. Inbound values are length-capped and stripped of anything but safe characters —
 * this string reaches the logs, and unsanitised input in a log line is how log injection works.
 *
 * <p>Runs first so that everything downstream, including the security filters, logs under the ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Correlation-Id";
	public static final String MDC_KEY = "correlationId";

	private static final int MAX_LENGTH = 64;

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		String correlationId = sanitise(request.getHeader(HEADER));
		if (correlationId == null) {
			correlationId = UUID.randomUUID().toString();
		}

		MDC.put(MDC_KEY, correlationId);
		response.setHeader(HEADER, correlationId);

		try {
			chain.doFilter(request, response);
		} finally {
			// Threads are pooled and reused. Without this, the next request handled by this thread
			// inherits a stale ID and the logs quietly lie.
			MDC.remove(MDC_KEY);
		}
	}

	/** Returns null if the value is absent, empty, or contains anything outside a safe set. */
	private static String sanitise(String value) {
		if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
			return null;
		}
		return value.matches("[A-Za-z0-9_.\\-]+") ? value : null;
	}
}
