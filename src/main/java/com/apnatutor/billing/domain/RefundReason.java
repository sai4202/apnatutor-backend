package com.apnatutor.billing.domain;

/**
 * Why a tutor is disputing a lead.
 *
 * <p>A code rather than free text, because the point of collecting reasons is to count them. Free
 * text tells an admin about one bad lead; a code tells the platform which students, which
 * requirements and which subjects are generating them — and that is what actually stops the next
 * one.
 */
public enum RefundReason {

	/** The number does not belong to the person who posted, or does not exist. */
	WRONG_NUMBER,

	/** Repeatedly called, never answered. The most common and the hardest to adjudicate. */
	UNREACHABLE,

	/** They had already found a tutor before this one paid to reach them. */
	ALREADY_HIRED,

	/** They answered and said they never wanted a tutor. */
	NOT_LOOKING,

	/** The same enquiry posted twice, so the tutor paid twice for one family. */
	DUPLICATE_REQUIREMENT,

	/** The enquiry did not describe what the student actually wanted. */
	WRONG_SUBJECT_OR_AREA,

	/** The contact was abusive. Refunded, and escalated separately. */
	ABUSIVE,

	OTHER;

	/**
	 * Whether this reason implicates the student rather than the platform.
	 *
	 * <p>Used to decide what happens beyond the refund. A wrong number is a data problem worth
	 * fixing at the source; a tutor who simply did not get through is not evidence of anything about
	 * the student.
	 */
	public boolean implicatesStudent() {
		return this == WRONG_NUMBER
				|| this == NOT_LOOKING
				|| this == DUPLICATE_REQUIREMENT
				|| this == ABUSIVE;
	}
}
