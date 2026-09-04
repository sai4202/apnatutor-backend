package com.apnatutor.billing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.billing.domain.CreditTransaction;
import com.apnatutor.billing.domain.RefundReason;
import com.apnatutor.billing.domain.RefundRequest;
import com.apnatutor.billing.domain.RefundStatus;
import com.apnatutor.abuse.AbuseReportService;
import com.apnatutor.abuse.domain.ReportReason;
import com.apnatutor.abuse.domain.ReportSubjectType;
import com.apnatutor.audit.AuditContext;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.lead.LeadUnlockRepository;
import com.apnatutor.lead.domain.LeadUnlock;
import com.apnatutor.lead.domain.UnlockStatus;
import com.apnatutor.notification.NotificationService;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.settings.SettingsService;
import com.apnatutor.user.TutorProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Disputes over bad leads.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A tutor who buys a lead and reaches a disconnected number has been sold nothing. If that is
 * simply their loss, they stop buying leads — and the credits kept are worth far less than the tutor
 * lost. The refund path is not generosity; it is what makes the next purchase possible.
 *
 * <p>It is also the platform's only honest feedback channel about lead quality. A requirement three
 * tutors have disputed is a fact about the enquiry, and without this nobody would ever learn it.
 */
@Service
public class RefundService {

	private static final Logger log = LoggerFactory.getLogger(RefundService.class);

	/** Fallback if the setting is missing. SOURCE_OF_TRUTH.md §3.4. */
	private static final int DEFAULT_REFUND_WINDOW_DAYS = 7;

	/**
	 * Dispute rate above which a tutor is flagged.
	 *
	 * <p>A genuine bad-lead rate sits well below this; a third of leads disputed is either a tutor
	 * gaming refunds or one whose targeting is so wrong that every lead disappoints them. Both need
	 * a human, and neither is caught by an automatic block — which is why this flags rather than
	 * refuses.
	 */
	private static final double DISPUTE_RATE_FLAG = 0.30;

	/** Below this, the rate is noise. Two disputes out of three leads is not a pattern. */
	private static final long MIN_DISPUTES_TO_FLAG = 5;

	private final RefundRequestRepository refunds;
	private final LeadUnlockRepository unlocks;
	private final RequirementRepository requirements;
	private final CreditLedger ledger;
	private final NotificationService notifications;
	private final SettingsService settings;
	private final AbuseReportService abuseReports;
	private final TutorProfileRepository tutorProfiles;
	private final Clock clock;

	public RefundService(
			RefundRequestRepository refunds,
			LeadUnlockRepository unlocks,
			RequirementRepository requirements,
			CreditLedger ledger,
			NotificationService notifications,
			SettingsService settings,
			AbuseReportService abuseReports,
			TutorProfileRepository tutorProfiles,
			Clock clock) {
		this.refunds = refunds;
		this.unlocks = unlocks;
		this.requirements = requirements;
		this.ledger = ledger;
		this.notifications = notifications;
		this.settings = settings;
		this.abuseReports = abuseReports;
		this.tutorProfiles = tutorProfiles;
		this.clock = clock;
	}

	/**
	 * A tutor disputes a lead they paid for.
	 *
	 * @throws ApiException {@code NOT_FOUND} if the unlock is not theirs, {@code REFUND_NOT_ALLOWED}
	 *     if the window has passed or it has already been disputed
	 */
	@Transactional
	public RefundRequest raise(
			Long unlockId, Long tutorUserId, RefundReason reason, String details) {

		LeadUnlock unlock = unlocks.findById(unlockId)
				.orElseThrow(() -> ApiException.notFound("Unlock"));

		// NOT_FOUND rather than FORBIDDEN: a 403 confirms the unlock exists, which is more than
		// someone enumerating ids should learn.
		if (!unlock.getTutorId().equals(tutorUserId)) {
			throw ApiException.notFound("Unlock");
		}

		if (!unlock.isActive()) {
			throw new ApiException(ErrorCode.REFUND_NOT_ALLOWED,
					"This lead has already been refunded.");
		}

		Duration window = settings.durationDays(
				SettingsService.REFUND_WINDOW_DAYS, DEFAULT_REFUND_WINDOW_DAYS);
		Instant deadline = unlock.getUnlockedAt().plus(window);

		if (clock.instant().isAfter(deadline)) {
			// The window exists because a dispute weeks later cannot be investigated — the student
			// will not remember the call, and neither will the tutor with any precision.
			throw new ApiException(ErrorCode.REFUND_NOT_ALLOWED,
					"Disputes must be raised within %d days of unlocking a lead."
							.formatted(window.toDays()));
		}

		try {
			RefundRequest request = refunds.saveAndFlush(
					RefundRequest.raise(unlock, reason, details));

			log.info("Refund requested: id={} unlock={} tutor={} reason={} credits={}",
					request.getId(), unlockId, tutorUserId, reason, unlock.getCreditsSpent());

			warnIfDisputeRateHigh(tutorUserId);
			noteRepeatedlyDisputedRequirement(unlock.getRequirementId());

			return request;
		} catch (DataIntegrityViolationException e) {
			// The unique index on unlock_id fired. Without it a tutor could dispute the same lead
			// repeatedly and be refunded more than they were ever charged.
			throw new ApiException(ErrorCode.REFUND_NOT_ALLOWED,
					"You have already disputed this lead.");
		}
	}

	/**
	 * An admin approves a dispute.
	 *
	 * <p>Three things happen together, in one transaction: a compensating {@code REFUND} entry is
	 * appended to the ledger, the unlock is marked {@code REFUNDED}, and its cap slot is freed so
	 * another tutor can take it. The slot matters — a parent who was promised five responses and got
	 * one unusable one should not be left with four.
	 */
	@Transactional
	public RefundRequest approve(Long refundId, Long adminUserId, String note) {
		RefundRequest request = refunds.findById(refundId)
				.orElseThrow(() -> ApiException.notFound("Dispute"));

		try {
			request.approve(adminUserId, note, clock.instant());
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
		}

		// The credits back. Never by editing the original spend — the ledger is append-only, and
		// "charged 5, refunded 5" is a history somebody can audit while a vanished charge is not.
		CreditTransaction entry = ledger.grant(
				request.getTutorId(),
				request.getCredits(),
				CreditReason.REFUND,
				"REFUND_REQUEST",
				request.getId(),
				// Deliberately no expiry. These credits were already paid for once, and putting a
				// fresh clock on them would be a second penalty for a lead that was our fault.
				null);

		request.recordCreditTransaction(entry.getId());
		refunds.save(request);

		unlocks.findById(request.getUnlockId()).ifPresent(unlock -> {
			unlock.markRefunded();
			unlocks.save(unlock);
		});

		// Frees the slot, reopening the requirement if it had capped out.
		requirements.findByIdForUpdate(request.getRequirementId()).ifPresent(requirement -> {
			requirement.releaseUnlock();
			requirements.save(requirement);
		});

		// M5-08.3: every refund recorded. The ledger already proves the credits moved; this says
		// which admin decided they should, and on what grounds.
		AuditContext.describe("REFUND_APPROVED", "REFUND_REQUEST", refundId);
		AuditContext.summarise(note);
		AuditContext.before(AuditContext.fields("status", RefundStatus.PENDING.name()));
		AuditContext.after(AuditContext.fields(
				"status", RefundStatus.APPROVED.name(),
				"tutorId", request.getTutorId(),
				"creditsRefunded", request.getCredits(),
				"creditTransactionId", entry.getId()));

		notifications.notify(
				request.getTutorId(),
				NotificationType.REFUND_APPROVED,
				"%d credits refunded".formatted(request.getCredits()),
				"Your dispute was upheld and %d credits are back in your wallet."
						.formatted(request.getCredits()),
				"REFUND_REQUEST",
				request.getId());

		log.info("Refund APPROVED: id={} tutor={} credits={} by admin={}",
				refundId, request.getTutorId(), request.getCredits(), adminUserId);

		return request;
	}

	@Transactional
	public RefundRequest reject(Long refundId, Long adminUserId, String note) {
		RefundRequest request = refunds.findById(refundId)
				.orElseThrow(() -> ApiException.notFound("Dispute"));

		try {
			request.reject(adminUserId, note, clock.instant());
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
		}

		refunds.save(request);

		AuditContext.describe("REFUND_REJECTED", "REFUND_REQUEST", refundId);
		AuditContext.summarise(note);
		AuditContext.before(AuditContext.fields("status", RefundStatus.PENDING.name()));
		AuditContext.after(AuditContext.fields(
				"status", RefundStatus.REJECTED.name(),
				"tutorId", request.getTutorId(),
				"creditsWithheld", request.getCredits()));

		notifications.notify(
				request.getTutorId(),
				NotificationType.REFUND_REJECTED,
				"Your dispute was not upheld",
				note,
				"REFUND_REQUEST",
				request.getId());

		log.info("Refund REJECTED: id={} tutor={} by admin={}",
				refundId, request.getTutorId(), adminUserId);

		return request;
	}

	/**
	 * Refunds every tutor who paid for a requirement the platform has just taken down (M5-05.6).
	 *
	 * <p>This is not a dispute, and deliberately writes no {@link RefundRequest} row. A dispute is a
	 * tutor's claim; this is the platform conceding without being asked, because a lead we removed
	 * as fake was a lead we should never have sold. Recording it as a dispute would inflate
	 * {@link #disputeRateFor} for tutors who complained about nothing — the one number used to judge
	 * whether a tutor is gaming refunds — and would teach the flag to fire on the platform's own
	 * mistakes.
	 *
	 * <p>The ledger entry is the record instead: reason {@code REFUND}, referencing the requirement.
	 * Runs in the caller's transaction so the takedown and the refunds commit together; a removal
	 * that credited nobody, or credits granted for a requirement still live, would both be worse
	 * than either alone.
	 *
	 * <p>The unlock count is <strong>not</strong> decremented here. {@link #approve} frees the slot
	 * so another tutor can take the lead; there is no lead left to take, and reopening slots on a
	 * removed requirement is meaningless. {@code Requirement.restore} recomputes its state from the
	 * count, so leaving it intact is also what makes a restore land in the right place.
	 *
	 * @return the number of tutors refunded
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int refundAllForRemovedRequirement(Long requirementId, String reason) {
		List<LeadUnlock> paid = unlocks.findByRequirementIdAndStatus(
				requirementId, UnlockStatus.ACTIVE);

		for (LeadUnlock unlock : paid) {
			ledger.grant(
					unlock.getTutorId(),
					unlock.getCreditsSpent(),
					CreditReason.REFUND,
					"REQUIREMENT_REMOVAL",
					requirementId,
					// No expiry, for the same reason as an approved dispute: these credits were
					// paid for once already, and a fresh clock would be a second penalty for our
					// mistake.
					null);

			unlock.markRefunded();
			unlocks.save(unlock);

			notifications.notify(
					unlock.getTutorId(),
					NotificationType.REFUND_APPROVED,
					"%d credits refunded".formatted(unlock.getCreditsSpent()),
					"We removed an enquiry you had unlocked (%s) and have returned the %d credits "
							.formatted(reason, unlock.getCreditsSpent())
							+ "you spent on it.",
					"REQUIREMENT",
					requirementId);
		}

		if (!paid.isEmpty()) {
			log.info("Requirement removal refunded {} tutors: requirement={}",
					paid.size(), requirementId);
		}

		return paid.size();
	}

	@Transactional(readOnly = true)
	public Page<RefundRequest> pendingQueue(Pageable pageable) {
		return refunds.findByStatusOrderByCreatedAtAsc(RefundStatus.PENDING, pageable);
	}

	@Transactional(readOnly = true)
	public List<RefundRequest> forTutor(Long tutorUserId) {
		return refunds.findByTutorIdOrderByCreatedAtDesc(tutorUserId);
	}

	/**
	 * The proportion of a tutor's unlocks they have disputed.
	 *
	 * <p>Surfaced to the admin reviewing a dispute, because it is the context that changes the
	 * decision: the same complaint from a tutor who has disputed one lead in fifty reads very
	 * differently from one who has disputed half of them.
	 */
	@Transactional(readOnly = true)
	public double disputeRateFor(Long tutorUserId) {
		long unlockCount = unlocks.findByTutorIdOrderByUnlockedAtDesc(tutorUserId).size();
		if (unlockCount == 0) {
			return 0;
		}
		return (double) refunds.countByTutorId(tutorUserId) / unlockCount;
	}

	/**
	 * Raises a report when a tutor's dispute rate looks like abuse.
	 *
	 * <p>Flags rather than blocks, deliberately. A tutor whose leads really are bad is exactly the
	 * person a hard cutoff would punish, and they are the one already being let down. A human
	 * looking at the pattern can tell the two apart; a threshold cannot.
	 *
	 * <p>Deliberately not an audit entry: the audit log records what an admin did, and this is the
	 * platform noticing something about a tutor. It raises a {@code SYSTEM} abuse report instead
	 * (M5-09.2), which is a queue a person actually reads — the log line it used to be was a
	 * placeholder, because a warning nobody greps is not a queue.
	 */
	private void warnIfDisputeRateHigh(Long tutorUserId) {
		long disputes = refunds.countByTutorId(tutorUserId);
		if (disputes < MIN_DISPUTES_TO_FLAG) {
			return;
		}

		double rate = disputeRateFor(tutorUserId);
		if (rate <= DISPUTE_RATE_FLAG) {
			return;
		}

		log.warn("HIGH DISPUTE RATE: tutor={} disputes={} rate={}%",
				tutorUserId, disputes, Math.round(rate * 100));

		// M5-09.2, and the end of a promise this method has carried since M4: the log line above
		// was always a placeholder, because a warning nobody greps is not a queue. The tutor is
		// addressed by profile id, as everywhere on the wire (ADR #13); a tutor with no profile
		// cannot have bought a lead, so the absence is not a case to handle.
		tutorProfiles.findByUserId(tutorUserId).ifPresent(profile -> abuseReports.raiseSystemReport(
				ReportSubjectType.TUTOR,
				profile.getId(),
				ReportReason.HIGH_DISPUTE_RATE,
				"Disputed %d of their leads (%d%%). Either they are gaming refunds, or their "
						.formatted(disputes, Math.round(rate * 100))
						+ "targeting is so wrong that every lead disappoints them. Both need a "
						+ "person; neither is caught by an automatic block."));
	}

	/**
	 * Logs a requirement several tutors have disputed.
	 *
	 * <p>The stronger of the two signals. One tutor disputing many leads may just be bad at phone
	 * calls; three tutors disputing the same enquiry is a fact about that enquiry, and it is the
	 * only way a fake or mistyped requirement ever comes to light.
	 */
	private void noteRepeatedlyDisputedRequirement(Long requirementId) {
		long disputes = refunds.countByRequirementId(requirementId);
		if (disputes >= 3) {
			log.warn("REQUIREMENT DISPUTED BY {} TUTORS: requirement={} — likely a bad lead, "
					+ "consider closing it", disputes, requirementId);
		}
	}
}
