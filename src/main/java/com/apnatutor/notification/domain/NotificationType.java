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
	REQUIREMENT_EXPIRING
}
