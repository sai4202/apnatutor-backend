package com.apnatutor.user;

import java.time.Clock;

import com.apnatutor.billing.CreditLedger;
import com.apnatutor.billing.RefundRequestRepository;
import com.apnatutor.audit.AuditContext;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.lead.LeadUnlockRepository;
import com.apnatutor.lead.domain.UnlockStatus;
import com.apnatutor.notification.NotificationService;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.requirement.RequirementModerationService;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.domain.RequirementStatus;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import com.apnatutor.user.domain.UserStatus;
import com.apnatutor.user.dto.AdminUserDtos.UserDetail;
import com.apnatutor.user.dto.AdminUserDtos.UserRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suspending and reinstating accounts - M5-05.3.
 *
 * <h2>A suspension has to actually do something</h2>
 *
 * <p>Setting {@code status = SUSPENDED} blocks the login path and nothing else. On its own that
 * would leave a suspended tutor in search results and a suspended student's enquiries on the lead
 * feed, still being paid for. Two things close that:
 *
 * <ul>
 *   <li>The search query and the public profile lookup both require an {@code ACTIVE} account, so a
 *       suspended tutor disappears from discovery the moment the row changes - no flag to set, and
 *       nothing for a future code path to forget.
 *   <li>Suspending a student takes down their live enquiries, which refunds the tutors who had paid
 *       for them. Continuing to sell introductions to an account we have just judged fraudulent
 *       would mean charging tutors for leads we would then refund one dispute at a time.
 * </ul>
 *
 * <p>The asymmetry is deliberate: a tutor's unlocks are not undone by their suspension. They paid
 * for introductions that already happened, and the students on the other end are real.
 */
@Service
public class UserAdminService {

	private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

	private final UserRepository users;
	private final RequirementRepository requirements;
	private final RequirementModerationService moderation;
	private final LeadUnlockRepository unlocks;
	private final RefundRequestRepository refunds;
	private final CreditLedger ledger;
	private final NotificationService notifications;
	private final Clock clock;

	public UserAdminService(
			UserRepository users,
			RequirementRepository requirements,
			RequirementModerationService moderation,
			LeadUnlockRepository unlocks,
			RefundRequestRepository refunds,
			CreditLedger ledger,
			NotificationService notifications,
			Clock clock) {
		this.users = users;
		this.requirements = requirements;
		this.moderation = moderation;
		this.unlocks = unlocks;
		this.refunds = refunds;
		this.ledger = ledger;
		this.notifications = notifications;
		this.clock = clock;
	}

	/**
	 * The admin user list.
	 *
	 * @param phoneFragment any part of a phone number, or null. Support takes calls, and a caller
	 *     reads out the last few digits rather than the E.164 form we store.
	 */
	@Transactional(readOnly = true)
	public Page<User> search(
			UserRole role, UserStatus status, String phoneFragment, Pageable pageable) {

		String phonePattern = phoneFragment == null || phoneFragment.isBlank()
				? null
				: "%" + phoneFragment.strip() + "%";

		return users.search(
				role == null ? null : role.name(),
				status == null ? null : status.name(),
				phonePattern,
				pageable);
	}

	@Transactional(readOnly = true)
	public UserDetail detail(Long userId) {
		return describe(users.findById(userId).orElseThrow(() -> ApiException.notFound("User")));
	}

	/**
	 * Blocks an account.
	 *
	 * <p>Refuses to suspend an admin. Not because admins are above it, but because an admin who
	 * suspends the last admin locks everybody out of the console with no way back in through the
	 * product, and the state that gets you there is one misclick in a user list.
	 *
	 * @return the account, and how many of its live enquiries were taken down as a consequence
	 */
	@Transactional
	public SuspensionResult suspend(Long userId, Long adminUserId, String reason) {
		User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));

		if (user.getRole() == UserRole.ADMIN) {
			throw ApiException.forbidden("Admin accounts cannot be suspended from the console.");
		}
		if (userId.equals(adminUserId)) {
			// Unreachable while admins cannot be suspended at all, but the check is cheap, and the
			// day someone relaxes the rule above this is the failure they will not have thought of.
			throw ApiException.forbidden("You cannot suspend your own account.");
		}

		try {
			user.suspend(adminUserId, reason, clock.instant());
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
		}

		users.save(user);

		// Students only. See the class javadoc for why a tutor's history is left alone.
		int enquiriesRemoved = user.getRole() == UserRole.STUDENT
				? moderation.removeLiveEnquiriesOf(
						userId, adminUserId, "The account that posted it was suspended")
				: 0;

		// Described AFTER the cascade, deliberately. removeLiveEnquiriesOf reaches
		// RequirementModerationService.remove, which describes itself as REQUIREMENT_REMOVED — and
		// there is one audit draft per request, so the last writer wins. The admin took one action;
		// it should be logged as the suspension it was, with the cascade as a field on it rather
		// than as N entries that hide the decision behind its consequences.
		AuditContext.describe("USER_SUSPENDED", "USER", userId);
		AuditContext.summarise(reason);
		AuditContext.before(AuditContext.fields("status", UserStatus.ACTIVE.name()));
		AuditContext.after(AuditContext.fields(
				"status", UserStatus.SUSPENDED.name(),
				"reason", reason,
				"enquiriesRemoved", enquiriesRemoved));

		notifications.notify(
				userId,
				NotificationType.ACCOUNT_SUSPENDED,
				"Your account has been suspended",
				"Reason: " + reason,
				"USER",
				userId);

		log.info("Account SUSPENDED: user={} role={} by admin={} enquiriesRemoved={} reason={}",
				userId, user.getRole(), adminUserId, enquiriesRemoved, reason);

		return new SuspensionResult(describe(user), enquiriesRemoved);
	}

	/**
	 * Lifts a suspension.
	 *
	 * <p>Enquiries taken down by the suspension are <strong>not</strong> restored. Their tutors have
	 * already been refunded and told the enquiry was removed; putting it back would mean either
	 * charging them again for a lead they were told was dead, or leaving a live enquiry with slots
	 * nobody can use. If the student still wants a tutor, posting again is free and takes a minute.
	 */
	@Transactional
	public UserDetail reinstate(Long userId, Long adminUserId) {
		User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));

		// Read before reinstate() clears it — the audit entry has to say what the suspension was for.
		String previousReason = user.getSuspensionReason();

		try {
			user.reinstate();
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
		}

		users.save(user);

		AuditContext.describe("USER_REINSTATED", "USER", userId);
		AuditContext.before(AuditContext.fields(
				"status", UserStatus.SUSPENDED.name(),
				"reason", previousReason));
		AuditContext.after(AuditContext.fields("status", UserStatus.ACTIVE.name()));

		notifications.notify(
				userId,
				NotificationType.ACCOUNT_REINSTATED,
				"Your account is active again",
				"You can sign in as usual.",
				"USER",
				userId);

		log.info("Account REINSTATED: user={} by admin={}", userId, adminUserId);

		return describe(user);
	}

	/** Builds the detail view, leaving the other role's counts null. */
	private UserDetail describe(User user) {
		boolean tutor = user.getRole() == UserRole.TUTOR;
		boolean student = user.getRole() == UserRole.STUDENT;
		Long id = user.getId();

		return new UserDetail(
				UserRow.from(user),
				user.getSuspendedBy(),
				tutor ? ledger.balanceOf(id) : null,
				tutor ? unlocks.countByTutorIdAndStatus(id, UnlockStatus.ACTIVE) : null,
				tutor ? refunds.countByTutorId(id) : null,
				student ? requirements.countByStudentId(id) : null,
				student ? liveEnquiries(id) : null,
				student
						? requirements.countByStudentIdAndStatus(id, RequirementStatus.REMOVED)
						: null);
	}

	private long liveEnquiries(Long studentId) {
		return requirements.countByStudentIdAndStatus(studentId, RequirementStatus.OPEN)
				+ requirements.countByStudentIdAndStatus(studentId, RequirementStatus.CAPPED);
	}

	/** A suspension and its blast radius, so the admin screen can report what else it changed. */
	public record SuspensionResult(UserDetail user, int enquiriesRemoved) {
	}
}
