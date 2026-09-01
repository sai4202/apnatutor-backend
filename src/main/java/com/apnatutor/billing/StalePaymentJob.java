package com.apnatutor.billing;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clears out orders that were started and never completed.
 *
 * <p>Every abandoned checkout leaves a {@code CREATED} row behind. Without this they accumulate
 * indefinitely, and the number that matters — payments genuinely stuck between the tutor's bank and
 * us — becomes invisible in the noise.
 *
 * <p>Two hours, not two minutes. A UPI mandate or a bank redirect can legitimately take a long time
 * on a slow connection, and cancelling a payment that was still in flight would be worse than
 * leaving it. Cancellation is reversible in any case: a webhook arriving later still credits the
 * payment, because money that arrives late is still money that arrived.
 */
@Component
public class StalePaymentJob {

	private static final Logger log = LoggerFactory.getLogger(StalePaymentJob.class);

	private static final Duration STALE_AFTER = Duration.ofHours(2);

	private final PurchaseService purchases;

	public StalePaymentJob(PurchaseService purchases) {
		this.purchases = purchases;
	}

	@Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 2 * 60 * 1000)
	public void cancelStaleOrders() {
		try {
			purchases.cancelStaleOrders(STALE_AFTER);
		} catch (RuntimeException e) {
			// Caught deliberately: an uncaught exception silently cancels all future runs of a
			// Spring schedule.
			log.error("Stale payment job failed; will retry next run", e);
		}
	}
}
