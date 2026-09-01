package com.apnatutor.requirement.domain;

/**
 * Where a requirement stands.
 *
 * <p>Only {@link #OPEN} appears in the tutor lead feed. {@link #CAPPED} exists as a distinct state
 * rather than being inferred from the count, so the feed query filters on one indexed column
 * instead of computing a comparison for every row.
 */
public enum RequirementStatus {

	/** Live and accepting responses. */
	OPEN,

	/** Five tutors have unlocked it. Off the feed, but still visible to its student. */
	CAPPED,

	/** The student found someone. */
	HIRED,

	/** The student withdrew it. */
	CLOSED,

	/** Timed out after 30 days (SOURCE_OF_TRUTH.md §3.4). */
	EXPIRED
}
