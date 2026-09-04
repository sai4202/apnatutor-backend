package com.apnatutor.requirement;

import java.time.Clock;
import java.util.List;

import com.apnatutor.billing.RefundService;
import com.apnatutor.audit.AuditContext;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.notification.NotificationService;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.requirement.domain.RequirementStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taking down spam and fake enquiries — {@code M5-05.6}.
 *
 * <h2>Why a takedown costs the platform money</h2>
 *
 * <p>Removing a requirement is not a moderation gesture; it is an admission. Tutors have already
 * paid credits to reach whoever posted it, and if the enquiry was fake then we sold them nothing.
 * Every active unlock is refunded in the same transaction as the removal, without anyone having to
 * ask. A takedown that left the charges standing would be the platform keeping the proceeds of its
 * own bad lead, which is precisely the behaviour that stops tutors buying credits again.
 *
 * <p>The consequence worth being explicit about: <strong>removal is expensive on purpose.</strong>
 * It is not the tool for a badly-written enquiry, and the reason is recorded and shown to the
 * student.
 */
@Service
public class RequirementModerationService {

	private static final Logger log = LoggerFactory.getLogger(RequirementModerationService.class);

	/**
	 * How many different tutors must dispute an enquiry before it reaches the moderation queue.
	 *
	 * <p>Three, matching the threshold {@code RefundService} already warns at. Two is inside the
	 * range of ordinary bad luck — a parent who stopped answering their phone disappoints everyone
	 * who calls. Three separate tutors is a pattern.
	 */
	public static final int DISPUTES_TO_FLAG = 3;

	/** States a live enquiry can be in — what suspending its author has to clear up. */
	private static final List<RequirementStatus> LIVE =
			List.of(RequirementStatus.OPEN, RequirementStatus.CAPPED);

	private final RequirementRepository requirements;
	private final RefundService refunds;
	private final NotificationService notifications;
	private final Clock clock;

	public RequirementModerationService(
			RequirementRepository requirements,
			RefundService refunds,
			NotificationService notifications,
			Clock clock) {
		this.requirements = requirements;
		this.refunds = refunds;
		this.notifications = notifications;
		this.clock = clock;
	}

	/** The queue: live enquiries at least {@value #DISPUTES_TO_FLAG} different tutors disputed. */
	@Transactional(readOnly = true)
	public Page<Requirement> flaggedQueue(Pageable pageable) {
		return requirements.findDisputedAtLeast(DISPUTES_TO_FLAG, pageable);
	}

	@Transactional(readOnly = true)
	public long disputedByCount(Long requirementId) {
		return requirements.countDistinctDisputersFor(requirementId);
	}

	@Transactional(readOnly = true)
	public Page<Requirement> browse(RequirementStatus status, Pageable pageable) {
		return status == null
				? requirements.findAllByOrderByCreatedAtDesc(pageable)
				: requirements.findByStatusOrderByCreatedAtDesc(status, pageable);
	}

	@Transactional(readOnly = true)
	public Page<Requirement> removed(Pageable pageable) {
		return requirements.findByStatusOrderByRemovedAtDesc(RequirementStatus.REMOVED, pageable);
	}

	/**
	 * Takes an enquiry down and refunds every tutor who paid for it.
	 *
	 * <p>The row is locked for the duration. An unlock landing between the refund sweep and the
	 * status change would be a tutor charged for an enquiry that no longer exists and never
	 * refunded — the one outcome this whole method exists to prevent.
	 *
	 * @return how many tutors were refunded
	 */
	@Transactional
	public RemovalResult remove(Long requirementId, Long adminUserId, String reason) {
		Requirement requirement = requirements.findByIdForUpdate(requirementId)
				.orElseThrow(() -> ApiException.notFound("Requirement"));

		RequirementStatus previousStatus = requirement.getStatus();

		try {
			requirement.removeByModerator(adminUserId, reason, clock.instant());
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
		}

		int refunded = refunds.refundAllForRemovedRequirement(requirementId, reason);
		requirements.save(requirement);

		AuditContext.describe("REQUIREMENT_REMOVED", "REQUIREMENT", requirementId);
		AuditContext.summarise(reason);
		AuditContext.before(AuditContext.fields("status", previousStatus.name()));
		AuditContext.after(AuditContext.fields(
				"status", RequirementStatus.REMOVED.name(),
				"reason", reason,
				// The cost of the decision, recorded beside it. A takedown that refunded eleven
				// tutors is a different act from one that refunded nobody.
				"tutorsRefunded", refunded));

		notifications.notify(
				requirement.getStudentId(),
				NotificationType.REQUIREMENT_REMOVED,
				"Your enquiry was removed",
				"We have taken down your enquiry. Reason: " + reason,
				"REQUIREMENT",
				requirementId);

		log.info("Requirement REMOVED: id={} student={} by admin={} refundedTutors={} reason={}",
				requirementId, requirement.getStudentId(), adminUserId, refunded, reason);

		return new RemovalResult(requirement, refunded);
	}

	/**
	 * Puts a wrongly-removed enquiry back.
	 *
	 * <p>The refunds are <strong>not</strong> reversed. Clawing credits back out of a tutor's wallet
	 * days later — possibly into a negative balance, since they may have spent them — to correct our
	 * own mistake would make the mistake theirs. The cost of a wrong takedown stays with the
	 * platform, which is also what keeps the removal decision careful.
	 *
	 * <p>The unlocks stay {@code REFUNDED}, so those tutors no longer hold the lead; the destination
	 * state is recomputed from the unlock count and expiry rather than remembered.
	 */
	@Transactional
	public Requirement restore(Long requirementId, Long adminUserId) {
		Requirement requirement = requirements.findByIdForUpdate(requirementId)
				.orElseThrow(() -> ApiException.notFound("Requirement"));

		String previousReason = requirement.getRemovalReason();

		try {
			requirement.restore(clock.instant());
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
		}

		requirements.save(requirement);

		AuditContext.describe("REQUIREMENT_RESTORED", "REQUIREMENT", requirementId);
		AuditContext.summarise(previousReason);
		AuditContext.before(AuditContext.fields(
				"status", RequirementStatus.REMOVED.name(),
				"reason", previousReason));
		// The restored status is recomputed, not remembered, so the audit entry is the only
		// place that records where it actually landed.
		AuditContext.after(AuditContext.fields("status", requirement.getStatus().name()));

		log.info("Requirement RESTORED: id={} by admin={} newStatus={}",
				requirementId, adminUserId, requirement.getStatus());

		return requirement;
	}

	/**
	 * Takes down every live enquiry belonging to an account being suspended.
	 *
	 * <p>Called from the suspension path, because a suspension that leaves the account's enquiries
	 * on the feed is not a suspension. Tutors would keep spending credits to reach someone we have
	 * just judged fraudulent, and every one of those charges would become a dispute we would uphold.
	 *
	 * <p>Only {@code OPEN} and {@code CAPPED} are touched. A hired or expired enquiry is history —
	 * rewriting it would refund tutors for introductions that worked.
	 *
	 * @return how many enquiries were taken down
	 */
	@Transactional
	public int removeLiveEnquiriesOf(Long studentUserId, Long adminUserId, String reason) {
		List<Requirement> live = requirements.findByStudentIdAndStatusIn(studentUserId, LIVE);

		for (Requirement requirement : live) {
			remove(requirement.getId(), adminUserId, reason);
		}

		return live.size();
	}

	/** A removal and its cost, so the admin screen can say how many tutors were refunded. */
	public record RemovalResult(Requirement requirement, int tutorsRefunded) {
	}
}
