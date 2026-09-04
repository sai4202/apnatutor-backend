package com.apnatutor.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a service tells the audit log what it just changed — {@code M5-08.2}.
 *
 * <h2>Why the entry is written by the HTTP layer and described here</h2>
 *
 * <p>Two things have to be true of this log and they pull in opposite directions. Nothing may be
 * missing, which argues for recording centrally where no call site can forget; and an entry has to
 * say what actually changed, which only the service holding both versions of the row can know.
 *
 * <p>So {@link AuditInterceptor} always writes exactly one entry per audited request — that part
 * cannot be forgotten, because it is not per-call-site — and the service optionally enriches it
 * through this class on the way past. An action nobody described is still logged, as its route and
 * its outcome. An action somebody described gains a name, a target and a before/after.
 *
 * <p>Every method here is a <strong>no-op when there is no audited request in flight</strong>: a
 * scheduled job, a test calling a service directly, or an internal call from another service. A
 * service must never have to ask whether it is being audited.
 *
 * <p>The state is a {@link ThreadLocal} cleared in {@code afterCompletion}, following
 * {@code CorrelationIdFilter}'s handling of the MDC. Servlet threads are pooled, and a draft left
 * behind attributes one admin's change to the next request that lands on the thread.
 */
public final class AuditContext {

	private static final ThreadLocal<Draft> CURRENT = new ThreadLocal<>();

	private AuditContext() {
	}

	/** Mutable while the request runs; read once, at the end, by the interceptor. */
	static final class Draft {
		String action;
		String targetType;
		Long targetId;
		String summary;
		Map<String, Object> before;
		Map<String, Object> after;
	}

	static void begin() {
		CURRENT.set(new Draft());
	}

	static Draft current() {
		return CURRENT.get();
	}

	static void clear() {
		CURRENT.remove();
	}

	/**
	 * Names the action and what it acted on.
	 *
	 * @param action a stable code — {@code USER_SUSPENDED}, {@code REQUIREMENT_REMOVED}. Counted,
	 *     so it must not be a sentence.
	 */
	public static void describe(String action, String targetType, Long targetId) {
		Draft draft = CURRENT.get();
		if (draft == null) {
			return;
		}
		draft.action = action;
		draft.targetType = targetType;
		draft.targetId = targetId;
	}

	/** The human line — normally the reason or note the admin typed. */
	public static void summarise(String summary) {
		Draft draft = CURRENT.get();
		if (draft != null) {
			draft.summary = summary;
		}
	}

	public static void before(Map<String, Object> state) {
		Draft draft = CURRENT.get();
		if (draft != null) {
			draft.before = state;
		}
	}

	public static void after(Map<String, Object> state) {
		Draft draft = CURRENT.get();
		if (draft != null) {
			draft.after = state;
		}
	}

	/**
	 * Builds a state map from alternating keys and values, skipping null values.
	 *
	 * <p>{@code Map.of} rejects nulls, and half the interesting fields here are nullable — a
	 * suspension reason before the suspension, a removal timestamp on a live enquiry. Callers
	 * should not have to branch around that.
	 *
	 * <p>Insertion-ordered, so the JSON reads in the order the call site wrote it rather than in
	 * hash order.
	 */
	public static Map<String, Object> fields(Object... keysAndValues) {
		if (keysAndValues.length % 2 != 0) {
			throw new IllegalArgumentException("fields() takes alternating keys and values");
		}

		Map<String, Object> state = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			Object value = keysAndValues[i + 1];
			if (value != null) {
				state.put(String.valueOf(keysAndValues[i]), value);
			}
		}
		return state;
	}
}
