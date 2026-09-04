package com.apnatutor.abuse.domain;

/**
 * Why something was reported.
 *
 * <p>A code rather than free text, for the same reason {@code RefundReason} is: the point of
 * collecting reasons is to count them. Free text describes one incident; a code shows which subject,
 * which city and which kind of account keep producing them.
 */
public enum ReportReason {

	SPAM,

	/** A profile that is not who it says it is. */
	FAKE_PROFILE,

	/** An enquiry nobody posted, or posted to harvest tutors. */
	FAKE_ENQUIRY,

	HARASSMENT,

	/**
	 * Asking to move the arrangement off the platform to avoid credits.
	 *
	 * <p>Worth its own code rather than {@code OTHER}: it is the failure mode that quietly ends
	 * marketplaces, and it cannot be measured if it is not named.
	 */
	OFF_PLATFORM_SOLICITATION,

	/** Qualifications or experience that do not hold up. */
	MISLEADING_CLAIMS,

	INAPPROPRIATE_CONTENT,

	/**
	 * Raised by the platform, never by a person: this tutor disputes an implausible share of the
	 * leads they buy.
	 */
	HIGH_DISPUTE_RATE,

	OTHER
}
