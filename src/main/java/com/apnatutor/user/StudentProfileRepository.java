package com.apnatutor.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.apnatutor.user.domain.StudentProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentProfileRepository extends JpaRepository<StudentProfile, Long> {

	Optional<StudentProfile> findByUserId(Long userId);

	/**
	 * Names for a set of students in one query.
	 *
	 * <p>Used to label a page of reviews. One query per reviewer would be an N+1 on a public page,
	 * which is the pattern {@code VerificationRepository.findApprovedUserIds} exists to avoid on
	 * search results.
	 */
	List<StudentProfile> findByUserIdIn(Collection<Long> userIds);
}
