package com.apnatutor.lead.domain;

/**
 * How a tutor and student became connected.
 *
 * <p>SOURCE_OF_TRUTH.md Invariant 2. {@link #UNLOCK} is the only type v1 creates; {@link #BOOKING}
 * exists so the v2 lesson-booking flow lands as another value here rather than as a parallel table
 * that every consumer of the connection graph would then need to know about.
 */
public enum EngagementType {

	/** The tutor spent credits to reveal the student's contact details. */
	UNLOCK,

	/** v2: the student booked and paid for a lesson in-app. Not yet created. */
	BOOKING
}
