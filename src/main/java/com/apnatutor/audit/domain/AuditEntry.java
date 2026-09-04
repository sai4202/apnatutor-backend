package com.apnatutor.audit.domain;

import java.time.Instant;

import com.apnatutor.user.domain.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One recorded action — {@code M5-08}.
 *
 * <p>Deliberately has no setters and no state transitions. The table refuses {@code UPDATE} and
 * {@code DELETE} at the database level (V18), and an entity offering mutators for a row that cannot
 * be mutated is an invitation to write code that fails at runtime.
 */
@Entity
@Table(name = "audit_log")
public class AuditEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Null when the caller's token carried claims we could not read.
	 *
	 * <p>Not for anonymous requests: those never reach the interceptor at all — see
	 * {@link com.apnatutor.audit.AuditInterceptor} for why.
	 */
	@Column(name = "actor_id")
	private Long actorId;

	@Enumerated(EnumType.STRING)
	@Column(name = "actor_role", length = 16)
	private UserRole actorRole;

	@Column(nullable = false, length = 64)
	private String action;

	@Column(name = "target_type", length = 32)
	private String targetType;

	@Column(name = "target_id")
	private Long targetId;

	@Column(length = 500)
	private String summary;

	// Mapped as JSON so Postgres stores real jsonb rather than a quoted string. Without the
	// JdbcTypeCode, Hibernate binds a String as varchar and Postgres refuses the implicit cast.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "before_state", columnDefinition = "jsonb")
	private String beforeState;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "after_state", columnDefinition = "jsonb")
	private String afterState;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private AuditOutcome outcome;

	@Column(name = "http_method", length = 8)
	private String httpMethod;

	@Column(length = 300)
	private String path;

	@Column(name = "http_status")
	private Integer httpStatus;

	@Column(name = "correlation_id", length = 64)
	private String correlationId;

	@Column(name = "ip_address", length = 45)
	private String ipAddress;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	protected AuditEntry() {
		// Required by JPA.
	}

	/** Built only through {@link com.apnatutor.audit.AuditService}, which owns the truncation rules. */
	public static AuditEntry of(
			Long actorId,
			UserRole actorRole,
			String action,
			String targetType,
			Long targetId,
			String summary,
			String beforeState,
			String afterState,
			AuditOutcome outcome,
			String httpMethod,
			String path,
			Integer httpStatus,
			String correlationId,
			String ipAddress) {

		AuditEntry entry = new AuditEntry();
		entry.actorId = actorId;
		entry.actorRole = actorRole;
		entry.action = action;
		entry.targetType = targetType;
		entry.targetId = targetId;
		entry.summary = summary;
		entry.beforeState = beforeState;
		entry.afterState = afterState;
		entry.outcome = outcome;
		entry.httpMethod = httpMethod;
		entry.path = path;
		entry.httpStatus = httpStatus;
		entry.correlationId = correlationId;
		entry.ipAddress = ipAddress;
		return entry;
	}

	public Long getId() {
		return id;
	}

	public Long getActorId() {
		return actorId;
	}

	public UserRole getActorRole() {
		return actorRole;
	}

	public String getAction() {
		return action;
	}

	public String getTargetType() {
		return targetType;
	}

	public Long getTargetId() {
		return targetId;
	}

	public String getSummary() {
		return summary;
	}

	public String getBeforeState() {
		return beforeState;
	}

	public String getAfterState() {
		return afterState;
	}

	public AuditOutcome getOutcome() {
		return outcome;
	}

	public String getHttpMethod() {
		return httpMethod;
	}

	public String getPath() {
		return path;
	}

	public Integer getHttpStatus() {
		return httpStatus;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public String getIpAddress() {
		return ipAddress;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
