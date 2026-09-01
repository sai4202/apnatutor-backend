package com.apnatutor.lead;

import java.time.Clock;
import java.time.Instant;

import com.apnatutor.billing.CreditLedger;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.lead.domain.LeadUnlock;
import com.apnatutor.lead.domain.UnlockStatus;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.domain.Requirement;
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

	private final RequirementRepository requirements;
	private final LeadUnlockRepository unlocks;
	private final CreditLedger ledger;
	private final Clock clock;

	public LeadUnlockService(
			RequirementRepository requirements,
			LeadUnlockRepository unlocks,
			CreditLedger ledger,
			Clock clock) {
		this.requirements = requirements;
		this.unlocks = unlocks;
		this.ledger = ledger;
		this.clock = clock;
	}

	/**
	 * Spends credits to reveal a requirement's contact details.
	 *
	 * @throws ApiException {@code LEAD_ALREADY_UNLOCKED}, {@code LEAD_UNLOCK_CAP_REACHED},
	 *     {@code REQUIREMENT_NOT_OPEN} or {@code INSUFFICIENT_CREDITS} — in every case having
	 *     written nothing
	 */
	@Transactional
	public LeadUnlock unlock(Long requirementId, Long tutorUserId, String introMessage) {
		Instant now = clock.instant();

		// Lock first. Everything below runs with this row held, so the count cannot go stale
		// between the check and the increment.
		Requirement requirement = requirements.findByIdForUpdate(requirementId)
				.orElseThrow(() -> ApiException.notFound("Requirement"));

		if (unlocks.existsByRequirementIdAndTutorIdAndStatus(
				requirementId, tutorUserId, UnlockStatus.ACTIVE)) {
			// Friendly path. The unique index below is what actually guarantees it.
			throw new ApiException(ErrorCode.LEAD_ALREADY_UNLOCKED,
					"You have already unlocked this enquiry.");
		}

		if (requirement.getUnlockCount() >= Requirement.UNLOCK_CAP) {
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
