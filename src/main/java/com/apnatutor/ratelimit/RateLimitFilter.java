package com.apnatutor.ratelimit;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import com.apnatutor.common.config.AppProperties;
import com.apnatutor.common.web.ApiError;
import com.apnatutor.common.web.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-IP rate limiting — {@code M5-07.1}, {@code M5-07.2}, {@code M5-07.4}.
 *
 * <h2>Why this runs before Spring Security</h2>
 *
 * <p>Ordered at {@code -101}, one ahead of the security filter chain at {@code -100}. A limiter
 * behind security never sees an anonymous request to a protected route, because security rejects it
 * first — which is exactly debt <strong>T19</strong>: anonymous probing of {@code /admin} produced
 * a 401 and no record anywhere. Sitting in front means the flood is turned away before it reaches
 * authentication, which is also the cheapest place to turn it away.
 *
 * <p>The cost of that position is that the caller is not yet authenticated, so <strong>every bucket
 * here is keyed on IP</strong>. Keying on a user id would mean reading it from the token before
 * anything has verified the token — and an unverified {@code sub} claim is attacker-chosen, so a
 * forged one would let an attacker spend a victim's allowance. Per-user limits live in the service
 * layer instead, where the identity has been proven.
 *
 * <h2>Shared addresses</h2>
 *
 * <p>Indian mobile networks use carrier-grade NAT heavily, so thousands of unrelated users can
 * arrive from one address. Every limit here is therefore set for "one address behaving badly",
 * not "one person behaving normally" — a limit tuned to a single human would lock out a whole
 * city block. The per-phone OTP cap (5/hour, {@code M1-03}) is what actually protects an
 * individual number; this protects the service.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	/**
	 * One ahead of {@code SecurityProperties.DEFAULT_FILTER_ORDER}.
	 *
	 * <p>Registered through {@code RateLimitFilterConfig} rather than by an {@code @Order}
	 * annotation, because {@code @Order} on a {@code @Component} filter is ignored by Boot's
	 * servlet registration — a trap that silently leaves the filter at the end of the chain, which
	 * is precisely the position this class exists not to be in.
	 */
	public static final int ORDER = -101;

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

	/** A route pattern and the allowance it carries. First match wins, so order matters. */
	private record Route(String prefix, RateLimitPolicy policy) {
	}

	private final List<Route> routes;
	private final RateLimiter limiter;
	private final ObjectMapper json;
	private final boolean devMode;

	public RateLimitFilter(RateLimiter limiter, ObjectMapper json, AppProperties properties) {
		this.limiter = limiter;
		this.json = json;
		this.devMode = properties.dev().enabled();
		AppProperties.RateLimit config = properties.rateLimit();

		this.routes = List.of(
				// Sign-in. The tightest limit on the service, because this is the one endpoint that
				// costs real money per request — every OTP is an SMS somebody pays for.
				new Route("/api/v1/auth/", new RateLimitPolicy(
						"auth", config.authPerMinute(), Duration.ofMinutes(1))),

				// Admin. Covers T19: an anonymous flood at /admin is now stopped here rather than
				// producing an unlogged 401 per request.
				new Route("/api/v1/admin/", new RateLimitPolicy(
						"admin", config.adminPerMinute(), Duration.ofMinutes(1))),

				// Public search and the SEO pages behind it. Loosest by a wide margin — see the
				// note on server-side rendering below.
				new Route("/api/v1/public/", new RateLimitPolicy(
						"public", config.publicPerMinute(), Duration.ofMinutes(1))),

				// Everything else authenticated. A backstop rather than a considered limit; the
				// endpoints that need a real one have it in the service layer.
				new Route("/api/v1/", new RateLimitPolicy(
						"api", config.apiPerMinute(), Duration.ofMinutes(1))));
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		RateLimitPolicy policy = policyFor(request.getRequestURI());
		if (policy == null) {
			chain.doFilter(request, response);
			return;
		}

		String ip = clientIp(request);
		RateLimiter.Decision decision = limiter.check(ip, policy);

		if (decision.allowed()) {
			chain.doFilter(request, response);
			return;
		}

		log.warn("Rate limit '{}' exceeded by {} on {} {}",
				policy.name(), ip, request.getMethod(), request.getRequestURI());

		reject(response, decision.retryAfterSeconds());
	}

	/**
	 * Dev mode is not limited.
	 *
	 * <p>Seeded test accounts exist to be signed into repeatedly, and the OTP cap already exempts
	 * them for the same reason. {@code DevModeGuard} refuses to start the application with dev mode
	 * on alongside a real SMS provider or a {@code prod} profile, so this cannot be the reason a
	 * production deployment is unprotected.
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return devMode;
	}

	private RateLimitPolicy policyFor(String path) {
		if (path == null) {
			return null;
		}
		return routes.stream()
				.filter(route -> path.startsWith(route.prefix()))
				.map(Route::policy)
				.findFirst()
				.orElse(null);
	}

	private void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
		response.setStatus(ErrorCode.RATE_LIMITED.status().value());
		// M5-07.4. A 429 without this tells a client to back off but not by how much, and the
		// usual response to that is an immediate retry.
		response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);

		response.getWriter().write(json.writeValueAsString(ApiError.of(
				ErrorCode.RATE_LIMITED,
				"Too many requests. Please try again in %d seconds.".formatted(retryAfterSeconds))));
	}

	/**
	 * The caller's address.
	 *
	 * <p><strong>The trap worth knowing about:</strong> the Next.js frontend renders public pages on
	 * the server, so every SEO page view reaches {@code /api/v1/public/**} from the frontend
	 * server's single address, not the visitor's. A per-IP limit that did not account for that
	 * would throttle the entire site the moment traffic arrived — which is why the public allowance
	 * is set in the thousands and why the deployment must forward the visitor address. Recorded as
	 * debt T21.
	 *
	 * <p>{@code X-Forwarded-For} is read, first hop only, length-capped. Trusting it is a deployment
	 * assumption: correct behind a proxy we control, spoofable if the application is exposed
	 * directly. That is the same assumption the audit log's IP column already carries.
	 */
	private static String clientIp(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		String ip = forwarded != null && !forwarded.isBlank()
				? forwarded.split(",")[0].strip()
				: request.getRemoteAddr();

		if (ip == null || ip.isBlank()) {
			return "unknown";
		}
		return ip.length() <= 45 ? ip : ip.substring(0, 45);
	}
}
