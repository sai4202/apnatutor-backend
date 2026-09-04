package com.apnatutor.admin.dto;

import java.time.Instant;

import com.apnatutor.audit.domain.AuditEntry;
import com.apnatutor.audit.domain.AuditOutcome;
import com.apnatutor.user.domain.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

/** The audit log on the wire — {@code M5-08.4}. */
public final class AuditDtos {

	private AuditDtos() {
	}

	@Schema(description = "One recorded action. Immutable, like the row behind it.")
	public record AuditRow(
			Long id,
			Long actorId,
			UserRole actorRole,
			@Schema(description = "A stable code where the service named itself, else its route")
			String action,
			String targetType,
			Long targetId,
			String summary,
			@Schema(description = "JSON. Partial by design — the fields the decision moved.")
			String beforeState,
			String afterState,
			AuditOutcome outcome,
			String httpMethod,
			String path,
			Integer httpStatus,
			@Schema(description = "Ties this entry to its request's log lines")
			String correlationId,
			String ipAddress,
			Instant at) {

		public static AuditRow from(AuditEntry entry) {
			return new AuditRow(
					entry.getId(),
					entry.getActorId(),
					entry.getActorRole(),
					entry.getAction(),
					entry.getTargetType(),
					entry.getTargetId(),
					entry.getSummary(),
					entry.getBeforeState(),
					entry.getAfterState(),
					entry.getOutcome(),
					entry.getHttpMethod(),
					entry.getPath(),
					entry.getHttpStatus(),
					entry.getCorrelationId(),
					entry.getIpAddress(),
					entry.getCreatedAt());
		}
	}
}
