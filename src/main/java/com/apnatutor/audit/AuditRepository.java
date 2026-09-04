package com.apnatutor.audit;

import com.apnatutor.audit.domain.AuditEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reading the audit log.
 *
 * <p>No delete or update methods, and none should be added: the table refuses both (V18). Extending
 * {@code JpaRepository} still exposes {@code delete}, which is unfortunate but harmless — the call
 * fails at the database rather than quietly succeeding, which is the guarantee that matters.
 */
public interface AuditRepository extends JpaRepository<AuditEntry, Long> {

	/**
	 * The log, newest first, with every filter optional.
	 *
	 * <p>Native SQL with explicit {@code CAST}s for the same reason as
	 * {@code UserRepository.search}: Postgres cannot infer the type of a parameter that only ever
	 * appears as {@code :p IS NULL}, and fails the statement rather than the filter.
	 */
	@Query(value = """
			SELECT a.* FROM audit_log a
			WHERE (CAST(:actorId AS bigint) IS NULL OR a.actor_id = CAST(:actorId AS bigint))
			  AND (CAST(:targetType AS varchar) IS NULL
			       OR a.target_type = CAST(:targetType AS varchar))
			  AND (CAST(:targetId AS bigint) IS NULL OR a.target_id = CAST(:targetId AS bigint))
			  AND (CAST(:action AS varchar) IS NULL OR a.action = CAST(:action AS varchar))
			  AND (CAST(:outcome AS varchar) IS NULL OR a.outcome = CAST(:outcome AS varchar))
			ORDER BY a.created_at DESC, a.id DESC
			""",
			countQuery = """
			SELECT COUNT(*) FROM audit_log a
			WHERE (CAST(:actorId AS bigint) IS NULL OR a.actor_id = CAST(:actorId AS bigint))
			  AND (CAST(:targetType AS varchar) IS NULL
			       OR a.target_type = CAST(:targetType AS varchar))
			  AND (CAST(:targetId AS bigint) IS NULL OR a.target_id = CAST(:targetId AS bigint))
			  AND (CAST(:action AS varchar) IS NULL OR a.action = CAST(:action AS varchar))
			  AND (CAST(:outcome AS varchar) IS NULL OR a.outcome = CAST(:outcome AS varchar))
			""",
			nativeQuery = true)
	Page<AuditEntry> search(
			@Param("actorId") Long actorId,
			@Param("targetType") String targetType,
			@Param("targetId") Long targetId,
			@Param("action") String action,
			@Param("outcome") String outcome,
			Pageable pageable);
}
