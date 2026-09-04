package com.apnatutor.user;

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
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.requirement.domain.RequirementStatus;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.user.UserAdminService.SuspensionResult;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import com.apnatutor.user.domain.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Suspending and reinstating accounts - M5-05.3.
 *
 * <p>{@code users.status} has carried {@code SUSPENDED} since V2, and setting it blocks the login
 * path. These tests are about everything setting it does <em>not</em> do on its own: a suspended
 * tutor who is still in search results, or a suspended student whose enquiries tutors keep paying to
 * reach, is a suspension that did not happen.
 */
class UserSuspensionTest extends AbstractIntegrationTest {

	@Autowired
	private UserAdminService admin;

	@Autowired
	private UserRepository users;

	@Autowired
	private RequirementRepository requirements;

	@Autowired
	private LeadUnlockService unlockService;

	@Autowired
	private CreditLedger ledger;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private Clock clock;

	@Autowired
	private TutorProfileRepository profileRepository;

	@Autowired
	private com.apnatutor.search.TutorSearchRepository search;

	@Test
	@DisplayName("suspending a tutor takes them out of search and off their profile page")
	void suspendedTutorsDisappearFromDiscovery() {
		Long tutorId = publishedTutor();
		Long profileId = profileRepository.findByUserId(tutorId).orElseThrow().getId();

		assertThat(appearsInSearch(profileId)).isTrue();
		assertThat(profileRepository.findPublishedActiveById(profileId)).isPresent();

		admin.suspend(tutorId, adminId(), "Sent a parent abusive messages");

		assertThat(appearsInSearch(profileId))
				.as("THE POINT: status alone only blocks login. A blocked tutor still taking "
						+ "enquiries is a suspension that did not happen")
				.isFalse();
		assertThat(profileRepository.findPublishedActiveById(profileId))
				.as("the profile page is the other way in, and it is closed too")
				.isEmpty();

		admin.reinstate(tutorId, adminId());

		assertThat(appearsInSearch(profileId))
				.as("and they come straight back, with no flag anyone had to remember to unset")
				.isTrue();
	}

	@Test
	@DisplayName("suspending a student takes down their live enquiries and refunds the tutors")
	void suspendingAStudentClearsTheirEnquiries() {
		Long studentId = studentId();
		Requirement live = postRequirementFor(studentId);
		Requirement hired = postRequirementFor(studentId);

		Long tutorId = fundedTutor(20);
		unlockService.unlock(live.getId(), tutorId, null);
		assertThat(ledger.balanceOf(tutorId)).isEqualTo(15);

		requirements.findById(hired.getId()).ifPresent(r -> {
			r.markHired();
			requirements.save(r);
		});

		SuspensionResult result = admin.suspend(studentId, adminId(), "Posting fake enquiries");

		assertThat(result.enquiriesRemoved()).isEqualTo(1);
		assertThat(requirements.findById(live.getId()).orElseThrow().getStatus())
				.isEqualTo(RequirementStatus.REMOVED);

		assertThat(ledger.balanceOf(tutorId))
				.as("THE POINT: we would have refunded this one dispute at a time anyway. "
						+ "Continuing to sell an account we just judged fraudulent is worse")
				.isEqualTo(20);

		assertThat(requirements.findById(hired.getId()).orElseThrow().getStatus())
				.as("a hired enquiry is history - rewriting it would refund an introduction "
						+ "that worked")
				.isEqualTo(RequirementStatus.HIRED);
	}

	@Test
	@DisplayName("a suspension carries a reason, and reinstating clears it")
	void suspensionCarriesAReasonAndReinstatingClearsIt() {
		Long tutorId = fundedTutor(0);
		Long adminUserId = adminId();

		assertThatThrownBy(() -> admin.suspend(tutorId, adminUserId, "   "))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_FAILED);

		admin.suspend(tutorId, adminUserId, "Repeated no-shows");

		User suspended = users.findById(tutorId).orElseThrow();
		assertThat(suspended.getStatus()).isEqualTo(UserStatus.SUSPENDED);
		assertThat(suspended.getSuspensionReason()).isEqualTo("Repeated no-shows");
		assertThat(suspended.getSuspendedBy()).isEqualTo(adminUserId);
		assertThat(suspended.canAuthenticate()).isFalse();

		assertThatThrownBy(() -> admin.suspend(tutorId, adminUserId, "Again"))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.CONFLICT);

		admin.reinstate(tutorId, adminUserId);

		User active = users.findById(tutorId).orElseThrow();
		assertThat(active.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(active.getSuspensionReason())
				.as("a stale reason on an active account is how a screen ends up showing "
						+ "'suspended for fraud' next to a user in good standing")
				.isNull();
		assertThat(active.canAuthenticate()).isTrue();
	}

	@Test
	@DisplayName("an admin account cannot be suspended from the console")
	void adminsCannotBeSuspended() {
		Long target = adminId();
		Long actor = adminId();

		assertThatThrownBy(() -> admin.suspend(target, actor, "Testing"))
				.as("suspending the last admin locks everyone out of the console, and the state "
						+ "that gets you there is one misclick in a user list")
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	@Test
	@DisplayName("the user list finds an account by the last digits of its phone")
	void searchMatchesAPhoneFragment() {
		User student = users.save(User.registerVerified(
				"+919111100042", UserRole.STUDENT, clock.instant()));

		assertThat(admin.search(null, null, "1100042", PageRequest.of(0, 20)))
				.as("support takes calls, and a caller reads out the last few digits")
				.extracting(User::getId)
				.contains(student.getId());

		assertThat(admin.search(UserRole.TUTOR, null, "1100042", PageRequest.of(0, 20)))
				.as("the filters combine")
				.isEmpty();
	}

	// --- Fixtures -------------------------------------------------------------------------------

	/**
	 * Whether this specific profile comes back from search.
	 *
	 * <p>Asks about one profile rather than counting rows. Test classes share one database within a
	 * run, so any other test that publishes a tutor would make a global count assertion pass or fail
	 * for reasons that have nothing to do with suspension.
	 */
	private boolean appearsInSearch(Long profileId) {
		return search.search(anyTutor(), 0, 500).stream()
				.anyMatch(row -> ((Number) row[0]).longValue() == profileId);
	}

	/** An all-null query: every published tutor belonging to an active account. */
	private static com.apnatutor.search.dto.SearchDtos.TutorSearchQuery anyTutor() {
		return new com.apnatutor.search.dto.SearchDtos.TutorSearchQuery(
				null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	/**
	 * A published tutor, published past the completeness bar rather than through it.
	 *
	 * <p>{@code publish(at, 0)} deliberately skips the 60% requirement. This test is about who can
	 * be seen, not about what makes a profile good enough to show, and building a complete profile
	 * here would couple it to every future change in the scoring rules.
	 */
	private Long publishedTutor() {
		User tutor = users.save(User.registerVerified(
				uniquePhone("9183"), UserRole.TUTOR, clock.instant()));

		com.apnatutor.user.domain.TutorProfile profile =
				com.apnatutor.user.domain.TutorProfile.createFor(tutor.getId());
		profile.publish(clock.instant(), 0);
		profileRepository.save(profile);

		return tutor.getId();
	}

	private Requirement postRequirementFor(Long studentId) {
		return requirements.save(Requirement.post(
				studentId, leafSubjectId(), null, null, null,
				"ONLINE", 300_000L, "PER_MONTH", null, null, null,
				"Suspension test requirement",
				5, 5,
				clock.instant().plus(Duration.ofDays(30))));
	}

	private Long leafSubjectId() {
		return subjects.findByLeafTrueAndActiveTrueOrderByDisplayOrderAsc().getFirst().getId();
	}

	private Long studentId() {
		return users.save(User.registerVerified(
				uniquePhone("9182"), UserRole.STUDENT, clock.instant())).getId();
	}

	private Long fundedTutor(int credits) {
		User tutor = users.save(User.registerVerified(
				uniquePhone("9181"), UserRole.TUTOR, clock.instant()));
		if (credits > 0) {
			ledger.grant(tutor.getId(), credits, CreditReason.ADMIN_ADJUSTMENT, "TEST", null, null);
		}
		return tutor.getId();
	}

	private Long adminId() {
		return users.save(User.registerVerified(
				uniquePhone("9180"), UserRole.ADMIN, clock.instant())).getId();
	}

	/** Keeps phone numbers unique without a shared sequence between tests. */
	private static final List<String> ISSUED = new ArrayList<>();

	private static synchronized String uniquePhone(String prefix) {
		String phone;
		do {
			phone = "+91" + prefix + (100_000 + (int) (Math.random() * 899_999));
		} while (ISSUED.contains(phone));
		ISSUED.add(phone);
		return phone;
	}
}
