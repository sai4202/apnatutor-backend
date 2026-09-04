package com.apnatutor.notification.domain;

/** What a notification is about. Values are constrained in the database too. */
public enum NotificationType {

	/** A tutor: an enquiry matching your subjects and area has been posted. */
	NEW_MATCHING_LEAD,

	/** A student: a tutor spent credits to reach you. */
	TUTOR_RESPONDED,

	/** A tutor: you are nearly out of credits and will start missing leads. */
	LOW_CREDIT_BALANCE,

	VERIFICATION_APPROVED,
	VERIFICATION_REJECTED,

	/** A student: your enquiry is about to time out. */
	REQUIREMENT_EXPIRING,

	/** A tutor: the free credits for verifying your ID have landed. */
	SIGNUP_BONUS_GRANTED,

	/** A tutor: your purchase went through and the credits are in your wallet. */
	CREDITS_PURCHASED,

	/** A tutor: credits are about to lapse. Sent while there is still time to use them. */
	CREDITS_EXPIRING,

	/** A tutor: a disputed lead was refunded, or the dispute was declined. */
	REFUND_APPROVED,
	REFUND_REJECTED,

	/** A tutor: a review of you passed moderation and is now on your profile. */
	REVIEW_PUBLISHED,

	/**
	 * A student: the review you wrote will not be published, and why.
	 *
	 * <p>Sent because silence is indistinguishable from a bug. A student who writes a review and
	 * never hears anything concludes the feature is broken, or that we quietly bin criticism.
	 */
	REVIEW_REJECTED,

	/** A student: the tutor you reviewed has answered, and the answer is now public. */
	REVIEW_REPLY_PUBLISHED,

	// --- Moderation (M5-05) --------------------------------------------------------------------

	/**
	 * Anyone: your account has been blocked, and why.
	 *
	 * <p>Sent even though a suspended user cannot log in to read it in the app — it is the record of
	 * what they were told, and the SMS channel still reaches them.
	 */
	ACCOUNT_SUSPENDED,

	/** Anyone: your account is active again. */
	ACCOUNT_REINSTATED,

	/** A student: your enquiry was taken down by a moderator, and why. */
	REQUIREMENT_REMOVED,

	/**
	 * Anyone: the thing you reported has been looked at, and what came of it.
	 *
	 * <p>Sent whether the report was upheld or dismissed. A report that vanishes into silence
	 * teaches the person who filed it that reporting does nothing, and they are usually the only
	 * witness to whatever happened.
	 */
	ABUSE_REPORT_REVIEWED
}
