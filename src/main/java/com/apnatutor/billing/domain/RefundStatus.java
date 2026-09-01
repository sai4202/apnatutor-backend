package com.apnatutor.billing.domain;

/** Where a dispute has got to. */
public enum RefundStatus {

	/** Waiting on an admin. */
	PENDING,

	/** Credits returned and the unlock's cap slot freed. */
	APPROVED,

	/** Declined, with a reason the tutor can read. */
	REJECTED
}
