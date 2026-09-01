package com.apnatutor.billing;

import java.time.Clock;
import java.time.Instant;

import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.notification.NotificationService;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.settings.SettingsService;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The free credits a tutor gets for verifying their identity.
 *
 * <p>SOURCE_OF_TRUTH.md §3.3. The bonus exists to do two jobs at once: it pays for the friction of
 * uploading an ID, and it lets a new tutor unlock their first lead without a payment decision — which
 * is the moment they find out whether the platform works for them at all. A tutor who verifies but
 * never unlocks anything has learned nothing about us.
 *
 * <h2>Once, and only once</h2>
 *
 * <p>Guaranteed by a partial unique index on {@code credit_transactions (tutor_id) WHERE reason =
 * 'SIGNUP_BONUS'}, not by the check below. The check is the friendly path; the index is the
 * guarantee. Two verification approvals landing together would each see no bonus, each conclude
 * there is work to do, and each grant — and no amount of application logic prevents that on its own.
 */
@Service
public class SignupBonusService {

	private static final Logger log = LoggerFactory.getLogger(SignupBonusService.class);

	/** Fallbacks if the settings rows are missing. SOURCE_OF_TRUTH.md §3.3 and §3.4. */
	private static final int DEFAULT_BONUS_CREDITS = 10;
	private static final int DEFAULT_BONUS_VALIDITY_DAYS = 90;

	private final CreditLedger ledger;
	private final CreditTransactionRepository transactions;
	private final NotificationService notifications;
	private final SettingsService settings;
	private final UserRepository users;
	private final Clock clock;

	public SignupBonusService(
			CreditLedger ledger,
			CreditTransactionRepository transactions,
			NotificationService notifications,
			SettingsService settings,
			UserRepository users,
			Clock clock) {
		this.ledger = ledger;
		this.transactions = transactions;
		this.notifications = notifications;
		this.settings = settings;
		this.users = users;
		this.clock = clock;
	}

	/**
	 * Grants the bonus if this user has earned it and not had it.
	 *
	 * <h2>Why {@code REQUIRES_NEW}</h2>
	 *
	 * <p>The bonus must not be able to fail the verification approval that triggered it. An admin
	 * approving an ID is making a trust decision; a bonus that has already been granted, or a
	 * settings row that is missing, must not roll that back. In its own transaction the failure is
	 * contained and loggable, and the approval stands either way.
	 *
	 * @return the credits granted, or 0 if none were
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int grantIfEarned(Long userId) {
		// Students have no wallet. Reaching here for one would mean the verification flow changed
		// without this being revisited, so it is worth a log line rather than a silent return.
		boolean isTutor = users.findById(userId)
				.map(user -> user.getRole() == UserRole.TUTOR)
				.orElse(false);
		if (!isTutor) {
			log.debug("Signup bonus skipped: user {} is not a tutor", userId);
			return 0;
		}

		if (transactions.existsByTutorIdAndReason(userId, CreditReason.SIGNUP_BONUS)) {
			return 0;
		}

		int credits = settings.intValue(SettingsService.SIGNUP_BONUS, DEFAULT_BONUS_CREDITS);
		if (credits <= 0) {
			// A legitimate configuration: an admin can turn the bonus off entirely.
			log.info("Signup bonus is set to {} credits; nothing granted to tutor {}",
					credits, userId);
			return 0;
		}

		Instant expiresAt = clock.instant().plus(settings.durationDays(
				SettingsService.BONUS_VALIDITY_DAYS, DEFAULT_BONUS_VALIDITY_DAYS));

		try {
			ledger.grant(userId, credits, CreditReason.SIGNUP_BONUS, "VERIFICATION", null,
					expiresAt);
		} catch (DataIntegrityViolationException e) {
			// The partial unique index fired: a concurrent approval got there first. Exactly the
			// case the check above cannot cover, and the correct outcome — one bonus.
			log.info("Concurrent signup bonus blocked by constraint for tutor {}", userId);
			return 0;
		}

		notifications.notify(
				userId,
				NotificationType.SIGNUP_BONUS_GRANTED,
				"%d free credits added".formatted(credits),
				("Your ID is verified and %d credits are in your wallet. That is enough to "
						+ "respond to your first few enquiries.").formatted(credits),
				"WALLET",
				null);

		log.info("Signup bonus granted: tutor={} credits={} expires={}",
				userId, credits, expiresAt);

		return credits;
	}
}
