package com.apnatutor.admin.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Funnel metrics - M5-05.7.
 *
 * <p>Every rate here is expressed as a numerator and a denominator rather than a percentage. A
 * dashboard that says "conversion 8%" without saying 8% of what is unreadable on a young platform,
 * where the honest answer is often "two out of twenty-five" and the percentage is noise. The screen
 * can divide; it cannot un-divide.
 */
public final class MetricsDtos {

	private MetricsDtos() {
	}

	@Schema(description = "A count out of a total, so the screen can show both")
	public record Ratio(long of, long total) {

		/** Convenience for the API consumer. Null rather than zero when there is no denominator. */
		public Double rate() {
			return total == 0 ? null : (double) of / total;
		}
	}

	@Schema(description = "Accounts, and how far each kind gets through its own funnel")
	public record AccountMetrics(
			long students,
			long tutors,
			long suspended,
			@Schema(description = "Tutors whose profile is live in search, of all tutors")
			Ratio published,
			@Schema(description = "Tutors who have passed ID verification, of all tutors")
			Ratio idVerified,
			@Schema(description = "Tutors who have ever bought credits, of all tutors")
			Ratio everPurchased) {
	}

	@Schema(description = "Enquiries, and whether they got anybody")
	public record RequirementMetrics(
			long posted,
			long live,
			long removed,
			@Schema(description = "Enquiries at least one tutor paid to reach, of all posted. "
					+ "The number that decides whether students come back.")
			Ratio answered,
			@Schema(description = "Enquiries that used every response slot, of all posted")
			Ratio filled) {
	}

	@Schema(description = "Leads sold, and how many were disputed")
	public record LeadMetrics(
			long unlocks,
			long creditsSpent,
			@Schema(description = "Unlocks disputed by the tutor who bought them, of all unlocks")
			Ratio disputed,
			@Schema(description = "Disputes upheld, of all disputes raised")
			Ratio disputesUpheld) {
	}

	@Schema(description = "Money in. Paise, never rupees - see SOURCE_OF_TRUTH.md section 5.")
	public record RevenueMetrics(
			@Schema(description = "Confirmed and credited, in paise")
			long grossPaise,
			long paidOrders,
			@Schema(description = "Orders started but never confirmed, of all orders started. "
					+ "A checkout abandonment rate, not a failure rate.")
			Ratio abandoned,
			@Schema(description = "Credits granted as signup bonuses - given away, not sold")
			long bonusCreditsGranted) {
	}

	/** One day on the dashboard chart. */
	@Schema(description = "A single day of activity")
	public record DailyPoint(
			LocalDate day,
			long signups,
			long requirements,
			long unlocks,
			long revenuePaise) {
	}

	@Schema(description = "The whole dashboard in one call")
	public record Dashboard(
			@Schema(description = "Days of history in the series below")
			int windowDays,
			AccountMetrics accounts,
			RequirementMetrics requirements,
			LeadMetrics leads,
			RevenueMetrics revenue,
			@Schema(description = "Oldest first, one entry per day, days with no activity included")
			List<DailyPoint> daily) {
	}
}
