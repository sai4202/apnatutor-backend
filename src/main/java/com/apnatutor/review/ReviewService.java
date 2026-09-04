package com.apnatutor.review;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.apnatutor.audit.AuditContext;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.lead.LeadUnlockRepository;
import com.apnatutor.notification.NotificationService;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.review.domain.ModerationStatus;
import com.apnatutor.review.domain.Review;
import com.apnatutor.search.ContactMasking;
import com.apnatutor.user.StudentProfileRepository;
import com.apnatutor.user.TutorProfileRepository;
import com.apnatutor.user.domain.StudentProfile;
import com.apnatutor.user.domain.TutorProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing, moderating and answering reviews.
 *
 * <h2>Nothing user-written is public before a human approves it</h2>
 *
 * <p>SOURCE_OF_TRUTH.md section 3.6. A review names a real person, usually one who teaches children
 * in their home, and an unmoderated pipeline from a stranger straight onto that person's public
 * profile is a defamation vector before it is a product feature. Everything starts
 * {@code PENDING} and the public read filters in SQL, so an approval is the only path to
 * publication.
 *
 * <h2>Aggregates are recomputed, never incremented</h2>
 *
 * <p>Every status change that could alter what is published calls
 * {@link ReviewRepository#recomputeRatingFor}. See that method for why the counter is derived from
 * the table rather than nudged up and down.
 */
@Service
public class ReviewService {

	private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

	private final ReviewRepository reviews;
	private final LeadUnlockRepository unlocks;
	private final TutorProfileRepository profiles;
	private final StudentProfileRepository students;
	private final NotificationService notifications;
	private final Clock clock;

	public ReviewService(
			ReviewRepository reviews,
			LeadUnlockRepository unlocks,
			TutorProfileRepository profiles,
			StudentProfileRepository students,
			NotificationService notifications,
			Clock clock) {
		this.reviews = reviews;
		this.unlocks = unlocks;
		this.profiles = profiles;
		this.students = students;
		this.notifications = notifications;
		this.clock = clock;
	}

	// --- Student ------------------------------------------------------------------------------

	/**
	 * A student's own review, with enough about the tutor to render a row.
	 *
	 * <p>Carries {@code tutorProfileId}, not the tutor's user id: every tutor link in the frontend
	 * is a {@code tutor_profiles.id}, and handing the client a second kind of tutor identifier
	 * invites the two being confused at exactly the point where the wrong one silently addresses a
	 * different person.
	 */
	public record OwnedReview(Review review, Long tutorProfileId, String tutorName) {
	}

	/**
	 * Writes a review, or revises the pending one that is already there.
	 *
	 * <p>Resubmitting is treated as an edit rather than a duplicate. The unique index makes a
	 * second row impossible anyway, and answering an edit with "you have already reviewed this
	 * tutor" would be technically true and useless — the student is looking at their own words and
	 * wants to change them.
	 */
	@Transactional
	public OwnedReview write(
			Long studentUserId, Long tutorProfileId, int rating, String title, String body) {

		TutorProfile tutor = tutorProfile(tutorProfileId);
		Long tutorUserId = tutor.getUserId();
		requireEligible(studentUserId, tutorUserId);

		Review existing = reviews.findByTutorIdAndStudentId(tutorUserId, studentUserId).orElse(null);
		if (existing != null) {
			if (existing.getStatus() != ModerationStatus.PENDING) {
				throw new ApiException(ErrorCode.REVIEW_ALREADY_EXISTS,
						existing.getStatus() == ModerationStatus.APPROVED
								? "Your review of this tutor is already published."
								: "Your review of this tutor was not approved, so it cannot be "
										+ "rewritten here.");
			}
			return owned(revise(existing, rating, title, body), tutor);
		}

		Review review = reviews.save(Review.write(tutorUserId, studentUserId, rating, title, body));
		log.info("Review submitted: id={} tutor={} student={}",
				review.getId(), tutorUserId, studentUserId);
		return owned(review, tutor);
	}

	/** Edits a pending review the student owns. */
	@Transactional
	public OwnedReview revise(
			Long studentUserId, Long reviewId, int rating, String title, String body) {

		Review review = reviews.findById(reviewId)
				.filter(candidate -> candidate.getStudentId().equals(studentUserId))
				// NOT_FOUND rather than FORBIDDEN: a 403 would confirm the review exists and let
				// someone map out other people's reviews by id.
				.orElseThrow(() -> ApiException.notFound("Review"));

		Review revised = revise(review, rating, title, body);
		return owned(revised, profiles.findByUserId(revised.getTutorId()).orElse(null));
	}

	@Transactional(readOnly = true)
	public List<OwnedReview> writtenBy(Long studentUserId) {
		List<Review> written = reviews.findByStudentIdOrderByCreatedAtDesc(studentUserId);
		if (written.isEmpty()) {
			return List.of();
		}

		// One query for the list rather than one per row.
		Map<Long, TutorProfile> byUserId = profiles
				.findByUserIdIn(written.stream().map(Review::getTutorId).distinct().toList())
				.stream()
				.collect(Collectors.toMap(TutorProfile::getUserId, profile -> profile));

		return written.stream()
				.map(review -> owned(review, byUserId.get(review.getTutorId())))
				.toList();
	}

	/**
	 * Whether this student may review this tutor, for the UI to ask before offering the form.
	 *
	 * <p>Offering a review box and then refusing the submission is a worse experience than not
	 * offering one, and this is the same predicate the write path enforces.
	 */
	@Transactional(readOnly = true)
	public boolean mayReview(Long studentUserId, Long tutorProfileId) {
		Long tutorUserId = profiles.findById(tutorProfileId)
				.map(TutorProfile::getUserId)
				.orElse(null);

		return tutorUserId != null
				&& unlocks.countEngagementsBetween(tutorUserId, studentUserId) > 0
				&& !reviews.existsByTutorIdAndStudentId(tutorUserId, studentUserId);
	}

	// --- Tutor --------------------------------------------------------------------------------

	/** Everything written about this tutor, pending included — it is about them either way. */
	@Transactional(readOnly = true)
	public List<Review> aboutTutor(Long tutorUserId) {
		return reviews.findByTutorIdOrderByCreatedAtDesc(tutorUserId);
	}

	/** The tutor's one public answer. It queues for moderation exactly as the review did. */
	@Transactional
	public Review reply(Long tutorUserId, Long reviewId, String text) {
		Review review = reviews.findById(reviewId)
				.filter(candidate -> candidate.getTutorId().equals(tutorUserId))
				.orElseThrow(() -> ApiException.notFound("Review"));

		try {
			review.reply(text, clock.instant());
		} catch (IllegalStateException e) {
			throw new ApiException(
					review.getTutorReply() != null
							? ErrorCode.REVIEW_ALREADY_REPLIED
							: ErrorCode.CONFLICT,
					e.getMessage());
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
		}

		return reviews.save(review);
	}

	// --- Public -------------------------------------------------------------------------------

	/**
	 * An approved review together with the name to show against it.
	 *
	 * <p>The name is masked at this boundary rather than in the DTO, so there is no path that
	 * builds a public review view holding a full name in the first place.
	 */
	public record PublishedReview(Review review, String reviewerName) {
	}

	/**
	 * The approved reviews on a tutor's public profile page.
	 *
	 * <p>Takes a {@code tutor_profiles.id}, because that is what {@code /api/v1/public/tutors/{id}}
	 * already uses and a page should not need two different ids for the same tutor. Unpublished
	 * profiles are invisible here for the same reason they are invisible in search.
	 *
	 * <p>Reviewers are shown as "Priya S." via {@link ContactMasking#maskName} — the same masking
	 * the lead feed uses. A full name against a review, on a page that also names a locality, is
	 * usually enough to identify a specific family.
	 */
	@Transactional(readOnly = true)
	public List<PublishedReview> publishedForProfile(Long profileId) {
		Long tutorUserId = profiles.findById(profileId)
				.filter(profile -> profile.isPublished())
				.map(profile -> profile.getUserId())
				.orElseThrow(() -> ApiException.notFound("Tutor"));

		List<Review> published = reviews.findByTutorIdAndStatusOrderByCreatedAtDesc(
				tutorUserId, ModerationStatus.APPROVED);
		if (published.isEmpty()) {
			return List.of();
		}

		// One query for the whole page rather than one per reviewer.
		Map<Long, String> names = students
				.findByUserIdIn(published.stream().map(Review::getStudentId).distinct().toList())
				.stream()
				.filter(profile -> profile.getName() != null)
				.collect(Collectors.toMap(StudentProfile::getUserId, StudentProfile::getName));

		return published.stream()
				.map(review -> new PublishedReview(
						review, ContactMasking.maskName(names.get(review.getStudentId()))))
				.toList();
	}
	// --- Moderation ---------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public Page<Review> pendingQueue(Pageable pageable) {
		return reviews.findByStatusOrderByCreatedAtAsc(ModerationStatus.PENDING, pageable);
	}

	@Transactional(readOnly = true)
	public Page<Review> pendingReplyQueue(Pageable pageable) {
		return reviews.findByTutorReplyStatusOrderByTutorReplyAtAsc(
				ModerationStatus.PENDING, pageable);
	}

	@Transactional
	public Review approve(Long reviewId, Long adminUserId) {
		Review review = load(reviewId);
		ModerationStatus previous = review.getStatus();
		apply(() -> review.approve(adminUserId, clock.instant()));
		reviews.save(review);

		auditReview("REVIEW_APPROVED", review, previous.name(), null);

		// Order matters: the aggregate is rewritten from the table, so the row must be flushed
		// first. recomputeRatingFor flushes and then clears the persistence context for exactly
		// this reason.
		reviews.recomputeRatingFor(review.getTutorId());

		notifications.notify(
				review.getTutorId(),
				NotificationType.REVIEW_PUBLISHED,
				"A new %d-star review".formatted(review.getRating()),
				"A student has reviewed you and it is now on your profile. You can post one public "
						+ "reply to it.",
				"REVIEW",
				review.getId());

		log.info("Review APPROVED: id={} tutor={} rating={} by admin={}",
				reviewId, review.getTutorId(), review.getRating(), adminUserId);
		return review;
	}

	@Transactional
	public Review reject(Long reviewId, Long adminUserId, String reason) {
		Review review = load(reviewId);
		ModerationStatus previous = review.getStatus();
		apply(() -> review.reject(adminUserId, reason, clock.instant()));
		reviews.save(review);

		auditReview("REVIEW_REJECTED", review, previous.name(), reason);

		notifications.notify(
				review.getStudentId(),
				NotificationType.REVIEW_REJECTED,
				"Your review was not published",
				reason,
				"REVIEW",
				review.getId());

		log.info("Review REJECTED: id={} tutor={} by admin={}",
				reviewId, review.getTutorId(), adminUserId);
		return review;
	}

	/**
	 * Takes a published review back down, for one reported after the fact.
	 *
	 * <p>The aggregate is recomputed here too. This is the case an incrementing counter gets
	 * wrong: it would need to know the old rating to subtract it, and would be silently wrong
	 * forever if it did not.
	 */
	@Transactional
	public Review unpublish(Long reviewId, Long adminUserId) {
		Review review = load(reviewId);
		ModerationStatus previous = review.getStatus();
		apply(() -> review.unpublish(adminUserId, clock.instant()));
		reviews.save(review);
		reviews.recomputeRatingFor(review.getTutorId());

		auditReview("REVIEW_UNPUBLISHED", review, previous.name(), null);

		log.info("Review WITHDRAWN: id={} tutor={} by admin={}",
				reviewId, review.getTutorId(), adminUserId);
		return review;
	}

	@Transactional
	public Review approveReply(Long reviewId, Long adminUserId) {
		Review review = load(reviewId);
		ModerationStatus previousReply = review.getTutorReplyStatus();
		apply(() -> review.approveReply(adminUserId));
		reviews.save(review);

		auditReply("REVIEW_REPLY_APPROVED", review, previousReply);

		notifications.notify(
				review.getStudentId(),
				NotificationType.REVIEW_REPLY_PUBLISHED,
				"A tutor replied to your review",
				"The tutor you reviewed has posted a public reply.",
				"REVIEW",
				review.getId());

		return review;
	}

	@Transactional
	public Review rejectReply(Long reviewId, Long adminUserId) {
		Review review = load(reviewId);
		ModerationStatus previousReply = review.getTutorReplyStatus();
		apply(() -> review.rejectReply(adminUserId));
		reviews.save(review);

		auditReply("REVIEW_REPLY_REJECTED", review, previousReply);

		return review;
	}

	/**
	 * Rebuilds every tutor's cached rating from the reviews table — {@code M5-04.3}.
	 *
	 * <p>For backfill, and for the day somebody needs to prove the column still matches the rows.
	 */
	@Transactional
	public int recomputeAllRatings() {
		int updated = reviews.recomputeAllRatings();
		log.info("Recomputed rating aggregates for {} tutor profiles", updated);
		return updated;
	}

	// --- Internals ----------------------------------------------------------------------------

	private TutorProfile tutorProfile(Long tutorProfileId) {
		return profiles.findById(tutorProfileId).orElseThrow(() -> ApiException.notFound("Tutor"));
	}

	/** Pairs a review with its tutor's display details. Tolerates a missing profile row. */
	private static OwnedReview owned(Review review, TutorProfile tutor) {
		return new OwnedReview(
				review,
				tutor == null ? null : tutor.getId(),
				tutor == null ? null : tutor.getDisplayName());
	}

	private Review revise(Review review, int rating, String title, String body) {
		try {
			review.reviseWhilePending(rating, title, body);
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.REVIEW_ALREADY_EXISTS, e.getMessage());
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
		}
		return reviews.save(review);
	}

	private void requireEligible(Long studentUserId, Long tutorUserId) {
		if (unlocks.countEngagementsBetween(tutorUserId, studentUserId) == 0) {
			throw new ApiException(ErrorCode.REVIEW_NOT_ELIGIBLE,
					"You can only review a tutor who has responded to one of your enquiries.");
		}
	}

	/**
	 * Records a moderation decision on a review (M5-08.2).
	 *
	 * <p>A helper rather than five copies: five call sites spelling out the same four fields is
	 * five chances for one of them to record the wrong status, and a wrong audit entry is worse
	 * than no audit entry — it is evidence that points somewhere else.
	 */
	private static void auditReview(
			String action, Review review, String previousStatus, String reason) {

		AuditContext.describe(action, "REVIEW", review.getId());
		AuditContext.summarise(reason);
		AuditContext.before(AuditContext.fields("status", previousStatus));
		AuditContext.after(AuditContext.fields(
				"status", review.getStatus().name(),
				"tutorId", review.getTutorId(),
				"rating", review.getRating(),
				"reason", reason));
	}

	/** The same, for the tutor's reply — decided independently of the review it answers. */
	private static void auditReply(
			String action, Review review, ModerationStatus previousReplyStatus) {

		AuditContext.describe(action, "REVIEW", review.getId());
		AuditContext.before(AuditContext.fields(
				"replyStatus", previousReplyStatus == null ? null : previousReplyStatus.name()));
		AuditContext.after(AuditContext.fields(
				"replyStatus",
				review.getTutorReplyStatus() == null ? null : review.getTutorReplyStatus().name(),
				"tutorId", review.getTutorId(),
				// The review's own status, so an entry makes plain that refusing an answer left the
				// criticism published.
				"reviewStatus", review.getStatus().name()));
	}

	private Review load(Long reviewId) {
		return reviews.findById(reviewId).orElseThrow(() -> ApiException.notFound("Review"));
	}

	/** Translates the entity's state guards into the API error shape, in one place. */
	private static void apply(Runnable transition) {
		try {
			transition.run();
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
		}
	}
}
