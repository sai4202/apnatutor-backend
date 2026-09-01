package com.apnatutor.requirement;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.requirement.domain.RequirementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RequirementRepository extends JpaRepository<Requirement, Long> {

	List<Requirement> findByStudentIdOrderByCreatedAtDesc(Long studentId);

	/**
	 * Loads a requirement with a {@code SELECT ... FOR UPDATE} row lock.
	 *
	 * <p>This is what makes the unlock cap hold under concurrency. Without it, five tutors hitting
	 * the last slot simultaneously would each read {@code unlockCount = 4}, each conclude there is
	 * room, and five would be charged for four slots. The lock serialises them so the fifth sees
	 * the fourth's increment.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT r FROM Requirement r WHERE r.id = :id")
	Optional<Requirement> findByIdForUpdate(@Param("id") Long id);

	/**
	 * The tutor lead feed.
	 *
	 * <p>Open requirements the tutor teaches and can reach, excluding any they have already
	 * unlocked — a tutor should never be shown a lead they already own.
	 *
	 * <p>An ONLINE requirement matches every tutor who teaches online, wherever they are. A
	 * location-based one matches tutors listing that locality, or its city.
	 */
	@Query(value = """
			SELECT r.* FROM requirements r
			WHERE r.status = 'OPEN'
			  AND r.expires_at > now()
			  AND EXISTS (SELECT 1 FROM tutor_subjects ts
			              JOIN tutor_profiles tp ON tp.id = ts.tutor_id
			              WHERE tp.user_id = :tutorUserId AND ts.subject_id = r.subject_id)
			  AND (r.mode = 'ONLINE'
			       OR r.location_id IS NULL
			       OR EXISTS (SELECT 1 FROM tutor_locations tl
			                  JOIN tutor_profiles tp2 ON tp2.id = tl.tutor_id
			                  WHERE tp2.user_id = :tutorUserId
			                    AND (tl.location_id = r.location_id
			                         OR (SELECT city FROM locations WHERE id = tl.location_id)
			                            = (SELECT city FROM locations WHERE id = r.location_id))))
			  AND NOT EXISTS (SELECT 1 FROM lead_unlocks lu
			                  WHERE lu.requirement_id = r.id
			                    AND lu.tutor_id = :tutorUserId
			                    AND lu.status = 'ACTIVE')
			ORDER BY r.created_at DESC
			""",
			countQuery = """
			SELECT COUNT(*) FROM requirements r
			WHERE r.status = 'OPEN'
			  AND r.expires_at > now()
			  AND EXISTS (SELECT 1 FROM tutor_subjects ts
			              JOIN tutor_profiles tp ON tp.id = ts.tutor_id
			              WHERE tp.user_id = :tutorUserId AND ts.subject_id = r.subject_id)
			  AND (r.mode = 'ONLINE'
			       OR r.location_id IS NULL
			       OR EXISTS (SELECT 1 FROM tutor_locations tl
			                  JOIN tutor_profiles tp2 ON tp2.id = tl.tutor_id
			                  WHERE tp2.user_id = :tutorUserId
			                    AND (tl.location_id = r.location_id
			                         OR (SELECT city FROM locations WHERE id = tl.location_id)
			                            = (SELECT city FROM locations WHERE id = r.location_id))))
			  AND NOT EXISTS (SELECT 1 FROM lead_unlocks lu
			                  WHERE lu.requirement_id = r.id
			                    AND lu.tutor_id = :tutorUserId
			                    AND lu.status = 'ACTIVE')
			""",
			nativeQuery = true)
	Page<Requirement> findLeadFeedFor(@Param("tutorUserId") Long tutorUserId, Pageable pageable);

	/** Requirements a tutor has unlocked, for their "my leads" list. */
	@Query("""
			SELECT r FROM Requirement r
			WHERE r.id IN (SELECT lu.requirementId FROM LeadUnlock lu
			               WHERE lu.tutorId = :tutorUserId AND lu.status = com.apnatutor.lead.domain.UnlockStatus.ACTIVE)
			ORDER BY r.createdAt DESC""")
	List<Requirement> findUnlockedBy(@Param("tutorUserId") Long tutorUserId);

	/** The expiry job. Batched by the caller's page size rather than loading everything. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE Requirement r SET r.status = com.apnatutor.requirement.domain.RequirementStatus.EXPIRED
			WHERE r.expiresAt <= :now
			  AND r.status IN (com.apnatutor.requirement.domain.RequirementStatus.OPEN,
			                   com.apnatutor.requirement.domain.RequirementStatus.CAPPED)""")
	int expireOlderThan(@Param("now") Instant now);

	long countByStatus(RequirementStatus status);
}
