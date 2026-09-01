package com.apnatutor.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.notification.NotificationService;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.settings.SettingsService;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The signup bonus and credit expiry — {@code M4-04} and {@code M4-05}.
 *
 * <p>Two places where credits move without anyone paying or spending, and both are easy to get
 * quietly wrong: a bonus granted twice costs real money, and an expiry that overshoots takes credits
 * a tutor already paid for.
 */
class CreditLifecycleTest extends AbstractIntegrationTest {

	@Autowired
	private SignupBonusService signupBonus;

	@Autowired
	private CreditExpiryService expiry;

	@Autowired
	private CreditLedger ledger;

	@Autowired
	private CreditTransactionRepository transactions;

	@Autowired
	private NotificationService notifications;

	@Autowired
	private SettingsService settings;

	@Autowired
	private UserRepository users;

	@Autowired
	private Clock clock;

	// --- Signup bonus -------------------------------------------------------------------------

	@Test
	@DisplayName("the bonus is granted once and carries an expiry")
	void bonusIsGrantedOnce() {
		Long tutorId = tutor();
		int configured = settings.intValue(SettingsService.SIGNUP_BONUS, 10);

		assertThat(signupBonus.grantIfEarned(tutorId)).isEqualTo(configured);
		assertThat(ledger.balanceOf(tutorId)).isEqualTo(configured);

		var entry = transactions.findByTutorIdOrderByCreatedAtDesc(tutorId).getFirst();
		assertThat(entry.getReason()).isEqualTo(CreditReason.SIGNUP_BONUS);
		assertThat(entry.getExpiresAt())
				.as("bonus credits expire, unlike purchased ones which are paid for")
				.isNotNull();
	}

	@Test
	@DisplayName("calling it again grants nothing")
	void bonusIsNotGrantedTwice() {
		Long tutorId = tutor();
		int first = signupBonus.grantIfEarned(tutorId);

		// A second ID verification, a retried admin click, a duplicated event — all reach here.
		assertThat(signupBonus.grantIfEarned(tutorId)).isZero();
		assertThat(signupBonus.grantIfEarned(tutorId)).isZero();

		assertThat(ledger.balanceOf(tutorId)).isEqualTo(first);
		assertThat(transactions.findByTutorIdOrderByCreatedAtDesc(tutorId))
				.filteredOn(entry -> entry.getReason() == CreditReason.SIGNUP_BONUS)
				.hasSize(1);
	}

	@Test
	@DisplayName("students get no bonus — they have no wallet to spend it from")
	void studentsGetNoBonus() {
		Long studentId = users.save(User.registerVerified(
				uniquePhone("9193"), UserRole.STUDENT, clock.instant())).getId();

		assertThat(signupBonus.grantIfEarned(studentId)).isZero();
		assertThat(ledger.balanceOf(studentId)).isZero();
	}

	@Test
	@DisplayName("the tutor is told the credits arrived")
	void bonusIsAnnounced() {
		Long tutorId = tutor();
		signupBonus.grantIfEarned(tutorId);

		// Credits nobody knows about buy nothing. The notification is the point of the bonus,
		// not a nicety on top of it.
		assertThat(notifications.forUser(tutorId))
				.anyMatch(n -> n.getType() == NotificationType.SIGNUP_BONUS_GRANTED);
	}

	// --- Expiry -------------------------------------------------------------------------------

	@Test
	@DisplayName("a lapsed grant is written off with a compensating entry, not an edit")
	void lapsedCreditsAreWrittenOff() {
		Long tutorId = tutor();
		ledger.grant(tutorId, 10, CreditReason.PURCHASE, "TEST", null, past());

		assertThat(expiry.expireLapsed()).isEqualTo(10);
		assertThat(ledger.balanceOf(tutorId)).isZero();

		var reasons = transactions.findByTutorIdOrderByCreatedAtDesc(tutorId).stream()
				.map(entry -> entry.getReason())
				.toList();

		// The grant is untouched and a negative EXPIRY entry sits beside it. "Granted 10, 10
		// lapsed on this date" is auditable; a grant that quietly shrank is not.
		assertThat(reasons).contains(CreditReason.PURCHASE, CreditReason.EXPIRY);
		assertThat(ledger.reconcile(tutorId)).isZero();
	}

	@Test
	@DisplayName("credits that have not lapsed are left alone")
	void unexpiredCreditsSurvive() {
		Long tutorId = tutor();
		ledger.grant(tutorId, 10, CreditReason.PURCHASE, "TEST", null, future());

		assertThat(expiry.expireLapsed()).isZero();
		assertThat(ledger.balanceOf(tutorId)).isEqualTo(10);
	}

	@Test
	@DisplayName("credits with no expiry never lapse")
	void nonExpiringCreditsSurvive() {
		Long tutorId = tutor();
		// Refunds are granted without an expiry — they were paid for once already.
		ledger.grant(tutorId, 10, CreditReason.REFUND, "TEST", null, null);

		assertThat(expiry.expireLapsed()).isZero();
		assertThat(ledger.balanceOf(tutorId)).isEqualTo(10);
	}

	@Test
	@DisplayName("expiry never takes the balance below zero")
	void expiryDoesNotClawBackSpentCredits() {
		Long tutorId = tutor();
		ledger.grant(tutorId, 10, CreditReason.PURCHASE, "TEST", null, past());
		spend(tutorId, 8);

		// THE POINT. Credits are fungible, so a 10-credit grant is still on record after 8 of
		// them are spent. Writing off the full 10 would take the balance to -2 and bill the tutor
		// for credits they already used. Given the choice, the platform takes the loss.
		assertThat(expiry.expireLapsed()).isEqualTo(2);
		assertThat(ledger.balanceOf(tutorId)).isZero();
		assertThat(ledger.reconcile(tutorId)).isZero();
	}

	@Test
	@DisplayName("a fully spent grant lapsing costs nothing and is not reprocessed")
	void fullySpentGrantIsNotReprocessed() {
		Long tutorId = tutor();
		ledger.grant(tutorId, 10, CreditReason.PURCHASE, "TEST", null, past());
		spend(tutorId, 10);

		assertThat(expiry.expireLapsed()).isZero();
		assertThat(expiry.expireLapsed()).isZero();

		// Nothing moved, so there must be NO ledger entry — a zero-amount one is not a movement
		// and the database refuses it. The grant is marked as handled in credit_grant_expiries
		// instead, which is what stops the job returning it on every run forever.
		assertThat(transactions.findByTutorIdOrderByCreatedAtDesc(tutorId))
				.as("a no-op expiry writes no ledger entry")
				.noneMatch(entry -> entry.getReason() == CreditReason.EXPIRY);
	}

	@Test
	@DisplayName("running expiry twice does not double-charge")
	void expiryIsIdempotent() {
		Long tutorId = tutor();
		ledger.grant(tutorId, 10, CreditReason.PURCHASE, "TEST", null, past());

		assertThat(expiry.expireLapsed()).isEqualTo(10);
		assertThat(expiry.expireLapsed()).isZero();
		assertThat(ledger.balanceOf(tutorId)).isZero();
	}

	@Test
	@DisplayName("a tutor is warned before their credits lapse, once for all of them")
	void warningIsSentOncePerTutor() {
		Long tutorId = tutor();
		Instant soon = clock.instant().plus(Duration.ofDays(3));
		ledger.grant(tutorId, 5, CreditReason.PURCHASE, "TEST", null, soon);
		ledger.grant(tutorId, 7, CreditReason.SIGNUP_BONUS, "TEST", null, soon);

		expiry.warnAboutExpiring();

		// Asserted on this tutor, not on the run's total. The total counts every tutor in the
		// test database, so asserting on it would really be asserting the order the tests ran in.
		//
		// Two grants lapsing the same week is one piece of news. Two messages about it is the
		// reason people mute notifications.
		assertThat(notifications.forUser(tutorId))
				.filteredOn(n -> n.getType() == NotificationType.CREDITS_EXPIRING)
				.hasSize(1);
	}

	@Test
	@DisplayName("no warning for credits lapsing beyond the window")
	void noWarningForDistantExpiry() {
		Long tutorId = tutor();
		ledger.grant(tutorId, 5, CreditReason.PURCHASE, "TEST", null, future());

		expiry.warnAboutExpiring();

		assertThat(notifications.forUser(tutorId))
				.as("credits lapsing in 200 days are not news today")
				.noneMatch(n -> n.getType() == NotificationType.CREDITS_EXPIRING);
	}

	// --- Helpers ------------------------------------------------------------------------------

	/** Spends through the ledger, which requires an ambient transaction. */
	private void spend(Long tutorId, int credits) {
		new org.springframework.transaction.support.TransactionTemplate(transactionManager)
				.executeWithoutResult(status ->
						ledger.spend(tutorId, credits, CreditReason.UNLOCK, "TEST", null));
	}

	@Autowired
	private org.springframework.transaction.PlatformTransactionManager transactionManager;

	private Instant past() {
		return clock.instant().minus(Duration.ofDays(1));
	}

	private Instant future() {
		return clock.instant().plus(Duration.ofDays(200));
	}

	private Long tutor() {
		return users.save(User.registerVerified(
				uniquePhone("9192"), UserRole.TUTOR, clock.instant())).getId();
	}

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
