package com.apnatutor.lead;

/**
 * What a lead costs a tutor to unlock.
 *
 * <p>Implements SOURCE_OF_TRUTH.md §3.1. A pure function of budget and mode — no database, no
 * clock, no configuration lookup — so every band and boundary can be tested exhaustively, which
 * matters because this decides what people are charged.
 *
 * <h2>Why price scales with budget</h2>
 *
 * <p>A ₹15,000/month enquiry is worth far more to a tutor than a ₹1,500/month one, and charging the
 * same for both means tutors cherry-pick the expensive leads and the cheap ones never get answered.
 * Scaling the price keeps every band roughly equally attractive.
 *
 * <h2>Why the result is stored, not recomputed</h2>
 *
 * <p>The caller writes this onto the requirement at creation
 * ({@code requirements.unlock_cost_credits}). A tutor looking at a lead priced at 5 credits must be
 * charged 5 credits even if the bands change between their reading the feed and tapping unlock.
 */
public final class LeadPricing {

	/** Budget bands in paise. ₹2,000 = 200000 paise. */
	private static final long BAND_1_CEILING = 200_000L;
	private static final long BAND_2_CEILING = 500_000L;
	private static final long BAND_3_CEILING = 1_000_000L;

	private static final int BAND_1_CREDITS = 3;
	private static final int BAND_2_CREDITS = 5;
	private static final int BAND_3_CREDITS = 8;
	private static final int BAND_4_CREDITS = 12;

	/**
	 * Online-only enquiries are discounted 20%.
	 *
	 * <p>They are a weaker signal: a parent willing to have someone travel to their home has usually
	 * decided more firmly than one browsing online options, and any tutor anywhere can serve them,
	 * so supply is deeper and each lead is contested by more people.
	 */
	private static final double ONLINE_MULTIPLIER = 0.8;

	private LeadPricing() {
	}

	/**
	 * @param budgetPaise the student's stated monthly budget; null means unstated
	 * @param mode STUDENT_HOME, TUTOR_PLACE or ONLINE
	 * @return credits required to unlock, always at least 1
	 */
	public static int creditsFor(Long budgetPaise, String mode) {
		int base = baseCredits(budgetPaise);

		if ("ONLINE".equals(mode)) {
			// Rounded UP, per SoT §3.1. Rounding down would let the cheapest band reach zero,
			// and a free lead is the business model given away.
			base = (int) Math.ceil(base * ONLINE_MULTIPLIER);
		}

		return Math.max(base, 1);
	}

	private static int baseCredits(Long budgetPaise) {
		if (budgetPaise == null) {
			// An unstated budget is priced at the lowest band rather than the highest. A parent who
			// skipped the field is not signalling wealth, and overcharging for an unknown makes
			// tutors avoid exactly the enquiries most in need of a reply.
			return BAND_1_CREDITS;
		}

		if (budgetPaise < BAND_1_CEILING) {
			return BAND_1_CREDITS;
		}
		if (budgetPaise < BAND_2_CEILING) {
			return BAND_2_CREDITS;
		}
		if (budgetPaise < BAND_3_CEILING) {
			return BAND_3_CREDITS;
		}
		return BAND_4_CREDITS;
	}
}
