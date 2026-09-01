package com.apnatutor.billing;

import java.util.List;
import java.util.Optional;

import com.apnatutor.billing.domain.RefundRequest;
import com.apnatutor.billing.domain.RefundStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

	Optional<RefundRequest> findByUnlockId(Long unlockId);

	boolean existsByUnlockId(Long unlockId);

	List<RefundRequest> findByTutorIdOrderByCreatedAtDesc(Long tutorId);

	Page<RefundRequest> findByStatusOrderByCreatedAtAsc(RefundStatus status, Pageable pageable);

	/** Denominator and numerator for the dispute-rate abuse signal. */
	long countByTutorId(Long tutorId);

	long countByTutorIdAndStatus(Long tutorId, RefundStatus status);

	/**
	 * How many tutors have disputed one requirement.
	 *
	 * <p>The stronger signal of the two. One tutor disputing many leads may just be bad at phone
	 * calls; several tutors disputing the <em>same</em> lead is a fact about the enquiry.
	 */
	long countByRequirementId(Long requirementId);
}
