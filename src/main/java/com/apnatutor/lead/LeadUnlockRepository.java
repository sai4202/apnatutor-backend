package com.apnatutor.lead;

import java.util.List;
import java.util.Optional;

import com.apnatutor.lead.domain.LeadUnlock;
import com.apnatutor.lead.domain.UnlockStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadUnlockRepository extends JpaRepository<LeadUnlock, Long> {

	Optional<LeadUnlock> findByRequirementIdAndTutorId(Long requirementId, Long tutorId);

	/** Who has responded to a requirement — the student's "tutors who contacted you" list. */
	List<LeadUnlock> findByRequirementIdAndStatus(Long requirementId, UnlockStatus status);

	List<LeadUnlock> findByTutorIdOrderByUnlockedAtDesc(Long tutorId);

	boolean existsByRequirementIdAndTutorIdAndStatus(
			Long requirementId, Long tutorId, UnlockStatus status);

	/**
	 * An unlock this tutor already holds.
	 *
	 * <p>Used to make a repeated unlock return what was already bought instead of charging twice or
	 * erroring — see {@link LeadUnlockService#unlock}.
	 */
	Optional<LeadUnlock> findByRequirementIdAndTutorIdAndStatus(
			Long requirementId, Long tutorId, UnlockStatus status);

	long countByRequirementIdAndStatus(Long requirementId, UnlockStatus status);

	/** How many leads a tutor has bought and still holds. For the admin account summary. */
	long countByTutorIdAndStatus(Long tutorId, UnlockStatus status);

	/**
	 * Has this tutor ever been put in touch with this student?
	 *
	 * <p>The eligibility test for writing a review (SOURCE_OF_TRUTH.md section 3.6), and the reason
	 * Invariant 2 keeps this table generic: a v2 booking becomes another {@code engagementType} on
	 * the same connection graph, and this query keeps working untouched.
	 *
	 * <p>Restricted to {@code ACTIVE}. A refunded unlock is one the tutor successfully disputed as
	 * a bad lead — the platform has already accepted that the introduction was worthless. Letting
	 * that student then rate the tutor turns every refund into an invitation to retaliate, which
	 * would quietly teach tutors not to dispute.
	 */
	@Query("""
			SELECT COUNT(u) FROM LeadUnlock u, Requirement r
			WHERE r.id = u.requirementId
			  AND u.tutorId = :tutorUserId
			  AND r.studentId = :studentUserId
			  AND u.status = com.apnatutor.lead.domain.UnlockStatus.ACTIVE""")
	long countEngagementsBetween(
			@Param("tutorUserId") Long tutorUserId, @Param("studentUserId") Long studentUserId);
}
