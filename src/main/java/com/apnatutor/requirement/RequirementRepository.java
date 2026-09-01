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
	 *
	 * <p>Only published profiles see leads. An unpublished tutor unlocking a lead would put a
	 * stranger on a parent's phone with no profile for the parent to check them against — the
	 * verification ladder exists precisely so that does not happen.
	 */
	@Query(value = """
			SELECT r.* FROM requirements r
			WHERE r.status = 'OPEN'
			  AND r.expires_at > now()
			  AND EXISTS (SELECT 1 FROM tutor_subjects ts
			              JOIN tutor_profiles tp ON tp.id = ts.tutor_id
			              WHERE tp.user_id = :tutorUserId AND ts.subject_id = r.subject_id
			                AND tp.is_published)
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
			              WHERE tp.user_id = :tutorUserId AND ts.subject_id = r.subject_id
			                AND tp.is_published)
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

	/**
	 * The tutors who should be told a new enquiry exists.
	 *
	 * <p>The inverse of {@link #findLeadFeedFor}: same subject rule, same location rule, same
	 * published rule. The two must stay in step — notifying a tutor about a lead their feed will not
	 * show them sends them to an empty screen, which is worse than not notifying at all.
	 *
	 * <p>Deliberately capped by the caller. A popular subject in a large city can match hundreds of
	 * tutors, and a lead only has a handful of slots; messaging everyone would mean most recipients
	 * arriving to find it already taken.
	 */
	@Query(value = """
			SELECT tp.user_id FROM tutor_profiles tp
			WHERE tp.is_published
			  AND EXISTS (SELECT 1 FROM tutor_subjects ts
			              WHERE ts.tutor_id = tp.id AND ts.subject_id = :subjectId)
			  AND (:mode = 'ONLINE'
			       OR :locationId IS NULL
			       OR EXISTS (SELECT 1 FROM tutor_locations tl
			                  WHERE tl.tutor_id = tp.id
			                    AND (tl.location_id = :locationId
			                         OR (SELECT city FROM locations WHERE id = tl.location_id)
			                            = (SELECT city FROM locations WHERE id = :locationId))))
			ORDER BY (SELECT COUNT(*) FROM verifications v
			          WHERE v.user_id = tp.user_id AND v.status = 'APPROVED') DESC,
			         tp.avg_rating DESC NULLS LAST,
			         tp.review_count DESC,
			         tp.id
			LIMIT :limit
			""",
			nativeQuery = true)
	List<Long> findTutorsToNotify(
			@Param("subjectId") Long subjectId,
			@Param("locationId") Long locationId,
			@Param("mode") String mode,
			@Param("limit") int limit);

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
