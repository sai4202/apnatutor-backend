package com.apnatutor.user.domain;

/**
 * Account lifecycle state.
 *
 * <p>Only {@link #ACTIVE} accounts may authenticate. Note that {@link #DELETED} is a marker, not an
 * erasure: financial ledger rows must survive a departed user (M5-10.3), so deletion anonymises
 * rather than removes.
 */
public enum UserStatus {
	ACTIVE,
	/** Blocked by an admin. Cannot log in; existing tokens stop working at next validation. */
	SUSPENDED,
	/** User-requested deletion. Personal fields are anonymised; the row stays for referential integrity. */
	DELETED
}
