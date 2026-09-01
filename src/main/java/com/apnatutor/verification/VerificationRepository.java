package com.apnatutor.verification;

import java.util.List;
import java.util.Optional;

import com.apnatutor.verification.domain.Verification;
import com.apnatutor.verification.domain.VerificationStatus;
import com.apnatutor.verification.domain.VerificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationRepository extends JpaRepository<Verification, Long> {

	List<Verification> findByUserId(Long userId);

	/** The live request for a type — pending or approved. Matches the unique partial index in V7. */
	Optional<Verification> findFirstByUserIdAndTypeAndStatusIn(
			Long userId, VerificationType type, List<VerificationStatus> statuses);

	/** The admin queue. Oldest first, so nobody waits behind a stream of newer submissions. */
	Page<Verification> findByStatusOrderByCreatedAtAsc(VerificationStatus status, Pageable pageable);

	List<Verification> findByUserIdAndStatus(Long userId, VerificationStatus status);

	/**
	 * User ids with an approved verification of a given type, for a set of users.
	 *
	 * <p>One query for a whole search results page rather than one per tutor — the difference
	 * between a constant and an N+1 on the hottest page in the application.
	 */
	@Query("""
			SELECT v.userId FROM Verification v
			WHERE v.userId IN :userIds
			  AND v.type = :type
			  AND v.status = com.apnatutor.verification.domain.VerificationStatus.APPROVED""")
	List<Long> findApprovedUserIds(
			@Param("userIds") List<Long> userIds, @Param("type") VerificationType type);
}
