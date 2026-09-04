package com.apnatutor.admin;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

/**
 * The funnel queries - M5-05.7.
 *
 * <h2>Why this is one class of native SQL rather than counts spread across modules</h2>
 *
 * <p>A funnel is a set of numbers that only mean anything next to each other: "180 enquiries" and
 * "24 unlocks" are two facts, and "24 of 180 enquiries got a response" is the one that changes what
 * anybody does. Assembling that from six modules' repositories would put the arithmetic in a service
 * and leave each half of every ratio computed by a different query, which is how two numbers on one
 * screen end up describing two different time windows.
 *
 * <p>These are read-only aggregates over tables other modules own. That is the one direction of
 * cross-module reach this project allows without a service in between: nothing here can write, and a
 * COUNT cannot violate an invariant.
 */
@Repository
public class AdminMetricsRepository {

	private final EntityManager entityManager;

	public AdminMetricsRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	// --- Accounts -------------------------------------------------------------------------------

	public long usersWithRole(String role) {
		return count("SELECT COUNT(*) FROM users WHERE role = :role", "role", role);
	}

	public long suspendedUsers() {
		return count("SELECT COUNT(*) FROM users WHERE status = 'SUSPENDED'");
	}

	/** Tutors whose profile is live in search. The first real step of the tutor funnel. */
	public long publishedTutors() {
		return count("""
				SELECT COUNT(*) FROM tutor_profiles tp
				JOIN users u ON u.id = tp.user_id
				WHERE tp.is_published AND u.status = 'ACTIVE'""");
	}

	public long idVerifiedTutors() {
		return count("""
				SELECT COUNT(DISTINCT v.user_id) FROM verifications v
				WHERE v.type = 'ID' AND v.status = 'APPROVED'""");
	}

	/**
	 * Tutors who have ever paid us anything.
	 *
	 * <p>{@code COUNT(DISTINCT tutor_id)} over paid orders, not a count of orders: this is the step
	 * of the funnel where a tutor becomes a customer, and someone who buys ten packs converted once.
	 */
	public long tutorsWhoPurchased() {
		return count("SELECT COUNT(DISTINCT tutor_id) FROM payments WHERE status = 'PAID'");
	}

	// --- Requirements ---------------------------------------------------------------------------

	public long requirementsPosted() {
		return count("SELECT COUNT(*) FROM requirements");
	}

	public long requirementsWithStatus(String status) {
		return count("SELECT COUNT(*) FROM requirements WHERE status = :status", "status", status);
	}

	public long liveRequirements() {
		return count("SELECT COUNT(*) FROM requirements WHERE status IN ('OPEN', 'CAPPED')");
	}

	/**
	 * Enquiries at least one tutor paid to reach.
	 *
	 * <p>Arguably the single most important number on the platform: a student whose enquiry was
	 * answered comes back and tells other parents, and one whose enquiry sat untouched for thirty
	 * days does neither.
	 *
	 * <p>Counts the enquiry, not the unlocks, and does not require the unlock to still be active -
	 * a refunded unlock still means a tutor got in touch, which is what the student experienced.
	 */
	public long requirementsAnswered() {
		return count("SELECT COUNT(DISTINCT requirement_id) FROM lead_unlocks");
	}

	/** Enquiries that used every response slot they were posted with. */
	public long requirementsFilled() {
		return count("SELECT COUNT(*) FROM requirements WHERE unlock_count >= unlock_cap");
	}

	// --- Leads and disputes ---------------------------------------------------------------------

	public long unlocks() {
		return count("SELECT COUNT(*) FROM lead_unlocks");
	}

	/** Credits spent on leads, as a positive number. Ledger amounts for a spend are negative. */
	public long creditsSpentOnLeads() {
		return count("""
				SELECT COALESCE(-SUM(amount), 0) FROM credit_transactions
				WHERE reason = 'UNLOCK'""");
	}

	public long disputesRaised() {
		return count("SELECT COUNT(*) FROM refund_requests");
	}

	public long disputesWithStatus(String status) {
		return count(
				"SELECT COUNT(*) FROM refund_requests WHERE status = :status", "status", status);
	}

	// --- Money ----------------------------------------------------------------------------------

	/**
	 * Gross receipts in paise.
	 *
	 * <p>Restricted to payments that were actually credited, not merely marked paid. The two agree
	 * today, and the day they do not it will be because crediting failed after capture - in which
	 * case this number should be the smaller one, so nobody reports revenue for credits a tutor
	 * never received.
	 */
	public long grossRevenuePaise() {
		return count("""
				SELECT COALESCE(SUM(amount_paise), 0) FROM payments
				WHERE status = 'PAID' AND credited_at IS NOT NULL""");
	}

	public long paidOrders() {
		return count("SELECT COUNT(*) FROM payments WHERE status = 'PAID'");
	}

	public long ordersStarted() {
		return count("SELECT COUNT(*) FROM payments");
	}

	public long bonusCreditsGranted() {
		return count("""
				SELECT COALESCE(SUM(amount), 0) FROM credit_transactions
				WHERE reason = 'SIGNUP_BONUS'""");
	}

	// --- Daily series ---------------------------------------------------------------------------

	/**
	 * One row per day for the dashboard chart, oldest first.
	 *
	 * <p>Built from {@code generate_series} rather than from the data, so a day on which nothing
	 * happened is a zero rather than a gap. A line chart that silently omits empty days draws a
	 * straight line through a dead week and makes an outage look like steady trade.
	 *
	 * <p>Dates are bucketed in Asia/Kolkata. Everything is stored in UTC, and a day boundary five
	 * and a half hours out puts an evening signup on the wrong day for every person reading this.
	 *
	 * @return rows of {@code [date, signups, requirements, unlocks, revenuePaise]}
	 */
	@SuppressWarnings("unchecked")
	public List<Object[]> dailySeries(Instant since) {
		Query query = entityManager.createNativeQuery("""
				WITH days AS (
				    SELECT generate_series(
				        (:since AT TIME ZONE 'Asia/Kolkata')::date,
				        (now() AT TIME ZONE 'Asia/Kolkata')::date,
				        '1 day') AS day
				)
				SELECT d.day,
				       (SELECT COUNT(*) FROM users u
				        WHERE (u.created_at AT TIME ZONE 'Asia/Kolkata')::date = d.day),
				       (SELECT COUNT(*) FROM requirements r
				        WHERE (r.created_at AT TIME ZONE 'Asia/Kolkata')::date = d.day),
				       (SELECT COUNT(*) FROM lead_unlocks lu
				        WHERE (lu.unlocked_at AT TIME ZONE 'Asia/Kolkata')::date = d.day),
				       (SELECT COALESCE(SUM(p.amount_paise), 0) FROM payments p
				        WHERE p.status = 'PAID' AND p.credited_at IS NOT NULL
				          AND (p.credited_at AT TIME ZONE 'Asia/Kolkata')::date = d.day)
				FROM days d
				ORDER BY d.day
				""");
		query.setParameter("since", since);
		return query.getResultList();
	}

	// --- Internals ------------------------------------------------------------------------------

	private long count(String sql) {
		return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
	}

	private long count(String sql, String parameter, Object value) {
		Query query = entityManager.createNativeQuery(sql);
		query.setParameter(parameter, value);
		return ((Number) query.getSingleResult()).longValue();
	}
}
