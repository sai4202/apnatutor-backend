package com.apnatutor.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.apnatutor.user.domain.TutorProfile;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Note there is deliberately no {@code @EntityGraph} fetching subjects, locations and
 * qualifications together.
 *
 * <p>An earlier version tried exactly that and failed at runtime with Hibernate's {@code
 * MultipleBagFetchException} — a single query cannot join-fetch more than one unordered {@code
 * List}, because the result rows become ambiguous. The workarounds (switching to {@code Set}, or
 * adding an {@code @OrderColumn}) buy a cartesian product in exchange.
 *
 * <p>Letting the three collections load lazily inside the service transaction costs three extra
 * queries on a profile read. That is the right trade: a profile read is one row for one user, not
 * the hot path. Search is the hot path, and it never touches these collections — its projection is
 * built from {@code tutor_profiles} alone.
 */
public interface TutorProfileRepository extends JpaRepository<TutorProfile, Long> {

	Optional<TutorProfile> findByUserId(Long userId);

	boolean existsByUserId(Long userId);

	/** Profiles for a set of tutor accounts, to label a list without a query per row. */
	List<TutorProfile> findByUserIdIn(Collection<Long> userIds);
}
