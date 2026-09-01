package com.apnatutor.lead;

import java.util.List;
import java.util.Optional;

import com.apnatutor.lead.domain.LeadUnlock;
import com.apnatutor.lead.domain.UnlockStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
