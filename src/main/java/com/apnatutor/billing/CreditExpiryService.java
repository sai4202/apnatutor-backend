package com.apnatutor.billing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.apnatutor.billing.domain.CreditGrantExpiry;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.billing.domain.CreditTransaction;
import com.apnatutor.notification.NotificationService;
import com.apnatutor.notification.domain.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes off credits that have lapsed.
 *
 * <h2>Expiry is a ledger entry, not an edit</h2>
 *
 * <p>A lapsed grant is not modified. A separate negative entry is appended with reason
 * {@link CreditReason#EXPIRY}, referencing the grant it cancels. That is forced by the append-only
 * invariant, but it is also the right answer independently: "you were granted 10 and 10 lapsed on
 * this date" is a history a tutor can be shown and an accountant can audit, whereas a grant that
 * quietly shrank explains nothing to anyone.
 *
 * <h2>What stops the job reprocessing the same grants</h2>
 *
 * <p>A row in {@code credit_grant_expiries}, keyed on the grant. Not the presence of a compensating
 * ledger entry: a grant that was fully spent before it lapsed produces no entry, because nothing
 * moved, and the database rightly refuses a zero-amount one. Keying off the ledger would return
 * those grants on every run forever.
 *
 * <h2>Never below zero</h2>
 *
 * <p>Credits are fungible — a spend is not attributed to the grant it came from — so a tutor granted
 * 10 who has spent 8 still has a 10-credit grant on record when it lapses. Writing off the full 10
 * would take their balance to -2 and effectively bill them for credits they already used and paid
 * for. So each write-off is capped at the current balance.
 *
 * <p>The consequence is a deliberate bias: credits already spent are never clawed back. Given a
 * choice between under- and over-charging a tutor here, the platform takes the loss.
 */
@Service
public class CreditExpiryService {

	private static final Logger log = LoggerFactory.getLogger(CreditExpiryService.class);

	/** How much warning a tutor gets before credits lapse. */
	static final Duration WARNING_WINDOW = Duration.ofDays(14);

	private final CreditTransactionRepository transactions;
	private final CreditGrantExpiryRepository processed;
	private final CreditLedger ledger;
	private final NotificationService notifications;
	private final Clock clock;

	public CreditExpiryService(
			CreditTransactionRepository transactions,
			CreditGrantExpiryRepository processed,
			CreditLedger ledger,
			NotificationService notifications,
			Clock clock) {
		this.transactions = transactions;
		this.processed = processed;
		this.ledger = ledger;
		this.notifications = notifications;
		this.clock = clock;
	}

	/**
	 * Expires everything past its date.
	 *
	 * @return how many credits were written off in total
	 */
	@Transactional
	public int expireLapsed() {
		Instant now = clock.instant();
		List<CreditTransaction> lapsed = transactions.findLapsedGrants(now);
		if (lapsed.isEmpty()) {
			return 0;
		}

		// Balances are tracked per tutor across the loop rather than re-read each time: several
		// grants for one tutor can lapse together, and each write-off moves the floor for the next.
		Map<Long, Integer> remaining = new HashMap<>();
		int total = 0;

		for (CreditTransaction grant : lapsed) {
			Long tutorId = grant.getTutorId();
			int balance = remaining.computeIfAbsent(tutorId, ledger::balanceOf);

			// Capped at the balance. See the class javadoc: spent credits are never clawed back.
			int writeOff = Math.min(grant.getAmount(), balance);
			if (writeOff <= 0) {
				// Fully spent before it lapsed, which is the outcome everyone wanted. Nothing
				// moved, so there is no ledger entry — only a note that this grant is dealt with.
				processed.save(CreditGrantExpiry.spentBeforeLapsing(grant.getId(), tutorId));
				continue;
			}

			CreditTransaction entry = ledger.spend(
					tutorId, writeOff, CreditReason.EXPIRY, "CREDIT_TXN", grant.getId());
			processed.save(CreditGrantExpiry.writtenOff(
					grant.getId(), tutorId, writeOff, entry.getId()));

			remaining.put(tutorId, balance - writeOff);
			total += writeOff;

			log.info("Credits expired: tutor={} grant={} credits={} grantedFor={}",
					tutorId, grant.getId(), writeOff, grant.getReason());
		}

		log.info("Credit expiry run: {} grant(s) processed, {} credit(s) written off",
				lapsed.size(), total);

		return total;
	}

	/**
	 * Warns tutors whose credits are about to lapse.
	 *
	 * <p>Sent at all because the alternative is a tutor discovering the loss afterwards, which reads
	 * as the platform taking something from them however clearly the terms said otherwise. Two weeks
	 * is enough notice to actually use them.
	 *
	 * @return how many tutors were warned
	 */
	@Transactional
	public int warnAboutExpiring() {
		Instant now = clock.instant();
		List<CreditTransaction> lapsing =
				transactions.findGrantsLapsingBetween(now, now.plus(WARNING_WINDOW));

		// One notification per tutor, not per grant. Three grants lapsing the same week is one
		// piece of news, and three messages about it is the reason people mute notifications.
		Map<Long, Integer> byTutor = new HashMap<>();
		for (CreditTransaction grant : lapsing) {
			byTutor.merge(grant.getTutorId(), grant.getAmount(), Integer::sum);
		}

		byTutor.forEach((tutorId, credits) -> {
			int capped = Math.min(credits, ledger.balanceOf(tutorId));
			if (capped <= 0) {
				return;
			}
			notifications.notify(
					tutorId,
					NotificationType.CREDITS_EXPIRING,
					"%d credits expiring soon".formatted(capped),
					("%d of your credits lapse within the next two weeks. Unlocking an enquiry "
							+ "uses the oldest ones first.").formatted(capped),
					"WALLET",
					null);
		});

		if (!byTutor.isEmpty()) {
			log.info("Expiry warnings sent to {} tutor(s)", byTutor.size());
		}

		return byTutor.size();
	}

}
