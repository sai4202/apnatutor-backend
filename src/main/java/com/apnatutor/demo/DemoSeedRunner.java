package com.apnatutor.demo;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.apnatutor.billing.CreditLedger;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.common.config.DevAccountSeeder;
import com.apnatutor.catalog.BoardRepository;
import com.apnatutor.catalog.GradeLevelRepository;
import com.apnatutor.catalog.LocationRepository;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.catalog.domain.Board;
import com.apnatutor.catalog.domain.GradeLevel;
import com.apnatutor.catalog.domain.Location;
import com.apnatutor.catalog.domain.Subject;
import com.apnatutor.demo.DemoData.DemoRequirement;
import com.apnatutor.demo.DemoData.DemoReview;
import com.apnatutor.demo.DemoData.DemoTutor;
import com.apnatutor.lead.LeadUnlockService;
import com.apnatutor.requirement.RequirementService;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.requirement.dto.RequirementDtos.PostRequirementRequest;
import com.apnatutor.review.ReviewService;
import com.apnatutor.user.StudentProfileRepository;
import com.apnatutor.user.TutorProfileRepository;
import com.apnatutor.user.TutorProfileService;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.FeeUnit;
import com.apnatutor.user.domain.StudentProfile;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import com.apnatutor.user.dto.TutorProfileDtos.TutorSubjectRequest;
import com.apnatutor.user.dto.TutorProfileDtos.UpdateBasicsRequest;
import com.apnatutor.user.dto.TutorProfileDtos.UpdateFeesRequest;
import com.apnatutor.user.dto.TutorProfileDtos.UpdateSubjectsRequest;
import com.apnatutor.user.dto.TutorProfileDtos.UpdateTeachingRequest;
import com.apnatutor.verification.VerificationRepository;
import com.apnatutor.verification.domain.Verification;
import com.apnatutor.verification.domain.VerificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the demo dataset through the real services — {@code M6-04}.
 *
 * <p>See {@link DemoDataSeeder} for why this is a separate bean and why nothing here uses SQL.
 */
public class DemoSeedRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

	private static final int DEMO_CREDITS = 200;

	private final UserRepository users;
	private final StudentProfileRepository studentProfiles;
	private final TutorProfileRepository tutorProfiles;
	private final TutorProfileService profiles;
	private final SubjectRepository subjects;
	private final LocationRepository locations;
	private final GradeLevelRepository grades;
	private final BoardRepository boards;
	private final RequirementService requirements;
	private final LeadUnlockService unlocks;
	private final ReviewService reviews;
	private final VerificationRepository verifications;
	private final CreditLedger ledger;
	private final Clock clock;

	DemoSeedRunner(
			UserRepository users,
			StudentProfileRepository studentProfiles,
			TutorProfileRepository tutorProfiles,
			TutorProfileService profiles,
			SubjectRepository subjects,
			LocationRepository locations,
			GradeLevelRepository grades,
			BoardRepository boards,
			RequirementService requirements,
			LeadUnlockService unlocks,
			ReviewService reviews,
			VerificationRepository verifications,
			CreditLedger ledger,
			Clock clock) {
		this.users = users;
		this.studentProfiles = studentProfiles;
		this.tutorProfiles = tutorProfiles;
		this.profiles = profiles;
		this.subjects = subjects;
		this.locations = locations;
		this.grades = grades;
		this.boards = boards;
		this.requirements = requirements;
		this.unlocks = unlocks;
		this.reviews = reviews;
		this.verifications = verifications;
		this.ledger = ledger;
		this.clock = clock;
	}

	@Transactional
	public void run() {
		// Idempotent on the first tutor's phone. Re-running the app with the flag on must not
		// double the dataset or fail on the unique phone index.
		// Funded first, and outside the idempotency check below. The end-to-end money path signs
		// in as the dev tutor, and re-running the seeder to top them up must work even though the
		// dataset itself is already there.
		fundDevAccounts();

		if (users.existsByPhone(DemoData.TUTORS.getFirst().phone())) {
			log.info("Demo data already present — funded the dev accounts and stopped.");
			return;
		}

		Map<String, Long> subjectIds = indexBySlug(subjects.findAll(), Subject::getSlug, Subject::getId);
		Map<String, Long> locationIds = indexBySlug(locations.findAll(), Location::getSlug, Location::getId);
		Map<String, Long> gradeIds = indexBySlug(grades.findAll(), GradeLevel::getSlug, GradeLevel::getId);
		Map<String, Long> boardIds = indexBySlug(boards.findAll(), Board::getSlug, Board::getId);

		DemoData.TUTORS.forEach(tutor -> createTutor(tutor, subjectIds, locationIds));

		List<Requirement> posted = DemoData.REQUIREMENTS.stream()
				.map(requirement -> postRequirement(requirement, subjectIds, locationIds, gradeIds, boardIds))
				.toList();

		connectAndReview(posted);

		log.warn("""

				  ┌──────────────────────────────────────────────────────────────
				  │ DEMO DATA LOADED — {} tutors, {} enquiries, {} reviews
				  │
				  │ Every tutor signs in with their own number and the dev OTP.
				  │ Fictional people. Never load this into a real database.
				  └──────────────────────────────────────────────────────────────
				""", DemoData.TUTORS.size(), posted.size(), DemoData.REVIEWS.size());
	}

	/**
	 * Gives the seeded dev tutor credits.
	 *
	 * <p>Not cosmetic. The end-to-end money-path test signs in as this account and unlocks a lead;
	 * with a zero balance it can only skip, and a test that always skips is worse than no test
	 * because it reports green. The demo dataset is the fixture that test runs against, so the
	 * fixture is the right place to make it runnable.
	 */
	private void fundDevAccounts() {
		users.findByPhone(DevAccountSeeder.TUTOR_PHONE).ifPresent(tutor -> {
			if (ledger.balanceOf(tutor.getId()) == 0) {
				ledger.grant(tutor.getId(), DEMO_CREDITS, CreditReason.ADMIN_ADJUSTMENT,
						"DEMO_SEED", null, null);
			}
		});
	}

	/**
	 * Builds one tutor all the way to published.
	 *
	 * <p>Through {@link TutorProfileService}, so the completeness bar is enforced rather than
	 * bypassed. A demo tutor that could not have published themselves is a demo that lies.
	 */
	private void createTutor(
			DemoTutor tutor, Map<String, Long> subjectIds, Map<String, Long> locationIds) {

		User account = users.save(
				User.registerVerified(tutor.phone(), UserRole.TUTOR, clock.instant()));
		Long userId = account.getId();

		profiles.updateBasics(userId, new UpdateBasicsRequest(
				tutor.displayName(), tutor.headline(), tutor.bio(), null, null,
				tutor.experienceYears(), List.of("English", "Hindi", "Telugu"),
				tutor.offersDemo(), "Weekday evenings and weekend mornings"));

		profiles.updateFees(userId, new UpdateFeesRequest(
				tutor.feeMinPaise(), tutor.feeMaxPaise(), FeeUnit.PER_MONTH, true));

		profiles.updateTeaching(userId, new UpdateTeachingRequest(
				tutor.teachingModes(),
				tutor.localitySlugs().isEmpty() ? 0 : 8,
				tutor.localitySlugs().stream().map(locationIds::get).filter(id -> id != null).toList()));

		profiles.updateSubjects(userId, new UpdateSubjectsRequest(
				tutor.subjectSlugs().stream()
						.map(subjectIds::get)
						.filter(id -> id != null)
						.map(id -> new TutorSubjectRequest(id, null, null, List.of(), List.of()))
						.toList()));

		if (tutor.idVerified()) {
			// Approved directly rather than through the admin queue: the queue is a screen worth
			// having demo data IN, so the unverified tutors are the interesting ones there.
			Verification verification = Verification.submit(
					userId, VerificationType.ID, "id-documents/demo-placeholder.jpg");
			verification.approve(userId, clock.instant());
			verifications.save(verification);
		}

		profiles.publish(userId);
		ledger.grant(userId, DEMO_CREDITS, CreditReason.ADMIN_ADJUSTMENT, "DEMO_SEED", null, null);
	}

	private Requirement postRequirement(
			DemoRequirement demo,
			Map<String, Long> subjectIds,
			Map<String, Long> locationIds,
			Map<String, Long> gradeIds,
			Map<String, Long> boardIds) {

		User student = users.findByPhone(demo.studentPhone()).orElseGet(() -> {
			User created = users.save(
					User.registerVerified(demo.studentPhone(), UserRole.STUDENT, clock.instant()));
			studentProfiles.save(StudentProfile.createFor(created.getId()));
			return created;
		});

		return requirements.post(student.getId(), new PostRequirementRequest(
				subjectIds.get(demo.subjectSlug()),
				demo.gradeSlug() == null ? null : gradeIds.get(demo.gradeSlug()),
				demo.boardSlug() == null ? null : boardIds.get(demo.boardSlug()),
				demo.localitySlug() == null ? null : locationIds.get(demo.localitySlug()),
				demo.mode(),
				demo.budgetPaise(),
				"PER_MONTH",
				"3 days a week",
				"Weekday evenings",
				"ANY",
				demo.description()));
	}

	/**
	 * Has tutors unlock the enquiries they match, then writes the reviews.
	 *
	 * <p>Review eligibility requires a real, active unlock between that tutor and that student
	 * (SOURCE_OF_TRUTH §3.6), so the connection has to happen first and has to be genuine. That is
	 * the constraint that makes this method fiddlier than inserting rows would be, and it is also
	 * the reason the resulting dataset is worth having: the ratings on the demo profiles are backed
	 * by the same evidence a real one would be.
	 */
	private void connectAndReview(List<Requirement> posted) {
		Map<String, Requirement> byStudentPhone = posted.stream().collect(
				java.util.stream.Collectors.toMap(
						requirement -> users.findById(requirement.getStudentId())
								.map(User::getPhone)
								.orElseThrow(),
						Function.identity(),
						(first, second) -> first));

		for (DemoReview review : DemoData.REVIEWS) {
			Requirement requirement = byStudentPhone.get(review.studentPhone());
			User tutor = users.findByPhone(review.tutorPhone()).orElse(null);
			if (requirement == null || tutor == null) {
				continue;
			}

			unlocks.unlock(requirement.getId(), tutor.getId(),
					"Happy to help — I teach exactly this and have slots on weekday evenings.");

			Long profileId = tutorProfiles.findByUserId(tutor.getId()).orElseThrow().getId();
			var written = reviews.write(
					requirement.getStudentId(), profileId,
					review.rating(), review.title(), review.body());

			// Published, so the demo shows ratings rather than an empty moderation queue. The
			// unverified tutors' profiles are left without reviews, which is also realistic.
			reviews.approve(written.review().getId(), tutor.getId());
		}
	}

	private static <T> Map<String, Long> indexBySlug(
			List<T> rows, Function<T, String> slug, Function<T, Long> id) {

		return rows.stream().collect(
				java.util.stream.Collectors.toMap(slug, id, (first, second) -> first));
	}
}
