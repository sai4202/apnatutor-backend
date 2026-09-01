package com.apnatutor.verification;

import java.util.List;
import java.util.Optional;

import com.apnatutor.verification.domain.Verification;
import com.apnatutor.verification.domain.VerificationStatus;
import com.apnatutor.verification.domain.VerificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationRepository extends JpaRepository<Verification, Long> {

	List<Verification> findByUserId(Long userId);

	/** The live request for a type — pending or approved. Matches the unique partial index in V7. */
	Optional<Verification> findFirstByUserIdAndTypeAndStatusIn(
			Long userId, VerificationType type, List<VerificationStatus> statuses);

	/** The admin queue. Oldest first, so nobody waits behind a stream of newer submissions. */
	Page<Verification> findByStatusOrderByCreatedAtAsc(VerificationStatus status, Pageable pageable);

	List<Verification> findByUserIdAndStatus(Long userId, VerificationStatus status);
}
