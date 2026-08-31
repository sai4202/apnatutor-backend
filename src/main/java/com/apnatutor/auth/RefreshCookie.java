package com.apnatutor.auth;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

/**
 * Builds and reads the refresh-token cookie.
 *
 * <p>The refresh token lives in a cookie rather than in a JSON response body so that JavaScript
 * cannot read it. A token held in {@code localStorage} is readable by any successful XSS; an
 * {@code HttpOnly} cookie is not. That single property is why the flow is shaped this way.
 *
 * <h2>CSRF (repays PENDING.md T1, task M1-04.5)</h2>
 *
 * <p>Cookies are attached by the browser automatically, so a cookie-authenticated endpoint is
 * exposed to cross-site request forgery in a way that a {@code Authorization: Bearer} endpoint is
 * not. The global CSRF disable in {@code SecurityConfig} is safe for Bearer routes and explicitly
 * does <em>not</em> cover this one. Two independent defences apply here:
 *
 * <ol>
 *   <li><strong>{@code SameSite=Strict}</strong> — the browser does not attach this cookie to any
 *       request originating from another site, which defeats the classic form-post attack outright.
 *   <li><strong>A required custom header</strong> ({@value #CSRF_HEADER}) — HTML forms cannot set
 *       custom headers at all, and scripted cross-origin requests that try are forced into a CORS
 *       preflight, which our configuration answers only for the known frontend origin.
 * </ol>
 *
 * <p>Either alone would very likely do; both together mean a single misconfiguration is not a
 * breach.
 */
public final class RefreshCookie {

	public static final String COOKIE_NAME = "apnatutor_refresh";

	/** Presence is what matters, not the value — see the class javadoc. */
	public static final String CSRF_HEADER = "X-Refresh-Request";

	/**
	 * Scoped to the refresh and logout endpoints, so the cookie is not attached to every ordinary
	 * API call. Fewer requests carrying the credential means fewer chances to leak it.
	 */
	private static final String PATH = "/api/v1/auth";

	private RefreshCookie() {
	}

	public static ResponseCookie create(String token, Duration ttl, boolean secure) {
		return ResponseCookie.from(COOKIE_NAME, token)
				.httpOnly(true)
				.secure(secure)
				.sameSite("Strict")
				.path(PATH)
				.maxAge(ttl)
				.build();
	}

	/**
	 * An expired, empty cookie. Attributes must match {@link #create} exactly — a browser treats a
	 * differing path or SameSite as a different cookie and quietly keeps the original.
	 */
	public static ResponseCookie clear(boolean secure) {
		return ResponseCookie.from(COOKIE_NAME, "")
				.httpOnly(true)
				.secure(secure)
				.sameSite("Strict")
				.path(PATH)
				.maxAge(0)
				.build();
	}

	public static String read(HttpServletRequest request) {
		if (request.getCookies() == null) {
			return null;
		}
		for (var cookie : request.getCookies()) {
			if (COOKIE_NAME.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	public static boolean hasCsrfHeader(HttpServletRequest request) {
		return request.getHeader(CSRF_HEADER) != null;
	}
}
