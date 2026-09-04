package com.apnatutor.audit;

import com.apnatutor.audit.domain.AuditOutcome;
import com.apnatutor.user.domain.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.apnatutor.common.web.CorrelationIdFilter;

/**
 * Writes exactly one audit entry for every admin action — {@code M5-08.2}.
 *
 * <h2>Why an interceptor rather than a call in each service</h2>
 *
 * <p>The same argument that put the suspension check in the search query rather than on a flag
 * (ADR #14). Auditing from inside each admin service means every future admin endpoint has to
 * remember to do it, and the failure mode is silent: an action nobody logged looks exactly like an
 * action nobody took. Here the rule is the route, so a controller added next year is audited
 * because of where it lives, not because its author thought of it.
 *
 * <p>What the interceptor cannot know is what changed, so services describe themselves through
 * {@link AuditContext} and this combines the two.
 *
 * <h2>What this cannot see</h2>
 *
 * <p>A request with <strong>no credentials at all</strong> is rejected by the security filter chain
 * ({@code anyRequest().authenticated()}) before the dispatcher runs, so no interceptor fires and no
 * entry is written. An authenticated caller who is not an admin <em>is</em> recorded, because
 * {@code @PreAuthorize} is evaluated during dispatch — and that is the case worth having: somebody
 * holding a real token trying admin routes. Anonymous probing shows up in the access log, and is
 * what the rate limiting in {@code M5-07} is for. Recorded as debt T19.
 *
 * <h2>What is audited</h2>
 *
 * <p>Every mutating request under {@code /admin}. Plus two kinds of {@code GET}, which are audited
 * because reading them <em>is</em> the sensitive act:
 *
 * <ul>
 *   <li>{@code /admin/files/**} — the only route to an ID or education document.
 *   <li>{@code /admin/requirements/**} — the only projection of an enquiry carrying the student's
 *       own phone number (debt T18). Necessary for spotting spam, and the thing that turns "an
 *       admin could have read every parent's number" into "an admin did, at 14:32".
 * </ul>
 *
 * <p>Other admin reads are not audited. Working a queue means loading it repeatedly, and an entry
 * per poll would bury the entries that matter under noise — which is a way of losing an audit log
 * without deleting anything.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

	private static final String API_PREFIX = "/api/v1";
	private static final String ADMIN_PREFIX = API_PREFIX + "/admin/";
	private static final String ROLE_CLAIM = "role";

	private final AuditService audit;

	public AuditInterceptor(AuditService audit) {
		this.audit = audit;
	}

	@Override
	public boolean preHandle(
			HttpServletRequest request, HttpServletResponse response, Object handler) {

		if (isAuditable(request)) {
			AuditContext.begin();
		}
		return true;
	}

	@Override
	public void afterCompletion(
			HttpServletRequest request,
			HttpServletResponse response,
			Object handler,
			Exception exception) {

		AuditContext.Draft draft = AuditContext.current();
		if (draft == null) {
			return;
		}

		try {
			int status = response.getStatus();

			audit.record(
					actorId(),
					actorRole(),
					// A described action keeps its name; anything else is identified by its route,
					// which is still enough to read.
					draft.action != null ? draft.action : fallbackAction(request),
					draft.targetType,
					draft.targetId,
					draft.summary,
					draft.before,
					draft.after,
					outcomeFor(status, exception),
					request.getMethod(),
					request.getRequestURI(),
					status,
					MDC.get(CorrelationIdFilter.MDC_KEY),
					clientIp(request));
		} finally {
			// Servlet threads are pooled. A draft left behind would attribute this admin's change
			// to whoever the thread serves next.
			AuditContext.clear();
		}
	}

	private static boolean isAuditable(HttpServletRequest request) {
		String path = request.getRequestURI();
		if (path == null || !path.startsWith(ADMIN_PREFIX)) {
			return false;
		}

		if (!"GET".equalsIgnoreCase(request.getMethod())) {
			return true;
		}

		// Sensitive reads. See the class javadoc.
		return path.startsWith(ADMIN_PREFIX + "files/")
				|| path.startsWith(ADMIN_PREFIX + "requirements");
	}

	/**
	 * A readable name for an action no service described.
	 *
	 * <p>Built from the method and the route with numeric path segments replaced, so
	 * {@code POST /admin/users/42/suspend} and the same call for user 7 count as one action rather
	 * than two. The id is not lost — it is in {@code path}, and usually in {@code target_id}.
	 */
	private static String fallbackAction(HttpServletRequest request) {
		String path = request.getRequestURI()
				.substring(API_PREFIX.length())
				.replaceAll("/[0-9]+", "/{id}");

		String action = request.getMethod() + " " + path;
		return action.length() <= 64 ? action : action.substring(0, 64);
	}

	private static AuditOutcome outcomeFor(int status, Exception exception) {
		if (exception != null || status >= 500) {
			return AuditOutcome.FAILED;
		}
		return status >= 400 ? AuditOutcome.REFUSED : AuditOutcome.SUCCEEDED;
	}

	private static Long actorId() {
		Jwt jwt = jwt();
		if (jwt == null) {
			return null;
		}
		try {
			return Long.valueOf(jwt.getSubject());
		} catch (NumberFormatException | NullPointerException e) {
			return null;
		}
	}

	private static UserRole actorRole() {
		Jwt jwt = jwt();
		if (jwt == null) {
			return null;
		}
		try {
			return UserRole.valueOf(jwt.getClaimAsString(ROLE_CLAIM));
		} catch (IllegalArgumentException | NullPointerException e) {
			return null;
		}
	}

	private static Jwt jwt() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null && authentication.getPrincipal() instanceof Jwt token
				? token
				: null;
	}

	/**
	 * The caller's address.
	 *
	 * <p>{@code X-Forwarded-For} is read but only the first hop is kept, and it is capped: the
	 * header is client-supplied and reaches a stored column, so an unbounded value is a way to
	 * write junk into the audit log. Trusting it at all is a deployment assumption — behind a proxy
	 * we control it is right, and directly exposed it is spoofable. Recorded either way as one
	 * signal among several, never as an identity.
	 */
	private static String clientIp(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		String ip = forwarded != null && !forwarded.isBlank()
				? forwarded.split(",")[0].strip()
				: request.getRemoteAddr();

		if (ip == null || ip.isBlank()) {
			return null;
		}
		return ip.length() <= 45 ? ip : ip.substring(0, 45);
	}
}
