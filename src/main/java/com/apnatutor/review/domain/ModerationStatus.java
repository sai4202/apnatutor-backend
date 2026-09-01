package com.apnatutor.review.domain;

/**
 * Where a piece of user-written text stands with a moderator.
 *
 * <p>Shared by a review and by the tutor's reply to it, because the lifecycle is genuinely the
 * same: written, waiting, then decided by a human. Two near-identical enums would only invite the
 * day one of them gains a value the other should have had.
 *
 * <p>Nothing reaches a public response before {@link #APPROVED} (SOURCE_OF_TRUTH.md section 3.6).
 */
public enum ModerationStatus {

	PENDING,
	APPROVED,

	/**
	 * Refused publication. The row is kept, with its reason.
	 *
	 * <p>Deleting it would let the student write the same thing again and re-enter the queue, and
	 * would lose the record of a decision somebody may have to answer for.
	 */
	REJECTED
}
