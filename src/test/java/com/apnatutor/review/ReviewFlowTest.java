package com.apnatutor.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.apnatutor.billing.CreditLedger;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.lead.LeadUnlockService;
import com.apnatutor.notification.NotificationRepository;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.review.domain.ModerationStatus;
import com.apnatutor.review.domain.Review;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.user.StudentProfileRepository;
import com.apnatutor.user.TutorProfileRepository;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.StudentProfile;
import com.apnatutor.user.domain.TutorProfile;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reviews, moderation and rating aggregation — {@code M5-01} to {@code M5-04}.
 *
 * <p>Two properties here are worth more than the rest. The first is that nothing user-written
 * reaches a public read before a moderator approves it: a review names a real person who teaches
 * children in their home, and an unmoderated path onto their profile is a defamation vector.
 *
 * <p>The second is that the cached aggregate is rewritten from the table on every change of status,
 * in both directions. {@code tutor_profiles.avg_rating} has been indexed and sorted on since M2;
 * an approval that failed to update it would leave search ranking on a stale number with nothing to
 * indicate anything was wrong.
 */
class ReviewFlowTest extends AbstractIntegrationTest {

	@Autowired
	private ReviewService reviews;

	@Autowired
	private ReviewRepository reviewRepository;

	@Autowired
	private LeadUnlockService unlockService;

	@Autowired
	private RequirementRepository requirements;

	@Autowired
	private TutorProfileRepository tutorProfiles;

	@Autowired
	private StudentProfileRepository studentProfiles;

	@Autowired
	private NotificationRepository notifications;

	@Autowired
	private CreditLedger ledger;

	@Autowired
	private UserRepository users;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private Clock clock;

	/** For reading the derived aggregate columns directly, and for simulating drift. */
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("the whole path: unlock, review, moderate, publish, rate, reply")
	void endToEnd() {
		Fixture f = connectedPair();

		Review review = reviews
				.write(f.studentId, f.profileId, 5, "Excellent", "Patient and clear.")
				.review();
		assertThat(review.getStatus()).isEqualTo(ModerationStatus.PENDING);

		assertThat(reviews.publishedForProfile(f.profileId))
				.as("a pending review is not public")
				.isEmpty();
		assertThat(ratingOf(f.tutorId))
				.as("and it counts for nothing until it is")
				.isNull();

		reviews.approve(review.getId(), adminId());

		assertThat(reviews.publishedForProfile(f.profileId)).hasSize(1);
		assertThat(ratingOf(f.tutorId)).isEqualTo("5.0");
		assertThat(reviewCountOf(f.tutorId)).isEqualTo(1);

		assertThat(notificationTypesFor(f.tutorId))
				.as("the tutor is told, so they can reply")
				.contains(NotificationType.REVIEW_PUBLISHED);

		reviews.reply(f.tutorId, review.getId(), "Thank you, it was a pleasure teaching Anya.");
		assertThat(reviews.publishedForProfile(f.profileId).getFirst().review().hasPublishedReply())
				.as("a reply is moderated too, so it is not public yet")
				.isFalse();

		reviews.approveReply(review.getId(), adminId());
		assertThat(reviews.publishedForProfile(f.profileId).getFirst().review().hasPublishedReply())
				.isTrue();

		// One public answer per review. A tutor who can keep replying turns a review into a thread
		// they always get the last word in.
		assertThatThrownBy(() -> reviews.reply(f.tutorId, review.getId(), "One more thing"))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.REVIEW_ALREADY_REPLIED);
	}

	@Test
	@DisplayName("a student who was never connected to the tutor cannot review them")
	void eligibilityRequiresAnEngagement() {
		Fixture f = connectedPair();
		Long stranger = newUser(UserRole.STUDENT);

		// The whole credibility of the rating rests on this. Without it anyone with an account can
		// rate any tutor, and the number stops meaning anything at all.
		assertThatThrownBy(() -> reviews.write(stranger, f.profileId, 1, "Terrible", "Never met them"))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.REVIEW_NOT_ELIGIBLE);

		assertThat(reviews.mayReview(stranger, f.profileId)).isFalse();
		assertThat(reviews.mayReview(f.studentId, f.profileId)).isTrue();
	}

	@Test
	@DisplayName("a resubmission edits the pending review rather than adding a second")
	void resubmissionEditsWhilePending() {
		Fixture f = connectedPair();

		Review first = reviews.write(f.studentId, f.profileId, 2, "Not great", "Often late.").review();
		Review second = reviews.write(f.studentId, f.profileId, 4, "Better", "They improved.").review();

		assertThat(second.getId())
				.as("the same row, revised — one review per pair")
				.isEqualTo(first.getId());
		assertThat(second.getRating()).isEqualTo((short) 4);
		assertThat(reviewRepository.findByStudentIdOrderByCreatedAtDesc(f.studentId)).hasSize(1);
	}

	@Test
	@DisplayName("a published review cannot be rewritten by its author")
	void cannotRewriteAfterPublication() {
		Fixture f = connectedPair();
		Review review = reviews.write(f.studentId, f.profileId, 5, "Great", "Very good.").review();
		reviews.approve(review.getId(), adminId());

		// The hole that would make the whole queue decorative: submit something acceptable, get it
		// approved, then edit it into whatever you wanted to say in the first place.
		assertThatThrownBy(() -> reviews.write(f.studentId, f.profileId, 1, "Actually", "Awful."))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS);

		assertThat(ratingOf(f.tutorId)).isEqualTo("5.0");
	}

	@Test
	@DisplayName("a rejected review is never public and the student is told why")
	void rejectionIsExplained() {
		Fixture f = connectedPair();
		Review review = reviews
				.write(f.studentId, f.profileId, 1, "Bad", "Contains a phone number.")
				.review();

		reviews.reject(review.getId(), adminId(), "Please remove the phone number and resubmit.");

		assertThat(reviews.publishedForProfile(f.profileId)).isEmpty();
		assertThat(ratingOf(f.tutorId)).isNull();
		assertThat(reviewCountOf(f.tutorId)).isZero();

		assertThat(notificationTypesFor(f.studentId))
				.as("silence is indistinguishable from a bug")
				.contains(NotificationType.REVIEW_REJECTED);
	}

	@Test
	@DisplayName("a rejection must carry a reason")
	void rejectionNeedsAReason() {
		Fixture f = connectedPair();
		Review review = reviews.write(f.studentId, f.profileId, 3, "Fine", "It was fine.").review();

		assertThatThrownBy(() -> reviews.reject(review.getId(), adminId(), "   "))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_FAILED);
	}

	// --- Aggregation --------------------------------------------------------------------------

	@Test
	@DisplayName("the average is the mean of approved reviews only")
	void averageCountsApprovedOnly() {
		Long tutorId = newUser(UserRole.TUTOR);
		Long profileId = publishedProfileFor(tutorId);

		approveReviewFrom(tutorId, 5);
		approveReviewFrom(tutorId, 4);

		// A third, left pending. It must not move the average.
		Fixture pending = connectedPairFor(tutorId, profileId);
		reviews.write(pending.studentId, pending.profileId, 1, "Pending", "Not moderated yet.");

		assertThat(ratingOf(tutorId)).isEqualTo("4.5");
		assertThat(reviewCountOf(tutorId)).isEqualTo(2);
	}

	@Test
	@DisplayName("withdrawing a published review moves the average back down")
	void withdrawalRecomputesDownwards() {
		Long tutorId = newUser(UserRole.TUTOR);
		publishedProfileFor(tutorId);

		Review five = approveReviewFrom(tutorId, 5);
		approveReviewFrom(tutorId, 3);
		assertThat(ratingOf(tutorId)).isEqualTo("4.0");

		reviews.unpublish(five.getId(), adminId());

		// The case an incrementing counter gets wrong: to undo it you must know the old rating and
		// subtract it, and if you get that wrong the column is silently wrong forever. Recomputing
		// from the table cannot drift, in either direction.
		assertThat(ratingOf(tutorId)).isEqualTo("3.0");
		assertThat(reviewCountOf(tutorId)).isEqualTo(1);
	}

	@Test
	@DisplayName("withdrawing the only review returns the tutor to unrated, not to zero")
	void lastWithdrawalClearsTheAverage() {
		Long tutorId = newUser(UserRole.TUTOR);
		publishedProfileFor(tutorId);

		Review only = approveReviewFrom(tutorId, 4);
		reviews.unpublish(only.getId(), adminId());

		// NULL, not 0.0. Search sorts on this column with NULLS LAST, so a zero would rank an
		// unrated tutor below every one-star tutor on the platform.
		assertThat(ratingOf(tutorId)).isNull();
		assertThat(reviewCountOf(tutorId)).isZero();
	}

	@Test
	@DisplayName("recomputing every rating is a no-op when nothing has drifted")
	void recomputeAllIsIdempotent() {
		Long tutorId = newUser(UserRole.TUTOR);
		publishedProfileFor(tutorId);
		approveReviewFrom(tutorId, 5);
		approveReviewFrom(tutorId, 2);

		String before = ratingOf(tutorId);
		reviews.recomputeAllRatings();
		reviews.recomputeAllRatings();

		assertThat(ratingOf(tutorId)).isEqualTo(before).isEqualTo("3.5");
		assertThat(reviewCountOf(tutorId)).isEqualTo(2);
	}

	@Test
	@DisplayName("recomputing repairs an aggregate that has been corrupted")
	void recomputeAllRepairsDrift() {
		Long tutorId = newUser(UserRole.TUTOR);
		publishedProfileFor(tutorId);
		approveReviewFrom(tutorId, 4);

		// Simulate the drift the derived-not-incremented rule exists to make impossible. Raw SQL
		// on purpose: there is no setter for these columns, and adding one so a test could reach
		// them would open the exact door M5-04.2 closes.
		jdbc.update("UPDATE tutor_profiles SET avg_rating = 1.0, review_count = 99 "
				+ "WHERE user_id = ?", tutorId);

		reviews.recomputeAllRatings();

		assertThat(ratingOf(tutorId)).isEqualTo("4.0");
		assertThat(reviewCountOf(tutorId)).isEqualTo(1);
	}

	// --- Helpers ------------------------------------------------------------------------------

	/** A student and a tutor who have actually been connected by a paid unlock. */
	private record Fixture(Long studentId, Long tutorId, Long profileId) {
	}

	private Fixture connectedPair() {
		Long tutorId = newUser(UserRole.TUTOR);
		return connectedPairFor(tutorId, publishedProfileFor(tutorId));
	}

	/** Another student, connected to an existing tutor, so one tutor can collect several reviews. */
	private Fixture connectedPairFor(Long tutorId, Long profileId) {
		Long studentId = newUser(UserRole.STUDENT);
		studentProfiles.save(namedStudent(studentId, "Priya Sharma"));

		Requirement requirement = requirements.save(Requirement.post(
				studentId, anySubjectId(), null, null, null,
				"ONLINE", 300_000L, "PER_MONTH", null, null, null,
				"Review test enquiry",
				2,
				5,
				clock.instant().plus(Duration.ofDays(30))));

		ledger.grant(tutorId, 20, CreditReason.ADMIN_ADJUSTMENT, "TEST", null, null);
		unlockService.unlock(requirement.getId(), tutorId, null);

		return new Fixture(studentId, tutorId, profileId);
	}

	/** Writes a review from a fresh student and approves it, returning the published review. */
	private Review approveReviewFrom(Long tutorId, int rating) {
		Fixture f = connectedPairFor(tutorId, profileIdOf(tutorId));
		Review review = reviews
				.write(f.studentId, f.profileId, rating, "Review", "Body text.")
				.review();
		reviews.approve(review.getId(), adminId());
		return review;
	}

	private Long profileIdOf(Long tutorId) {
		return tutorProfiles.findByUserId(tutorId).orElseThrow().getId();
	}

	private Long publishedProfileFor(Long tutorId) {
		TutorProfile profile = TutorProfile.createFor(tutorId);
		// Published with no completeness bar: this class is testing reviews, not the publishing
		// rules, which TutorProfileIntegrationTest already covers.
		profile.publish(clock.instant(), 0);
		return tutorProfiles.save(profile).getId();
	}

	private StudentProfile namedStudent(Long userId, String name) {
		StudentProfile profile = StudentProfile.createFor(userId);
		profile.update(name, null);
		return profile;
	}

	private String ratingOf(Long tutorUserId) {
		return jdbc.queryForObject(
				"SELECT avg_rating::text FROM tutor_profiles WHERE user_id = ?",
				String.class, tutorUserId);
	}

	private int reviewCountOf(Long tutorUserId) {
		Integer count = jdbc.queryForObject(
				"SELECT review_count FROM tutor_profiles WHERE user_id = ?",
				Integer.class, tutorUserId);
		return count == null ? 0 : count;
	}

	private List<NotificationType> notificationTypesFor(Long userId) {
		return notifications.findByUserIdOrderByCreatedAtDesc(userId).stream()
				.map(notification -> notification.getType())
				.toList();
	}

	private Long anySubjectId() {
		return subjects.findByLeafTrueAndActiveTrueOrderByDisplayOrderAsc().getFirst().getId();
	}

	private Long newUser(UserRole role) {
		return users.save(User.registerVerified(uniquePhone(), role, clock.instant())).getId();
	}

	private Long adminId() {
		return newUser(UserRole.ADMIN);
	}

	/** Keeps phone numbers unique without a shared sequence between tests. */
	private static final List<String> ISSUED = new ArrayList<>();

	private static synchronized String uniquePhone() {
		String phone;
		do {
			phone = "+919" + (100_000_000 + (int) (Math.random() * 899_999_999));
		} while (ISSUED.contains(phone));
		ISSUED.add(phone);
		return phone;
	}
}
