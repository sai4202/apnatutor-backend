package com.apnatutor.user;

import com.apnatutor.user.domain.TutorProfile;

/**
 * Scores how complete a tutor profile is, 0–100.
 *
 * <p>It does two jobs. It gates publishing — an empty profile in search results wastes a parent's
 * time and reflects on every other tutor on the platform. And it gives the onboarding wizard a
 * progress number, which is the single most effective nudge for getting a half-finished profile
 * finished.
 *
 * <p><strong>The weights are a judgement about what a parent decides on</strong>, not an even split
 * across fields. Subjects and location are worth more than a bio because a profile without them
 * cannot be matched to a requirement at all — it is invisible regardless of how well written it is.
 * A photo is weighted meaningfully because a parent choosing who enters their home looks at the
 * face first.
 *
 * <p>Deliberately a pure function of the profile, with no database access, so it is trivial to unit
 * test across the whole range.
 */
public final class ProfileCompleteness {

	/** Below this a profile cannot be published. Roughly: subjects, location, fees and a headline. */
	public static final int MINIMUM_TO_PUBLISH = 60;

	private ProfileCompleteness() {
	}

	public static int score(TutorProfile profile) {
		int score = 0;

		// --- Matchability: without these the profile cannot be found at all -------------------
		if (!profile.getSubjects().isEmpty()) {
			score += 20;
		}
		if (!profile.getLocations().isEmpty() || teachesOnline(profile)) {
			score += 15;
		}
		if (profile.getTeachingModes().length > 0) {
			score += 10;
		}
		if (profile.getFeeMinPaise() != null && profile.getFeeUnit() != null) {
			score += 15;
		}

		// --- Credibility: what a parent reads before deciding ---------------------------------
		if (isPresent(profile.getDisplayName())) {
			score += 5;
		}
		if (isPresent(profile.getHeadline())) {
			score += 10;
		}
		if (isPresent(profile.getBio()) && profile.getBio().trim().length() >= 80) {
			// A two-word bio is not a bio. The threshold stops the score rewarding a placeholder.
			score += 10;
		}
		if (isPresent(profile.getPhotoUrl())) {
			score += 10;
		}
		if (!profile.getQualifications().isEmpty()) {
			score += 5;
		}

		return Math.min(score, 100);
	}

	/** An online-only tutor has no serviceable localities, and should not be penalised for it. */
	private static boolean teachesOnline(TutorProfile profile) {
		for (String mode : profile.getTeachingModes()) {
			if ("ONLINE".equals(mode)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}
}
