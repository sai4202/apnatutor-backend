package com.apnatutor.requirement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires requirements past their 30 days.
 *
 * <p>An enquiry from two months ago is not a lead, it is a wasted credit. A tutor who unlocks one
 * and finds the family sorted long ago has been sold something worthless, and will say so.
 *
 * <p>Hourly rather than nightly: the boundary should be roughly where a student expects it, not up
 * to a day later. Cheap either way — a single bulk UPDATE over an indexed predicate.
 *
 * <p><strong>Single-instance assumption.</strong> With more than one application instance every one
 * would run this. Harmless here because the update is idempotent — a second run finds nothing left
 * to expire — but a job with side effects would need locking. Worth remembering at `M6-07`.
 */
@Component
public class RequirementExpiryJob {

	private static final Logger log = LoggerFactory.getLogger(RequirementExpiryJob.class);

	private final RequirementService requirements;

	public RequirementExpiryJob(RequirementService requirements) {
		this.requirements = requirements;
	}

	@Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 1000)
	public void expireOverdueRequirements() {
		try {
			int expired = requirements.expireOverdue();
			if (expired > 0) {
				log.info("Requirement expiry: {} marked EXPIRED", expired);
			}
		} catch (RuntimeException e) {
			// Caught deliberately. An uncaught exception in a scheduled task silently cancels all
			// future runs of that schedule in Spring, so a transient database blip would stop
			// expiry permanently with nothing to indicate it.
			log.error("Requirement expiry job failed; will retry next run", e);
		}
	}
}
