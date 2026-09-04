package com.apnatutor.admin;

import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.apnatutor.admin.dto.MetricsDtos.AccountMetrics;
import com.apnatutor.admin.dto.MetricsDtos.DailyPoint;
import com.apnatutor.admin.dto.MetricsDtos.Dashboard;
import com.apnatutor.admin.dto.MetricsDtos.LeadMetrics;
import com.apnatutor.admin.dto.MetricsDtos.Ratio;
import com.apnatutor.admin.dto.MetricsDtos.RequirementMetrics;
import com.apnatutor.admin.dto.MetricsDtos.RevenueMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin dashboard - M5-05.7.
 *
 * <p>Assembles one snapshot in one transaction. That matters more than it sounds: the ratios here
 * are pairs of counts, and computing them across separate transactions lets a signup land between
 * the numerator and the denominator, producing a conversion rate above 100% on a quiet platform with
 * small numbers. Rare, and the kind of thing nobody ever explains once it is seen.
 *
 * <p>Totals are all-time. Only the daily series is windowed, because a rate over "the last 30 days"
 * on a platform three months old compares a cohort with itself and moves for reasons nobody can act
 * on. When there is a year of history this is where a windowed variant would go.
 */
@Service
public class AdminMetricsService {

	/** Default history on the chart. Four weeks, so weekly rhythm is visible without scrolling. */
	public static final int DEFAULT_WINDOW_DAYS = 28;

	/** A year. Beyond this the query is a report to be run deliberately, not a dashboard. */
	private static final int MAX_WINDOW_DAYS = 365;

	private final AdminMetricsRepository metrics;
	private final Clock clock;

	public AdminMetricsService(AdminMetricsRepository metrics, Clock clock) {
		this.metrics = metrics;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Dashboard dashboard(int windowDays) {
		int days = Math.min(Math.max(windowDays, 1), MAX_WINDOW_DAYS);
		Instant since = clock.instant().minus(Duration.ofDays(days - 1L));

		long tutors = metrics.usersWithRole("TUTOR");
		long posted = metrics.requirementsPosted();
		long unlocks = metrics.unlocks();
		long disputes = metrics.disputesRaised();

		return new Dashboard(
				days,
				new AccountMetrics(
						metrics.usersWithRole("STUDENT"),
						tutors,
						metrics.suspendedUsers(),
						new Ratio(metrics.publishedTutors(), tutors),
						new Ratio(metrics.idVerifiedTutors(), tutors),
						new Ratio(metrics.tutorsWhoPurchased(), tutors)),
				new RequirementMetrics(
						posted,
						metrics.liveRequirements(),
						metrics.requirementsWithStatus("REMOVED"),
						new Ratio(metrics.requirementsAnswered(), posted),
						new Ratio(metrics.requirementsFilled(), posted)),
				new LeadMetrics(
						unlocks,
						metrics.creditsSpentOnLeads(),
						new Ratio(disputes, unlocks),
						new Ratio(metrics.disputesWithStatus("APPROVED"), disputes)),
				new RevenueMetrics(
						metrics.grossRevenuePaise(),
						metrics.paidOrders(),
						new Ratio(
								metrics.ordersStarted() - metrics.paidOrders(),
								metrics.ordersStarted()),
						metrics.bonusCreditsGranted()),
				series(since));
	}

	/**
	 * Maps the raw series rows.
	 *
	 * <p>The date column arrives as {@link java.sql.Date} because the query casts to {@code date};
	 * everything else is a {@link Number} whose exact class depends on whether it came from a
	 * {@code COUNT} or a {@code SUM}, so each is narrowed rather than cast.
	 */
	private List<DailyPoint> series(Instant since) {
		return metrics.dailySeries(since).stream()
				.map(row -> new DailyPoint(
						((Date) row[0]).toLocalDate(),
						((Number) row[1]).longValue(),
						((Number) row[2]).longValue(),
						((Number) row[3]).longValue(),
						((Number) row[4]).longValue()))
				.toList();
	}
}
