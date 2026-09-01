package com.apnatutor.lead;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import com.apnatutor.lead.domain.LeadPricingBand;

/**
 * What a lead costs a tutor to unlock.
 *
 * <p><strong>A pure function.</strong> Bands and multiplier are passed in rather than read from a
 * database, so every boundary can be tested exhaustively without a Spring context — which matters,
 * because this decides what people are charged and an off-by-one at a band edge is a tutor
 * overpaying repeatedly until someone notices.
 *
 * <p>{@link LeadPricingService} is the thin layer that loads the configuration and calls this.
 *
 * <h2>Why price scales with budget</h2>
 *
 * <p>A ₹15,000/month enquiry is worth far more to a tutor than a ₹1,500/month one. Charging the same
 * for both means tutors cherry-pick the expensive leads and the cheap ones go unanswered; scaling
 * keeps every band roughly equally attractive.
 *
 * <h2>Why the result is stored, not recomputed</h2>
 *
 * <p>The caller writes it onto the requirement at creation. A tutor shown a lead at 5 credits must be
 * charged 5, even if an admin repriced the bands in between.
 */
public final class LeadPricing {

	/** Used only when no bands are configured. Matches SOURCE_OF_TRUTH.md §3.1. */
	public static final List<LeadPricingBand> DEFAULT_BANDS = List.of(
			new LeadPricingBand(0L, 3, "Under Rs 2,000"),
			new LeadPricingBand(200_000L, 5, "Rs 2,000 - 4,999"),
			new LeadPricingBand(500_000L, 8, "Rs 5,000 - 9,999"),
			new LeadPricingBand(1_000_000L, 12, "Rs 10,000 and above"));

	public static final BigDecimal DEFAULT_ONLINE_MULTIPLIER = new BigDecimal("0.8");

	private LeadPricing() {
	}

	/**
	 * @param budgetPaise the stated budget; null means unstated
	 * @param mode STUDENT_HOME, TUTOR_PLACE or ONLINE
	 * @param bands the configured ladder, in any order
	 * @param onlineMultiplier discount applied to online-only enquiries
	 * @return credits required, always at least 1
	 */
	public static int creditsFor(
			Long budgetPaise,
			String mode,
			List<LeadPricingBand> bands,
			BigDecimal onlineMultiplier) {

		List<LeadPricingBand> ladder =
				(bands == null || bands.isEmpty()) ? DEFAULT_BANDS : bands;
		BigDecimal multiplier =
				onlineMultiplier == null ? DEFAULT_ONLINE_MULTIPLIER : onlineMultiplier;

		int base = baseCredits(budgetPaise, ladder);

		if ("ONLINE".equals(mode)) {
			// Online-only enquiries are a weaker signal: a parent willing to have someone travel to
			// their home has usually decided more firmly, and any tutor anywhere can serve an
			// online one, so supply is deeper and each lead is contested by more people.
			//
			// Rounded UP. Rounding down would let the cheapest band reach zero under an aggressive
			// multiplier, and a free lead is the business model given away.
			base = multiplier.multiply(BigDecimal.valueOf(base))
					.setScale(0, java.math.RoundingMode.CEILING)
					.intValue();
		}

		return Math.max(base, 1);
	}

	/** Convenience for callers using the defaults — the unit tests, chiefly. */
	public static int creditsFor(Long budgetPaise, String mode) {
		return creditsFor(budgetPaise, mode, DEFAULT_BANDS, DEFAULT_ONLINE_MULTIPLIER);
	}

	private static int baseCredits(Long budgetPaise, List<LeadPricingBand> bands) {
		// An unstated budget is priced at the LOWEST band, not the highest. A parent who skipped
		// the field is not signalling wealth, and overcharging for an unknown makes tutors avoid
		// exactly the enquiries most in need of a reply.
		long budget = budgetPaise == null ? 0L : budgetPaise;

		return bands.stream()
				.filter(band -> budget >= band.getMinBudgetPaise())
				// The highest bound the budget meets. Sorting here rather than relying on the
				// caller's ordering, because an admin-managed table has no guaranteed order.
				.max(Comparator.comparingLong(LeadPricingBand::getMinBudgetPaise))
				.map(LeadPricingBand::getCredits)
				// No band covers this budget, which can only happen if an admin deleted the zero
				// band. Falling back to the cheapest configured rung is safer than throwing on a
				// parent trying to post.
				.orElseGet(() -> bands.stream()
						.mapToInt(LeadPricingBand::getCredits)
						.min()
						.orElse(1));
	}
}
