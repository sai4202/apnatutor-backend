package com.apnatutor.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.apnatutor.user.domain.FeeUnit;
import com.apnatutor.user.domain.TutorLocation;
import com.apnatutor.user.domain.TutorProfile;
import com.apnatutor.user.domain.TutorQualification;
import com.apnatutor.user.domain.TutorSubject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests — the scorer takes a profile and returns a number, with no database involved.
 *
 * <p>Worth testing carefully because the score gates publishing: too lenient and empty profiles
 * reach parents, too strict and tutors cannot get listed at all.
 */
class ProfileCompletenessTest {

	private static TutorProfile emptyProfile() {
		return TutorProfile.createFor(1L);
	}

	/** A profile with everything a tutor could reasonably be asked for. */
	private static TutorProfile fullProfile() {
		TutorProfile profile = emptyProfile();
		profile.updateBasics(
				"Ananya Reddy",
				"Physics & Maths, Classes 9-12",
				"I teach Physics and Mathematics to students in Classes 9 to 12, focusing on "
						+ "building intuition before formulas so the equations stop feeling arbitrary.",
				null,
				null,
				8,
				new String[] { "English", "Telugu" },
				true,
				"Weekday evenings");
		profile.updateFees(450000L, 600000L, FeeUnit.PER_MONTH, true);
		profile.updateTeaching(new String[] { "STUDENT_HOME", "ONLINE" }, 8);
		profile.setPhotoUrl("/uploads/photo.jpg");
		profile.getSubjects().add(new TutorSubject(profile, 1L, 450000L, FeeUnit.PER_MONTH, null, null));
		profile.getLocations().add(new TutorLocation(profile, 1L));
		profile.getQualifications().add(
				new TutorQualification(profile, "M.Sc. Physics", "University of Hyderabad", 2016));
		return profile;
	}

	@Test
	@DisplayName("an empty profile scores zero")
	void emptyProfileScoresZero() {
		assertThat(ProfileCompleteness.score(emptyProfile())).isZero();
	}

	@Test
	@DisplayName("a fully filled profile scores 100")
	void fullProfileScoresFull() {
		assertThat(ProfileCompleteness.score(fullProfile())).isEqualTo(100);
	}

	@Test
	@DisplayName("an empty profile cannot be published")
	void emptyProfileCannotPublish() {
		assertThat(ProfileCompleteness.score(emptyProfile()))
				.isLessThan(ProfileCompleteness.MINIMUM_TO_PUBLISH);
	}

	@Test
	@DisplayName("the essentials alone are enough to publish — a bio and photo are not required")
	void essentialsAreEnoughToPublish() {
		// Subjects, location, modes and fees. This is the minimum at which a tutor can actually be
		// matched to a requirement, so it must clear the bar; demanding a polished bio as well
		// would block tutors who are ready to teach.
		TutorProfile profile = emptyProfile();
		profile.updateTeaching(new String[] { "STUDENT_HOME" }, 5);
		profile.updateFees(300000L, null, FeeUnit.PER_MONTH, false);
		profile.getSubjects().add(new TutorSubject(profile, 1L, null, null, null, null));
		profile.getLocations().add(new TutorLocation(profile, 1L));
		profile.updateBasics("Rahul", "Maths tutor", null, null, null, 3, null, false, null);

		assertThat(ProfileCompleteness.score(profile))
				.isGreaterThanOrEqualTo(ProfileCompleteness.MINIMUM_TO_PUBLISH);
	}

	@Test
	@DisplayName("an online-only tutor is not penalised for having no localities")
	void onlineOnlyTutorNotPenalised() {
		// An online tutor has no serviceable area by definition. Scoring them down for it would
		// make an entire legitimate category of tutor unpublishable.
		TutorProfile online = emptyProfile();
		online.updateTeaching(new String[] { "ONLINE" }, 0);
		online.updateFees(300000L, null, FeeUnit.PER_MONTH, false);
		online.getSubjects().add(new TutorSubject(online, 1L, null, null, null, null));
		online.updateBasics("Rahul", "JEE coaching", null, null, null, 3, null, false, null);

		assertThat(ProfileCompleteness.score(online))
				.isGreaterThanOrEqualTo(ProfileCompleteness.MINIMUM_TO_PUBLISH);
	}

	@Test
	@DisplayName("a two-word bio does not count as a bio")
	void placeholderBioEarnsNothing() {
		TutorProfile withStub = emptyProfile();
		withStub.updateBasics(null, null, "I teach.", null, null, 0, null, false, null);

		TutorProfile withNone = emptyProfile();

		assertThat(ProfileCompleteness.score(withStub))
				.isEqualTo(ProfileCompleteness.score(withNone));
	}

	@Test
	@DisplayName("the score never exceeds 100")
	void scoreIsCapped() {
		assertThat(ProfileCompleteness.score(fullProfile())).isLessThanOrEqualTo(100);
	}
}
