package com.apnatutor.user.domain;

/**
 * A tutor's gender, as they choose to state it.
 *
 * <p>Collected because it is a genuine search filter in this market — many families specifically
 * want a female tutor for a daughter, and that preference decides whether they hire at all. Always
 * optional on the profile, never required.
 */
public enum Gender {
	MALE,
	FEMALE,
	OTHER
}
