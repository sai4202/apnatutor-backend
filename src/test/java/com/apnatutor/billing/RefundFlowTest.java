package com.apnatutor.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.billing.domain.CreditTransaction;
import com.apnatutor.billing.domain.RefundReason;
import com.apnatutor.billing.domain.RefundRequest;
import com.apnatutor.billing.domain.RefundStatus;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.lead.LeadUnlockRepository;
import com.apnatutor.lead.LeadUnlockService;
import com.apnatutor.lead.domain.LeadUnlock;
import com.apnatutor.lead.domain.UnlockStatus;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.requirement.domain.RequirementStatus;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Disputes over bad leads — {@code M4-06}.
 *
 * <p>The property that matters most is the one that is easiest to forget: an approved refund must
 * <strong>free the enquiry's response slot</strong>. A parent promised five responses who got one
 * unusable one should end up with five usable ones, not four. Refunding the credits while leaving
 * the slot consumed quietly makes every refund cost the student something too.
 */
class RefundFlowTest extends AbstractIntegrationTest {

	@Autowired
	private RefundService refunds;

	@Autowired
	private RefundRequestRepository refundRequests;

	@Autowired
	private LeadUnlockService unlockService;

	@Autowired
	private LeadUnlockRepository unlocks;

	@Autowired
	private RequirementRepository requirements;

	@Autowired
	private CreditLedger ledger;

	@Autowired
	private CreditTransactionRepository transactions;

	@Autowired
	private UserRepository users;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private Clock clock;

	@Test
	@DisplayName("an approved dispute returns the credits and frees the response slot")
	void approvalRefundsAndFreesTheSlot() {
		Requirement requirement = postRequirement(5, 2);
		Long tutorId = fundedTutor(20);

		LeadUnlock unlock = unlockService.unlock(requirement.getId(), tutorId, null);
		assertThat(ledger.balanceOf(tutorId)).isEqualTo(15);

		Requirement afterUnlock = requirements.findById(requirement.getId()).orElseThrow();
		assertThat(afterUnlock.remainingSlots()).isEqualTo(1);

		RefundRequest dispute = refunds.raise(
				unlock.getId(), tutorId, RefundReason.WRONG_NUMBER, "Number does not exist");
		refunds.approve(dispute.getId(), adminId(), "Confirmed, the number is unallocated");

		assertThat(ledger.balanceOf(tutorId))
				.as("the credits come back")
				.isEqualTo(20);

		assertThat(unlocks.findById(unlock.getId()).orElseThrow().getStatus())
				.isEqualTo(UnlockStatus.REFUNDED);

		Requirement afterRefund = requirements.findById(requirement.getId()).orElseThrow();
		assertThat(afterRefund.remainingSlots())
				.as("THE POINT: the slot is freed, so the parent still gets the responses "
						+ "they were promised")
				.isEqualTo(2);

		assertThat(ledger.reconcile(tutorId)).isZero();
	}

	@Test
	@DisplayName("a refund reopens a requirement that had capped out")
	void refundReopensACappedRequirement() {
		// A cap of one, so a single unlock fills it.
		Requirement requirement = postRequirement(5, 1);
		Long tutorId = fundedTutor(20);

		LeadUnlock unlock = unlockService.unlock(requirement.getId(), tutorId, null);
		assertThat(requirements.findById(requirement.getId()).orElseThrow().getStatus())
				.isEqualTo(RequirementStatus.CAPPED);

		RefundRequest dispute = refunds.raise(
				unlock.getId(), tutorId, RefundReason.NOT_LOOKING, "Said they never posted this");
		refunds.approve(dispute.getId(), adminId(), "Upheld");

		assertThat(requirements.findById(requirement.getId()).orElseThrow().getStatus())
				.as("a capped enquiry with a freed slot is open again, otherwise a refund "
						+ "silently ends the parent's search")
				.isEqualTo(RequirementStatus.OPEN);
	}

	@Test
	@DisplayName("the refund is a compensating entry, not an edit")
	void refundIsAppendOnly() {
		Requirement requirement = postRequirement(5, 2);
		Long tutorId = fundedTutor(20);

		LeadUnlock unlock = unlockService.unlock(requirement.getId(), tutorId, null);
		RefundRequest dispute = refunds.raise(
				unlock.getId(), tutorId, RefundReason.UNREACHABLE, "Called six times");
		refunds.approve(dispute.getId(), adminId(), "Upheld");

		var reasons = transactions.findByTutorIdOrderByCreatedAtDesc(tutorId).stream()
				.map(CreditTransaction::getReason)
				.toList();

		// "Charged 5, refunded 5" is a history a tutor can be shown and an accountant can audit.
		// A charge that quietly disappeared explains nothing to either of them.
		assertThat(reasons).contains(CreditReason.UNLOCK, CreditReason.REFUND);
		assertThat(ledger.reconcile(tutorId)).isZero();
	}

	@Test
	@DisplayName("refunded credits do not carry a fresh expiry")
	void refundedCreditsDoNotExpireSooner() {
		Requirement requirement = postRequirement(5, 2);
		Long tutorId = fundedTutor(20);

		LeadUnlock unlock = unlockService.unlock(requirement.getId(), tutorId, null);
		RefundRequest dispute = refunds.raise(
				unlock.getId(), tutorId, RefundReason.ALREADY_HIRED, "Hired last week");
		refunds.approve(dispute.getId(), adminId(), "Upheld");

		var refundEntry = transactions.findByTutorIdOrderByCreatedAtDesc(tutorId).stream()
				.filter(entry -> entry.getReason() == CreditReason.REFUND)
				.findFirst()
				.orElseThrow();

		// These credits were paid for once already. Putting a fresh clock on them would be a
		// second penalty for a lead that was not the tutor's fault.
		assertThat(refundEntry.getExpiresAt()).isNull();
	}

	@Test
	@DisplayName("the same lead cannot be disputed twice")
	void oneDisputePerUnlock() {
		Requirement requirement = postRequirement(5, 2);
		Long tutorId = fundedTutor(20);

		LeadUnlock unlock = unlockService.unlock(requirement.getId(), tutorId, null);
		refunds.raise(unlock.getId(), tutorId, RefundReason.UNREACHABLE, "No answer");

		// Without the unique index a tutor could dispute repeatedly and be refunded more than
		// they were ever charged.
		assertThatThrownBy(() -> refunds.raise(
				unlock.getId(), tutorId, RefundReason.WRONG_NUMBER, "Trying again"))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.REFUND_NOT_ALLOWED);
	}

	@Test
	@DisplayName("a tutor cannot dispute someone else's unlock")
	void cannotDisputeAnotherTutorsUnlock() {
		Requirement requirement = postRequirement(5, 2);
		Long tutorId = fundedTutor(20);
		Long otherTutorId = fundedTutor(20);

		LeadUnlock unlock = unlockService.unlock(requirement.getId(), tutorId, null);

		// NOT_FOUND, not FORBIDDEN: a 403 would confirm the unlock exists.
		assertThatThrownBy(() -> refunds.raise(
				unlock.getId(), otherTutorId, RefundReason.UNREACHABLE, "Not mine"))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	@Test
	@DisplayName("a rejected dispute refunds nothing and keeps the slot consumed")
	void rejectionChangesNothingFinancial() {
		Requirement requirement = postRequirement(5, 2);
		Long tutorId = fundedTutor(20);

		LeadUnlock unlock = unlockService.unlock(requirement.getId(), tutorId, null);
		RefundRequest dispute = refunds.raise(
				unlock.getId(), tutorId, RefundReason.OTHER, "Did not like the tone");

		refunds.reject(dispute.getId(), adminId(), "The student confirmed they are still looking.");

		assertThat(ledger.balanceOf(tutorId)).isEqualTo(15);
		assertThat(unlocks.findById(unlock.getId()).orElseThrow().getStatus())
				.isEqualTo(UnlockStatus.ACTIVE);
		assertThat(requirements.findById(requirement.getId()).orElseThrow().remainingSlots())
				.isEqualTo(1);
		assertThat(refundRequests.findById(dispute.getId()).orElseThrow().getStatus())
				.isEqualTo(RefundStatus.REJECTED);
	}

	@Test
	@DisplayName("a rejection must carry a reason the tutor can read")
	void rejectionRequiresANote() {
		Requirement requirement = postRequirement(5, 2);
		Long tutorId = fundedTutor(20);

		LeadUnlock unlock = unlockService.unlock(requirement.getId(), tutorId, null);
		RefundRequest dispute = refunds.raise(
				unlock.getId(), tutorId, RefundReason.OTHER, "Something was wrong");

		// A rejection with no reason is one a tutor can neither argue with nor learn from, and it
		// is where the belief that disputes are pointless comes from.
		assertThatThrownBy(() -> refunds.reject(dispute.getId(), adminId(), "  "))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_FAILED);
	}

	@Test
	@DisplayName("a decided dispute cannot be decided again")
	void cannotDecideTwice() {
		Requirement requirement = postRequirement(5, 2);
		Long tutorId = fundedTutor(20);

		LeadUnlock unlock = unlockService.unlock(requirement.getId(), tutorId, null);
		RefundRequest dispute = refunds.raise(
				unlock.getId(), tutorId, RefundReason.UNREACHABLE, "No answer");
		refunds.approve(dispute.getId(), adminId(), "Upheld");

		// Approving twice would grant the credits twice.
		assertThatThrownBy(() -> refunds.approve(dispute.getId(), adminId(), "Again"))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.CONFLICT);

		assertThat(ledger.balanceOf(tutorId)).isEqualTo(20);
	}

	// --- Helpers ------------------------------------------------------------------------------

	private Requirement postRequirement(int costCredits, int unlockCap) {
		User student = users.save(User.registerVerified(
				uniquePhone("9196"), UserRole.STUDENT, clock.instant()));

		Long subjectId = subjects.findByLeafTrueAndActiveTrueOrderByDisplayOrderAsc()
				.getFirst().getId();

		return requirements.save(Requirement.post(
				student.getId(), subjectId, null, null, null,
				"ONLINE", 300_000L, "PER_MONTH", null, null, null,
				"Refund test requirement",
				costCredits,
				unlockCap,
				clock.instant().plus(Duration.ofDays(30))));
	}

	private Long fundedTutor(int credits) {
		User tutor = users.save(User.registerVerified(
				uniquePhone("9195"), UserRole.TUTOR, clock.instant()));
		ledger.grant(tutor.getId(), credits, CreditReason.ADMIN_ADJUSTMENT, "TEST", null, null);
		return tutor.getId();
	}

	private Long adminId() {
		return users.save(User.registerVerified(
				uniquePhone("9194"), UserRole.ADMIN, clock.instant())).getId();
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
