package com.apnatutor.lead;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.apnatutor.billing.CreditLedger;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.common.config.AppProperties;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.ratelimit.RateLimitPolicy;
import com.apnatutor.ratelimit.RateLimitedException;
import com.apnatutor.ratelimit.RateLimiter;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.lead.domain.LeadUnlock;
import com.apnatutor.lead.domain.UnlockStatus;
import com.apnatutor.notification.NotificationService;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unlock — the transaction the entire product rests on.
 *
 * <h2>What has to be true</h2>
 *
 * <ol>
 *   <li>A tutor is never charged for a lead they do not receive.
 *   <li>A tutor is never charged twice for the same lead.
 *   <li>No more than five tutors ever unlock one requirement, however many try at once.
 *   <li>A rejected attempt leaves no trace — no ledger entry, no partial record.
 * </ol>
 *
 * <h2>How</h2>
 *
 * <p>One transaction, and a row lock taken on the requirement first. The lock is what makes the cap
 * hold: without it, five tutors hitting the last slot together each read {@code unlockCount = 4},
 * each conclude there is room, and five get charged for four slots.
 *
 * <p>Locks are always taken requirement-then-wallet, never the reverse. Two orderings across two
 * code paths is a deadlock waiting for traffic.
 *
 * <p>The unique index on {@code (requirement_id, tutor_id)} is the last line of defence. The check
 * below is the friendly path; the constraint is what actually guarantees it, because a check can
 * race and a constraint cannot.
 */
@Service
public class LeadUnlockService {

	private static final Logger log = LoggerFactory.getLogger(LeadUnlockService.class);

	/** Fallback if the setting is missing; the live value is admin-configurable. */
	private static final int DEFAULT_LOW_BALANCE_THRESHOLD = 10;

	private final RequirementRepository requirements;
	private final LeadUnlockRepository unlocks;
	private final CreditLedger ledger;
	private final NotificationService notifications;
	private final SettingsService settings;
	private final RateLimiter rateLimiter;
	private final AppProperties properties;
	private final Clock clock;

	public LeadUnlockService(
			RequirementRepository requirements,
			LeadUnlockRepository unlocks,
			CreditLedger ledger,
			NotificationService notifications,
			SettingsService settings,
			RateLimiter rateLimiter,
			AppProperties properties,
			Clock clock) {
		this.requirements = requirements;
		this.unlocks = unlocks;
		this.ledger = ledger;
		this.notifications = notifications;
		this.settings = settings;
		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Spends credits to reveal a requirement's contact details.
	 *
	 * <h2>Repeating the call is safe and free</h2>
	 *
	 * <p>A tutor who already holds this unlock gets it back, not an error. This is deliberate, and
	 * it is what makes the endpoint replay-safe without an {@code Idempotency-Key} header.
	 *
	 * <p>The failure it prevents is specific and likely: a tutor on a patchy mobile connection taps
	 * unlock, the request succeeds, the response never arrives, and the client retries. Answering
	 * that retry with an error would leave them charged and holding nothing — the worst outcome the
	 * money path can produce. They have already bought this lead; handing it over again costs
	 * nothing and is the only answer that matches what they own.
	 *
	 * <p>The charge is still exactly once. It happens below this early return, guarded by a
	 * {@code FOR UPDATE} lock and, underneath that, a unique index on
	 * {@code (requirement_id, tutor_id)}.
	 *
	 * @throws ApiException {@code LEAD_UNLOCK_CAP_REACHED}, {@code REQUIREMENT_NOT_OPEN} or
	 *     {@code INSUFFICIENT_CREDITS} — in every case having written nothing
	 */
	@Transactional
	public LeadUnlock unlock(Long requirementId, Long tutorUserId, String introMessage) {
		Instant now = clock.instant();

		// Lock first. Everything below runs with this row held, so the count cannot go stale
		// between the check and the increment.
		Requirement requirement = requirements.findByIdForUpdate(requirementId)
				.orElseThrow(() -> ApiException.notFound("Requirement"));

		var existing = unlocks.findByRequirementIdAndTutorIdAndStatus(
				requirementId, tutorUserId, UnlockStatus.ACTIVE);
		if (existing.isPresent()) {
			// Already bought. Return it rather than charging or erroring.
			log.info("Unlock replayed: requirement={} tutor={}", requirementId, tutorUserId);
			return existing.get();
		}

		// M5-07.2: per-tutor, and deliberately AFTER the replay check above.
		//
		// A tutor on a patchy connection retrying the same unlock must never be rate-limited —
		// that request buys nothing new, and refusing it would break the exact case the replay
		// path exists for, turning a flaky network into a lost credit. Only a genuinely new
		// unlock counts against the allowance.
		//
		// Keyed on the tutor rather than the IP because the identity is proven here, and because
		// several tutors sharing one address is normal in this market. The filter cannot do this:
		// it runs before authentication, where a user id would only be attacker-supplied.
		RateLimiter.Decision decision = rateLimiter.check(
				String.valueOf(tutorUserId),
				new RateLimitPolicy(
						"unlock",
						properties.rateLimit().unlocksPerHourPerTutor(),
						Duration.ofHours(1)));

		if (!decision.allowed()) {
			log.warn("Unlock rate limit reached: tutor={} requirement={}",
					tutorUserId, requirementId);
			throw new RateLimitedException(
					"You have unlocked a lot of leads in a short time. "
							+ "Please try again shortly.",
					decision.retryAfterSeconds());
		}

		if (requirement.getUnlockCount() >= requirement.getUnlockCap()) {
			// NO CREDITS TAKEN. Charging for a lead that cannot be delivered is the fastest way
			// to lose a tutor permanently.
			throw new ApiException(ErrorCode.LEAD_UNLOCK_CAP_REACHED,
					"This enquiry already has the maximum number of responses.");
		}

		if (!requirement.isUnlockable(now)) {
			throw new ApiException(ErrorCode.REQUIREMENT_NOT_OPEN,
					"This enquiry is no longer accepting responses.");
		}

		// The price stored on the requirement, not a recomputed one. A tutor charged more than the
		// figure they were shown has been misled, whatever the pricing table now says.
		int cost = requirement.getUnlockCostCredits();

		// Throws INSUFFICIENT_CREDITS before writing anything, so a rejected attempt leaves no
		// ledger entry — a refusal is not a financial event.
		ledger.spend(tutorUserId, cost, CreditReason.UNLOCK, "REQUIREMENT", requirementId);

		requirement.recordUnlock();
		requirements.save(requirement);

		try {
			LeadUnlock unlock = unlocks.save(
					LeadUnlock.record(requirementId, tutorUserId, cost, introMessage, now));

			// Recorded inside this transaction so it rolls back with the unlock — telling a parent
			// about a response that did not happen is worse than telling them nothing. Actual
			// delivery happens after commit; see NotificationService.
			notifyStudentAndTutor(requirement, tutorUserId, cost);

			log.info("Lead unlocked: requirement={} tutor={} credits={} slotsLeft={}",
					requirementId, tutorUserId, cost, requirement.remainingSlots());

			return unlock;
		} catch (DataIntegrityViolationException e) {
			// The unique index fired, meaning a concurrent request for this same tutor and
			// requirement got there first. Rolling back takes the credits with it, which is the
			// correct outcome — one unlock, one charge.
			log.warn("Duplicate unlock blocked by constraint: requirement={} tutor={}",
					requirementId, tutorUserId);
			throw new ApiException(ErrorCode.LEAD_ALREADY_UNLOCKED,
					"You have already unlocked this enquiry.");
		}
	}

	/**
	 * Tells the student someone responded, and warns the tutor if they are running low.
	 *
	 * <p>The low-balance warning matters more than it looks: a tutor who silently runs out stops
	 * seeing value in the platform, whereas one who is told is usually one top-up away from
	 * continuing.
	 */
	private void notifyStudentAndTutor(Requirement requirement, Long tutorUserId, int cost) {
		notifications.notify(
				requirement.getStudentId(),
				NotificationType.TUTOR_RESPONDED,
				"A tutor responded to your enquiry",
				"A verified tutor has responded and can now contact you. "
						+ "You can see their details on your requirement.",
				"REQUIREMENT",
				requirement.getId());

		int remaining = ledger.balanceOf(tutorUserId);
		int threshold = settings.intValue(
				SettingsService.LOW_BALANCE_THRESHOLD, DEFAULT_LOW_BALANCE_THRESHOLD);

		if (remaining < threshold) {
			notifications.notify(
					tutorUserId,
					NotificationType.LOW_CREDIT_BALANCE,
					"You are running low on credits",
					"You have %d credits left. Top up to keep responding to enquiries."
							.formatted(remaining),
					"WALLET",
					null);
		}
	}

	/**
	 * Reverses an unlock after an approved dispute.
	 *
	 * <p>Returns the credits, marks the unlock refunded, and frees the cap slot so another tutor can
	 * take it — a bad lead should not permanently consume one of the five.
	 */
	@Transactional
	public void refund(Long unlockId, String reason) {
		LeadUnlock unlock = unlocks.findById(unlockId)
				.orElseThrow(() -> ApiException.notFound("Unlock"));

		if (!unlock.isActive()) {
			throw new ApiException(ErrorCode.CONFLICT, "This unlock was already refunded.");
		}

		Requirement requirement = requirements.findByIdForUpdate(unlock.getRequirementId())
				.orElseThrow(() -> ApiException.notFound("Requirement"));

		ledger.grant(
				unlock.getTutorId(),
				unlock.getCreditsSpent(),
				CreditReason.REFUND,
				"LEAD_UNLOCK",
				unlockId,
				null);

		unlock.markRefunded();
		unlocks.save(unlock);

		requirement.releaseUnlock();
		requirements.save(requirement);

		log.info("Unlock refunded: id={} tutor={} credits={} reason={}",
				unlockId, unlock.getTutorId(), unlock.getCreditsSpent(), reason);
	}
}
