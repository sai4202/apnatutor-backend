package com.apnatutor.billing.domain;

/**
 * Why credits moved.
 *
 * <p>Every ledger entry carries one. This is what makes the ledger auditable — "your balance went
 * from 12 to 7" is not an answer, "you unlocked requirement #431 for 5 credits" is.
 *
 * <p>v2 commission, escrow and tutor payouts are new values here plus a second ledger, not a
 * migration of this one (SOURCE_OF_TRUTH.md §4).
 */
public enum CreditReason {

	/** Bought with money. Expires 365 days after purchase. */
	PURCHASE,

	/** Granted once on reaching ID_VERIFIED. Expires in 90 days. */
	SIGNUP_BONUS,

	/** Spent revealing a lead's contact details. Negative. */
	UNLOCK,

	/** Returned after an approved dispute over a bad lead. */
	REFUND,

	/** A support correction. The only sanctioned way to fix a balance. */
	ADMIN_ADJUSTMENT,

	/** Credits that timed out. Negative. */
	EXPIRY
}
