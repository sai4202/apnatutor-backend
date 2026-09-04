package com.apnatutor.audit;

import java.util.Map;

import com.apnatutor.audit.domain.AuditEntry;
import com.apnatutor.audit.domain.AuditOutcome;
import com.apnatutor.user.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes and reads the audit log — {@code M5-08}.
 *
 * <h2>The write runs in its own transaction</h2>
 *
 * <p>{@code REQUIRES_NEW}, and it is the whole reason this is a separate bean from the interceptor
 * that calls it. An action that was refused or that blew up has, by then, rolled back its own
 * transaction — and the record of a failed attempt is the entry most worth keeping. Joining the
 * caller's transaction would roll the evidence back along with the thing it was evidence of.
 *
 * <p>This is the third time that trap has come up on this project, after
 * {@code WebhookEventRecorder}: {@code @Transactional} is proxy-applied, so {@code REQUIRES_NEW} on
 * a self-invoked method does nothing at all.
 */
@Service
public class AuditService {

	private static final Logger log = LoggerFactory.getLogger(AuditService.class);

	/** Matches the column. Longer summaries are truncated rather than losing the whole entry. */
	private static final int MAX_SUMMARY = 500;
	private static final int MAX_PATH = 300;

	private final AuditRepository entries;
	private final ObjectMapper json;

	public AuditService(AuditRepository entries, ObjectMapper json) {
		this.entries = entries;
		this.json = json;
	}

	/**
	 * Records one action.
	 *
	 * <p>Never throws. A failure to write the audit row must not turn a successful suspension into
	 * a 500 — the action already happened, and telling the admin it failed would have them do it
	 * again. It is logged at ERROR instead, which is the loudest thing available that does not
	 * change the outcome the caller sees.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(
			Long actorId,
			UserRole actorRole,
			String action,
			String targetType,
			Long targetId,
			String summary,
			Map<String, Object> before,
			Map<String, Object> after,
			AuditOutcome outcome,
			String httpMethod,
			String path,
			Integer httpStatus,
			String correlationId,
			String ipAddress) {

		try {
			entries.save(AuditEntry.of(
					actorId,
					actorRole,
					action,
					targetType,
					targetId,
					truncate(summary, MAX_SUMMARY),
					toJson(before),
					toJson(after),
					outcome,
					httpMethod,
					truncate(path, MAX_PATH),
					httpStatus,
					correlationId,
					ipAddress));
		} catch (RuntimeException e) {
			log.error("AUDIT WRITE FAILED action={} target={}#{} actor={} outcome={} — "
					+ "the action itself was not affected",
					action, targetType, targetId, actorId, outcome, e);
		}
	}

	@Transactional(readOnly = true)
	public Page<AuditEntry> search(
			Long actorId,
			String targetType,
			Long targetId,
			String action,
			AuditOutcome outcome,
			Pageable pageable) {

		return entries.search(
				actorId,
				targetType,
				targetId,
				action,
				outcome == null ? null : outcome.name(),
				pageable);
	}

	/**
	 * Serialises a state map.
	 *
	 * <p>Returns null rather than propagating on a serialisation failure. Something unserialisable
	 * in a before/after map is a bug at the call site, and it should cost that one field — not the
	 * entry recording who suspended whom.
	 */
	private String toJson(Map<String, Object> state) {
		if (state == null || state.isEmpty()) {
			return null;
		}
		try {
			return json.writeValueAsString(state);
		} catch (RuntimeException e) {
			log.error("Could not serialise audit state {}", state.keySet(), e);
			return null;
		}
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max - 1) + "…";
	}
}
