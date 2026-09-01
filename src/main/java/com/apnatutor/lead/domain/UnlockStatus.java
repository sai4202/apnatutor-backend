package com.apnatutor.lead.domain;

/** Whether an unlock still stands, or was reversed after a dispute. */
public enum UnlockStatus {

	ACTIVE,

	/**
	 * Refunded after an approved dispute.
	 *
	 * <p>The row is kept rather than deleted — the refund is part of the history, and the ledger
	 * entry references it. The cap slot is freed, so a bad lead does not permanently consume one of
	 * the five.
	 */
	REFUNDED
}
