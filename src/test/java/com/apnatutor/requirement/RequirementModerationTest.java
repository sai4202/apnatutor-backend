package com.apnatutor.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.apnatutor.billing.CreditLedger;
import com.apnatutor.billing.RefundRequestRepository;
import com.apnatutor.billing.RefundService;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.billing.domain.RefundReason;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.lead.LeadUnlockRepository;
import com.apnatutor.lead.LeadUnlockService;
import com.apnatutor.lead.domain.LeadUnlock;
import com.apnatutor.lead.domain.UnlockStatus;
import com.apnatutor.requirement.RequirementModerationService.RemovalResult;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.requirement.domain.RequirementStatus;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Spam and fake-lead moderation - M5-05.6.
 *
 * <p>The property worth the most here is that a takedown <strong>costs the platform money</strong>.
 * Removing an enquiry is the platform accepting it sold a lead it should not have, so every tutor
 * who paid gets their credits back without asking. A removal that quietly kept the charges would be
 * the single most damaging thing this feature could do: tutors would learn that a lead can evaporate
 * with their credits inside it.
 */
class RequirementModerationTest extends AbstractIntegrationTest {

	@Autowired
	private RequirementModerationService moderation;

	@Autowired
	private RequirementRepository requirements;

	@Autowired
	private LeadUnlockService unlockService;

	@Autowired
	private LeadUnlockRepository unlocks;

	@Autowired
	private RefundService refunds;

	@Autowired
	private RefundRequestRepository refundRequests;

	@Autowired
	private CreditLedger ledger;

	@Autowired
	private UserRepository users;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private Clock clock;

	@Test
	@DisplayName("removing an enquiry refunds every tutor who had paid for it")
	void removalRefundsEveryTutor() {
		Requirement requirement = postRequirement(5, 5);
		Long first = fundedTutor(20);
		Long second = fundedTutor(20);

		unlockService.unlock(requirement.getId(), first, null);
		unlockService.unlock(requirement.getId(), second, null);
		assertThat(ledger.balanceOf(first)).isEqualTo(15);
		assertThat(ledger.balanceOf(second)).isEqualTo(15);

		RemovalResult result = moderation.remove(
				requirement.getId(), adminId(), "Fake enquiry, the number belongs to a shop");

		assertThat(result.tutorsRefunded())
				.as("THE POINT: a takedown is the platform conceding, so it pays")
				.isEqualTo(2);
		assertThat(ledger.balanceOf(first)).isEqualTo(20);
		assertThat(ledger.balanceOf(second)).isEqualTo(20);

		assertThat(requirements.findById(requirement.getId()).orElseThrow().getStatus())
				.isEqualTo(RequirementStatus.REMOVED);

		assertThat(unlocks.findByRequirementIdAndStatus(requirement.getId(), UnlockStatus.ACTIVE))
				.as("nobody still holds the lead")
				.isEmpty();

		assertThat(ledger.reconcile(first)).isZero();
		assertThat(ledger.reconcile(second)).isZero();
	}

	@Test
	@DisplayName("a takedown does not count as a dispute against the tutors it refunded")
	void removalWritesNoDisputeRows() {
		Requirement requirement = postRequirement(5, 5);
		Long tutorId = fundedTutor(20);
		unlockService.unlock(requirement.getId(), tutorId, null);

		moderation.remove(requirement.getId(), adminId(), "Spam");

		assertThat(refundRequests.countByTutorId(tutorId))
				.as("THE POINT: the tutor complained about nothing. Recording this as a dispute "
						+ "would inflate the one number used to judge whether they game refunds, "
						+ "and would fire the abuse flag on the platform's own mistakes")
				.isZero();
		assertThat(refunds.disputeRateFor(tutorId)).isZero();
	}

	@Test
	@DisplayName("a removal needs a reason, and cannot happen twice")
	void removalIsGuarded() {
		Requirement requirement = postRequirement(5, 5);
		Long admin = adminId();

		assertThatThrownBy(() -> moderation.remove(requirement.getId(), admin, "  "))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_FAILED);

		moderation.remove(requirement.getId(), admin, "Duplicate of #17");

		assertThatThrownBy(() -> moderation.remove(requirement.getId(), admin, "Again"))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.CONFLICT);
	}

	@Test
	@DisplayName("restoring recomputes the status and does not re-charge the refunded tutors")
	void restoreRecomputesAndDoesNotRecharge() {
		// A cap of one, so the single unlock caps it before the takedown.
		Requirement requirement = postRequirement(5, 1);
		Long tutorId = fundedTutor(20);
		unlockService.unlock(requirement.getId(), tutorId, null);
		assertThat(requirements.findById(requirement.getId()).orElseThrow().getStatus())
				.isEqualTo(RequirementStatus.CAPPED);

		Long admin = adminId();
		moderation.remove(requirement.getId(), admin, "Reported as spam");
		assertThat(ledger.balanceOf(tutorId)).isEqualTo(20);

		Requirement restored = moderation.restore(requirement.getId(), admin);

		assertThat(restored.getStatus())
				.as("the refund freed nothing, so it comes back capped rather than open")
				.isEqualTo(RequirementStatus.CAPPED);
		assertThat(restored.getRemovalReason()).isNull();
		assertThat(restored.getRemovedAt()).isNull();

		assertThat(ledger.balanceOf(tutorId))
				.as("THE POINT: the cost of a wrong takedown stays with the platform. Clawing "
						+ "credits back out of a tutor's wallet to fix our mistake makes it theirs")
				.isEqualTo(20);
	}

	@Test
	@DisplayName("an enquiry that expired while it was down comes back expired")
	void restoreOfAnExpiredEnquiryComesBackExpired() {
		Requirement requirement = requirements.save(Requirement.post(
				studentId(), leafSubjectId(), null, null, null,
				"ONLINE", 300_000L, "PER_MONTH", null, null, null,
				"Already past its expiry",
				5, 5,
				clock.instant().minus(Duration.ofDays(1))));

		Long admin = adminId();
		moderation.remove(requirement.getId(), admin, "Spam");

		assertThat(moderation.restore(requirement.getId(), admin).getStatus())
				.as("the destination is recomputed, never remembered - a stored previous state "
						+ "would restore it to a state that is no longer possible")
				.isEqualTo(RequirementStatus.EXPIRED);
	}

	@Test
	@DisplayName("the flagged queue counts distinct tutors, not disputes")
	void flaggedQueueCountsDistinctTutors() {
		Requirement quiet = postRequirement(5, 5);
		Requirement suspicious = postRequirement(5, 5);

		disputeBy(fundedTutor(20), quiet);

		disputeBy(fundedTutor(20), suspicious);
		disputeBy(fundedTutor(20), suspicious);

		assertThat(flaggedIds())
				.as("two tutors is inside the range of ordinary bad luck")
				.doesNotContain(suspicious.getId());

		disputeBy(fundedTutor(20), suspicious);

		assertThat(flaggedIds())
				.as("the third tips it over")
				.contains(suspicious.getId())
				.doesNotContain(quiet.getId());
	}

	@Test
	@DisplayName("a removed enquiry leaves the flagged queue")
	void removedEnquiriesLeaveTheQueue() {
		Requirement suspicious = postRequirement(5, 5);
		disputeBy(fundedTutor(20), suspicious);
		disputeBy(fundedTutor(20), suspicious);
		disputeBy(fundedTutor(20), suspicious);
		assertThat(flaggedIds()).contains(suspicious.getId());

		moderation.remove(suspicious.getId(), adminId(), "Confirmed fake");

		assertThat(flaggedIds())
				.as("a queue is work to do. A moderator who re-skips the same rows stops reading it")
				.doesNotContain(suspicious.getId());
	}

	// --- Fixtures -------------------------------------------------------------------------------

	/**
	 * The ids currently in the flagged queue.
	 *
	 * <p>Asked as a set of ids rather than a size. Test classes share one database within a run, so
	 * a size assertion here passes or fails on what some other test happened to leave behind.
	 */
	private List<Long> flaggedIds() {
		return moderation.flaggedQueue(PageRequest.of(0, 100)).stream()
				.map(Requirement::getId)
				.toList();
	}

	/** Has a tutor unlock the enquiry and then dispute it, which is what the queue counts. */
	private void disputeBy(Long tutorId, Requirement requirement) {
		LeadUnlock unlock = unlockService.unlock(requirement.getId(), tutorId, null);
		refunds.raise(unlock.getId(), tutorId, RefundReason.WRONG_NUMBER, "Not a real number");
	}

	private Requirement postRequirement(int costCredits, int unlockCap) {
		return requirements.save(Requirement.post(
				studentId(), leafSubjectId(), null, null, null,
				"ONLINE", 300_000L, "PER_MONTH", null, null, null,
				"Moderation test requirement",
				costCredits,
				unlockCap,
				clock.instant().plus(Duration.ofDays(30))));
	}

	private Long leafSubjectId() {
		return subjects.findByLeafTrueAndActiveTrueOrderByDisplayOrderAsc().getFirst().getId();
	}

	private Long studentId() {
		return users.save(User.registerVerified(
				uniquePhone("9186"), UserRole.STUDENT, clock.instant())).getId();
	}

	private Long fundedTutor(int credits) {
		User tutor = users.save(User.registerVerified(
				uniquePhone("9185"), UserRole.TUTOR, clock.instant()));
		ledger.grant(tutor.getId(), credits, CreditReason.ADMIN_ADJUSTMENT, "TEST", null, null);
		return tutor.getId();
	}

	private Long adminId() {
		return users.save(User.registerVerified(
				uniquePhone("9184"), UserRole.ADMIN, clock.instant())).getId();
	}

	/** Keeps phone numbers unique without a shared sequence between tests. */
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
