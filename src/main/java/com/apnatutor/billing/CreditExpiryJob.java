package com.apnatutor.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the credit expiry and its warning.
 *
 * <p>Daily rather than hourly, unlike {@code RequirementExpiryJob}. Credit validity is measured in
 * months, so the exact hour a credit lapses matters to nobody — and every run of this job writes
 * ledger entries and can send notifications, which is not work worth doing twenty-four times a day
 * to gain precision no one will notice.
 *
 * <p><strong>Single-instance assumption</strong>, and a stronger one than the requirement job's.
 * That job's bulk UPDATE is naturally idempotent; this one writes ledger entries. Two instances
 * running simultaneously could each write off the same grant, because the "already expired?" check
 * and the write are not atomic across processes. Safe today with one instance; scaling out needs a
 * lock here before anything else (`M6-07`).
 */
@Component
public class CreditExpiryJob {

	private static final Logger log = LoggerFactory.getLogger(CreditExpiryJob.class);

	private static final long DAILY = 24 * 60 * 60 * 1000L;

	private final CreditExpiryService expiry;

	public CreditExpiryJob(CreditExpiryService expiry) {
		this.expiry = expiry;
	}

	/** Offset from the warning run so the two never overlap on a slow database. */
	@Scheduled(fixedDelay = DAILY, initialDelay = 5 * 60 * 1000)
	public void expireLapsedCredits() {
		try {
			expiry.expireLapsed();
		} catch (RuntimeException e) {
			// Caught deliberately. An uncaught exception in a scheduled task silently cancels every
			// future run of that schedule in Spring, so one transient database blip would stop
			// credit expiry permanently with nothing to show for it.
			log.error("Credit expiry job failed; will retry next run", e);
		}
	}

	@Scheduled(fixedDelay = DAILY, initialDelay = 10 * 60 * 1000)
	public void warnAboutExpiringCredits() {
		try {
			expiry.warnAboutExpiring();
		} catch (RuntimeException e) {
			log.error("Credit expiry warning job failed; will retry next run", e);
		}
	}
}
