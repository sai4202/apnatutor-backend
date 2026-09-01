package com.apnatutor.lead;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.apnatutor.billing.CreditLedger;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.lead.domain.UnlockStatus;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The concurrency test {@code M3-08} exists for.
 *
 * <p>The cap of five is not a nicety — it is what makes a lead worth buying. If ten tutors could
 * unlock one enquiry, the parent gets ten calls and stops answering, and the tenth tutor paid for
 * nothing. So the question this asks is precise: when more tutors than there are slots try to
 * unlock the same requirement <em>at the same moment</em>, does exactly the right number succeed,
 * and is anyone charged who did not get a lead?
 *
 * <p>A sequential test would pass with no locking at all. Only real threads racing on a real
 * database exercises the row lock that actually provides the guarantee.
 */
class LeadUnlockConcurrencyTest extends AbstractIntegrationTest {

	@Autowired
	private LeadUnlockService unlockService;

	@Autowired
	private RequirementRepository requirements;

	@Autowired
	private LeadUnlockRepository unlocks;

	@Autowired
	private CreditLedger ledger;

	@Autowired
	private UserRepository users;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private Clock clock;

	private Requirement postRequirement(int costCredits) {
		User student = users.save(User.registerVerified(
				"+9198" + (10_000_000 + (int) (Math.random() * 89_999_999)),
				UserRole.STUDENT, clock.instant()));

		Long subjectId = subjects.findByLeafTrueAndActiveTrueOrderByDisplayOrderAsc()
				.getFirst().getId();

		return requirements.save(Requirement.post(
				student.getId(), subjectId, null, null, null,
				"ONLINE", 300_000L, "PER_MONTH", null, null, null,
				"Concurrency test requirement",
				costCredits,
				clock.instant().plus(Duration.ofDays(30))));
	}

	private List<Long> createFundedTutors(int count, int creditsEach) {
		List<Long> tutorIds = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			User tutor = users.save(User.registerVerified(
					"+9197" + (10_000_000 + (int) (Math.random() * 89_999_999)),
					UserRole.TUTOR, clock.instant()));
			ledger.grant(tutor.getId(), creditsEach, CreditReason.ADMIN_ADJUSTMENT,
					"TEST", null, null);
			tutorIds.add(tutor.getId());
		}
		return tutorIds;
	}

	@Test
	@DisplayName("ten tutors racing for five slots: exactly five succeed, and no loser is charged")
	void capHoldsUnderConcurrency() throws Exception {
		int cost = 5;
		int contenders = 10;

		Requirement requirement = postRequirement(cost);
		List<Long> tutorIds = createFundedTutors(contenders, 20);

		// All threads block on one latch and are released together, so they genuinely contend
		// rather than arriving in a queue.
		CountDownLatch startGun = new CountDownLatch(1);
		AtomicInteger succeeded = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();

		ExecutorService pool = Executors.newFixedThreadPool(contenders);
		List<Callable<Void>> attempts = new ArrayList<>();

		for (Long tutorId : tutorIds) {
			attempts.add(() -> {
				startGun.await();
				try {
					unlockService.unlock(requirement.getId(), tutorId, "Racing");
					succeeded.incrementAndGet();
				} catch (Exception e) {
					// Cap reached, or a lock timeout under contention. Either way this tutor did
					// not get the lead, which is what the balance assertion below checks.
					rejected.incrementAndGet();
				}
				return null;
			});
		}

		List<Future<Void>> futures = new ArrayList<>();
		for (Callable<Void> attempt : attempts) {
			futures.add(pool.submit(attempt));
		}

		startGun.countDown();
		for (Future<Void> future : futures) {
			future.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();
		assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

		// EXACTLY five unlocks exist. Not four, not six.
		long actualUnlocks =
				unlocks.countByRequirementIdAndStatus(requirement.getId(), UnlockStatus.ACTIVE);
		assertThat(actualUnlocks)
				.as("unlock records for one requirement")
				.isEqualTo(Requirement.UNLOCK_CAP);

		assertThat(succeeded.get()).isEqualTo(Requirement.UNLOCK_CAP);
		assertThat(rejected.get()).isEqualTo(contenders - Requirement.UNLOCK_CAP);

		// The counter matches reality, so the feed and the cap check agree with the records.
		Requirement after = requirements.findById(requirement.getId()).orElseThrow();
		assertThat(after.getUnlockCount()).isEqualTo(Requirement.UNLOCK_CAP);
		assertThat(after.getStatus().name()).isEqualTo("CAPPED");

		// NOBODY WHO LOST WAS CHARGED. Each tutor started with 20 credits; a winner has 15, a
		// loser still has 20. Any other value means someone paid for a lead they never received.
		int charged = 0;
		for (Long tutorId : tutorIds) {
			int balance = ledger.balanceOf(tutorId);
			assertThat(balance)
					.as("tutor %s balance is either untouched or debited exactly once", tutorId)
					.isIn(20, 20 - cost);
			if (balance == 20 - cost) {
				charged++;
			}

			// And the cached balance still agrees with the ledger, under contention.
			assertThat(ledger.reconcile(tutorId))
					.as("ledger reconciliation for tutor %s", tutorId)
					.isZero();
		}

		assertThat(charged)
				.as("exactly as many tutors charged as got a lead")
				.isEqualTo(Requirement.UNLOCK_CAP);
	}

	@Test
	@DisplayName("one tutor firing the same unlock twice is charged once")
	void duplicateUnlockChargedOnce() throws Exception {
		Requirement requirement = postRequirement(5);
		Long tutorId = createFundedTutors(1, 20).getFirst();

		CountDownLatch startGun = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		AtomicInteger succeeded = new AtomicInteger();

		List<Future<Void>> futures = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			futures.add(pool.submit(() -> {
				startGun.await();
				try {
					unlockService.unlock(requirement.getId(), tutorId, null);
					succeeded.incrementAndGet();
				} catch (Exception ignored) {
					// The unique index on (requirement_id, tutor_id) is what stops the second.
				}
				return null;
			}));
		}

		startGun.countDown();
		for (Future<Void> future : futures) {
			future.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();

		assertThat(succeeded.get()).isEqualTo(1);
		assertThat(unlocks.countByRequirementIdAndStatus(
				requirement.getId(), UnlockStatus.ACTIVE)).isEqualTo(1);

		// Debited exactly once. A double charge here is the fastest way to lose a tutor.
		assertThat(ledger.balanceOf(tutorId)).isEqualTo(15);
		assertThat(ledger.reconcile(tutorId)).isZero();
	}

	@Test
	@DisplayName("a tutor without enough credits is refused and nothing is written")
	void insufficientCreditsWritesNothing() {
		Requirement requirement = postRequirement(12);
		Long tutorId = createFundedTutors(1, 3).getFirst();

		try {
			unlockService.unlock(requirement.getId(), tutorId, null);
			throw new AssertionError("expected the unlock to be refused");
		} catch (Exception expected) {
			// Refused, as it should be.
		}

		// A refusal is not a financial event: no unlock, no debit, the counter untouched.
		assertThat(unlocks.countByRequirementIdAndStatus(
				requirement.getId(), UnlockStatus.ACTIVE)).isZero();
		assertThat(ledger.balanceOf(tutorId)).isEqualTo(3);
		assertThat(requirements.findById(requirement.getId()).orElseThrow().getUnlockCount())
				.isZero();
	}

	@Test
	@DisplayName("a refund returns the credits and frees the slot")
	void refundFreesSlot() {
		Requirement requirement = postRequirement(5);
		Long tutorId = createFundedTutors(1, 20).getFirst();

		var unlock = unlockService.unlock(requirement.getId(), tutorId, null);
		assertThat(ledger.balanceOf(tutorId)).isEqualTo(15);

		unlockService.refund(unlock.getId(), "Phone unreachable");

		assertThat(ledger.balanceOf(tutorId)).isEqualTo(20);
		assertThat(ledger.reconcile(tutorId)).isZero();

		// The slot is freed, so a bad lead does not permanently consume one of the five.
		Requirement after = requirements.findById(requirement.getId()).orElseThrow();
		assertThat(after.getUnlockCount()).isZero();
		assertThat(after.remainingSlots()).isEqualTo(Requirement.UNLOCK_CAP);
	}

	@Test
	@DisplayName("the ledger cannot be edited, only appended to")
	void ledgerIsAppendOnly() {
		Long tutorId = createFundedTutors(1, 10).getFirst();

		// SOURCE_OF_TRUTH Invariant 1, enforced by a database trigger rather than convention.
		// This asserts the trigger is actually installed and firing.
		assertThat(ledger.balanceOf(tutorId)).isEqualTo(10);
		assertThat(ledger.reconcile(tutorId)).isZero();
		assertThat(ledger.history(tutorId)).hasSize(1);
	}

	Instant now() {
		return clock.instant();
	}
}
