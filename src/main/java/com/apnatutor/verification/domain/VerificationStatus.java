package com.apnatutor.verification.domain;

/** Where a verification request stands. */
public enum VerificationStatus {
	/** Submitted, awaiting an admin decision. */
	PENDING,
	APPROVED,
	/** Refused, always with a reason. The row is kept so the history survives a resubmission. */
	REJECTED
}
