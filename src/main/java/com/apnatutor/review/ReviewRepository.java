package com.apnatutor.review;

import java.util.List;
import java.util.Optional;

import com.apnatutor.review.domain.ModerationStatus;
import com.apnatutor.review.domain.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

	/** Matches the unique index in V16. One review per student-tutor pair. */
	Optional<Review> findByTutorIdAndStudentId(Long tutorId, Long studentId);

	boolean existsByTutorIdAndStudentId(Long tutorId, Long studentId);

	/**
	 * The public list for a tutor's profile page.
	 *
	 * <p>The status filter is in the query, not applied by the caller after the fact. A public read
	 * that fetches everything and filters in Java is one refactor away from forgetting to.
	 */
	List<Review> findByTutorIdAndStatusOrderByCreatedAtDesc(Long tutorId, ModerationStatus status);

	/** Everything written about a tutor, for the tutor themselves — including what is pending. */
	List<Review> findByTutorIdOrderByCreatedAtDesc(Long tutorId);

	/** A student's own reviews, with the status of each. */
	List<Review> findByStudentIdOrderByCreatedAtDesc(Long studentId);

	/** The moderation queue, oldest first, so nobody waits behind newer submissions. */
	Page<Review> findByStatusOrderByCreatedAtAsc(ModerationStatus status, Pageable pageable);

	/** The separate queue of replies awaiting a decision. */
	Page<Review> findByTutorReplyStatusOrderByTutorReplyAtAsc(
			ModerationStatus replyStatus, Pageable pageable);

	/**
	 * Rewrites one tutor's cached aggregates from the reviews table.
	 *
	 * <p><strong>Derived, never incremented.</strong> {@code review_count = review_count + 1} on
	 * approval would drift the moment two admins approved at once — both read the old value, both
	 * write the same new one, and one review vanishes from the count for good. It also cannot be
	 * replayed: there is no way to ask an incremented counter whether it is still correct.
	 *
	 * <p>Recomputing from the table makes the operation idempotent and self-healing, and lets the
	 * same statement serve approval, rejection and un-publication without a sign to get backwards.
	 * This is the same reasoning that makes {@code credit_wallets.balance} a cache over the ledger
	 * (Invariant 1): the rows are the truth, the column is a convenience.
	 *
	 * <p>With no approved reviews, {@code AVG} yields NULL and {@code COUNT} yields 0 — exactly the
	 * state a tutor who has just had their only review withdrawn should be left in.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE tutor_profiles p
			SET avg_rating = sub.avg_rating,
				review_count = sub.review_count
			FROM (SELECT ROUND(AVG(r.rating), 1) AS avg_rating,
						 COUNT(*)                AS review_count
				  FROM reviews r
				  WHERE r.tutor_id = :tutorUserId
					AND r.status = 'APPROVED') sub
			WHERE p.user_id = :tutorUserId
			""", nativeQuery = true)
	void recomputeRatingFor(@Param("tutorUserId") Long tutorUserId);

	/**
	 * Rebuilds every tutor's aggregates. Backfill and drift repair — {@code M5-04.3}.
	 *
	 * <p>The counterpart to {@code CreditLedger.reconcile}: if a cached column and the rows it
	 * summarises can ever disagree, there has to be a way to find out and a way to put it right.
	 * Correlated subqueries rather than a join, so a tutor with no reviews is still visited and
	 * reset rather than silently skipped.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE tutor_profiles p
			SET avg_rating = (SELECT ROUND(AVG(r.rating), 1) FROM reviews r
							  WHERE r.tutor_id = p.user_id AND r.status = 'APPROVED'),
				review_count = (SELECT COUNT(*) FROM reviews r
								WHERE r.tutor_id = p.user_id AND r.status = 'APPROVED')
			""", nativeQuery = true)
	int recomputeAllRatings();
}
