package com.apnatutor.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.apnatutor.billing.CreditLedger;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.lead.LeadUnlockService;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.domain.Requirement;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the public actually receives — {@code M5-02.1}, and the payoff for {@code M5-04}.
 *
 * <p>The service tests prove the rules; this proves the wire. A review that is correctly marked
 * {@code PENDING} in the database but serialised onto a public page anyway is the failure that
 * matters, and it is only visible from out here.
 *
 * <p>The last test is the one that justifies doing reviews before the admin console:
 * {@code avg_rating} has been indexed and sortable since M2 with nothing to write it, so every
 * tutor tied at NULL. These assertions fail against the code as it stood before this milestone.
 */
@AutoConfigureMockMvc
class ReviewApiTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ReviewService reviews;

	@Autowired
	private LeadUnlockService unlockService;

	@Autowired
	private RequirementRepository requirements;

	@Autowired
	private TutorProfileRepository tutorProfiles;

	@Autowired
	private StudentProfileRepository studentProfiles;

	@Autowired
	private CreditLedger ledger;

	@Autowired
	private UserRepository users;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private Clock clock;

	@Test
	@DisplayName("a pending review is absent from the public endpoint entirely")
	void pendingReviewsAreNotServed() throws Exception {
		Long tutorId = newUser(UserRole.TUTOR);
		Long profileId = publishedProfileFor(tutorId);
		writeReview(tutorId, "Priya Sharma", 1, "Awful", "Do not book this tutor.");

		// Not "present but flagged" — absent. Anything that reaches the client can be rendered by
		// a client that does not know it should not be.
		mockMvc.perform(get("/api/v1/public/tutors/{id}/reviews", profileId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("an approved review is served with a masked reviewer name")
	void approvedReviewsAreServedMasked() throws Exception {
		Long tutorId = newUser(UserRole.TUTOR);
		Long profileId = publishedProfileFor(tutorId);
		Review review = writeReview(tutorId, "Priya Sharma", 5, "Excellent", "Very patient.");
		reviews.approve(review.getId(), adminId());

		mockMvc.perform(get("/api/v1/public/tutors/{id}/reviews", profileId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].rating").value(5))
				.andExpect(jsonPath("$[0].title").value("Excellent"))
				// A full name against a review, on a page that also names a locality, is usually
				// enough to identify a specific family.
				.andExpect(jsonPath("$[0].reviewerName").value("Priya S."))
				.andExpect(jsonPath("$[0].studentId").doesNotExist())
				.andExpect(jsonPath("$[0].status").doesNotExist());
	}

	@Test
	@DisplayName("a tutor reply appears only after it has been approved too")
	void repliesAreModeratedBeforeTheyAreServed() throws Exception {
		Long tutorId = newUser(UserRole.TUTOR);
		Long profileId = publishedProfileFor(tutorId);
		Review review = writeReview(tutorId, "Anil Kumar", 2, "Late", "Missed two classes.");
		reviews.approve(review.getId(), adminId());
		reviews.reply(tutorId, review.getId(), "I was unwell and offered to make both up.");

		mockMvc.perform(get("/api/v1/public/tutors/{id}/reviews", profileId))
				.andExpect(jsonPath("$[0].tutorReply").doesNotExist());

		reviews.approveReply(review.getId(), adminId());

		mockMvc.perform(get("/api/v1/public/tutors/{id}/reviews", profileId))
				.andExpect(jsonPath("$[0].tutorReply")
						.value("I was unwell and offered to make both up."));
	}

	@Test
	@DisplayName("reviews on an unpublished profile are not served")
	void unpublishedProfilesHaveNoPublicReviews() throws Exception {
		Long tutorId = newUser(UserRole.TUTOR);
		TutorProfile profile = tutorProfiles.save(TutorProfile.createFor(tutorId));
		Review review = writeReview(tutorId, "Ravi Menon", 5, "Great", "Recommended.");
		reviews.approve(review.getId(), adminId());

		// Consistent with search: a draft profile is invisible, and so is everything hanging off it.
		mockMvc.perform(get("/api/v1/public/tutors/{id}/reviews", profile.getId()))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("search can finally discriminate on rating")
	void searchSortsAndFiltersOnRealRatings() throws Exception {
		Long excellent = newUser(UserRole.TUTOR);
		Long profileOfExcellent = publishedProfileFor(excellent, "Meera Excellent");
		approve(writeReview(excellent, "Student One", 5, "Superb", "Outstanding."));

		Long adequate = newUser(UserRole.TUTOR);
		publishedProfileFor(adequate, "Sunil Adequate");
		approve(writeReview(adequate, "Student Two", 2, "Adequate", "Often late."));

		// minRating has existed since M2 and could not exclude anybody, because every avg_rating
		// was NULL. It excludes somebody now.
		mockMvc.perform(get("/api/v1/public/tutors")
						.param("minRating", "4")
						.param("size", "50"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.displayName == 'Meera Excellent')]").exists())
				.andExpect(jsonPath("$.content[?(@.displayName == 'Sunil Adequate')]")
						.doesNotExist());

		mockMvc.perform(get("/api/v1/public/tutors")
						.param("sort", "RATING")
						.param("size", "50"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(profileOfExcellent.intValue()))
				.andExpect(jsonPath("$.content[0].avgRating").value(5.0))
				.andExpect(jsonPath("$.content[0].reviewCount").value(1));
	}

	// --- Helpers ------------------------------------------------------------------------------

	private Review approve(Review review) {
		return reviews.approve(review.getId(), adminId());
	}

	/** A student connected to the tutor by a paid unlock, who then writes a review. */
	private Review writeReview(Long tutorId, String studentName, int rating, String title,
			String body) {

		Long studentId = newUser(UserRole.STUDENT);
		StudentProfile student = StudentProfile.createFor(studentId);
		student.update(studentName, null);
		studentProfiles.save(student);

		Requirement requirement = requirements.save(Requirement.post(
				studentId, anySubjectId(), null, null, null,
				"ONLINE", 300_000L, "PER_MONTH", null, null, null,
				"Review API test enquiry",
				2,
				5,
				clock.instant().plus(Duration.ofDays(30))));

		ledger.grant(tutorId, 20, CreditReason.ADMIN_ADJUSTMENT, "TEST", null, null);
		unlockService.unlock(requirement.getId(), tutorId, null);

		return reviews.write(studentId, profileIdOf(tutorId), rating, title, body).review();
	}

	private Long profileIdOf(Long tutorId) {
		return tutorProfiles.findByUserId(tutorId).orElseThrow().getId();
	}

	private Long publishedProfileFor(Long tutorId) {
		return publishedProfileFor(tutorId, null);
	}

	private Long publishedProfileFor(Long tutorId, String displayName) {
		TutorProfile profile = TutorProfile.createFor(tutorId);
		if (displayName != null) {
			profile.updateBasics(displayName, "Experienced tutor", null, null, null, 5,
					new String[0], false, null);
		}
		// No completeness bar: this class tests reviews, not the publishing rules.
		profile.publish(clock.instant(), 0);
		return tutorProfiles.save(profile).getId();
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
