package com.apnatutor.audit.domain;

/**
 * How an audited request ended.
 *
 * <p>Refusals are recorded, not only successes. An audit log that holds nothing but completed
 * actions cannot show the thing most worth seeing: a run of {@code REFUSED} entries on
 * {@code /admin} is somebody trying doors.
 */
public enum AuditOutcome {

	SUCCEEDED,

	/** 4xx. A validation failure, a conflict, or an attempt by someone not entitled to make it. */
	REFUSED,

	/** 5xx. The action may or may not have taken effect, which is itself worth knowing. */
	FAILED
}
