package com.apnatutor.user.domain;

/**
 * What kind of account this is.
 *
 * <p>A user has exactly one role (SOURCE_OF_TRUTH.md ADR #10). Someone who is both a parent and a
 * tutor creates two accounts — a v1 simplification, revisited only on real user demand.
 *
 * <p>Stored as a string, never an ordinal: an ordinal silently remaps every existing row the moment
 * someone reorders this enum.
 */
public enum UserRole {
	/** Seeks tuition. Often a parent rather than the student; the UI says "Student / Parent". */
	STUDENT,
	/** Supplies tuition. */
	TUTOR,
	ADMIN;

	/** Spring Security convention: authorities carry a {@code ROLE_} prefix. */
	public String authority() {
		return "ROLE_" + name();
	}
}
