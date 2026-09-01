package com.apnatutor.verification.domain;

/**
 * How far up the trust ladder a tutor has climbed.
 *
 * <h2>Email is a badge, not a rung</h2>
 *
 * <p>The original breakdown described the ladder as PHONE → EMAIL → ID → EDUCATION, implying each
 * rung requires the one below. Implemented literally that is wrong: email is optional on this
 * platform ({@code users.email} is nullable, because phone is the identity), so a tutor who never
 * adds one could never reach {@code ID_VERIFIED}. That would block the single badge parents care
 * about, and with it the M4 signup bonus, for no benefit to anyone.
 *
 * <p>So the ladder here is phone → ID → education, and email verification is reported separately as
 * its own badge. A tutor with a verified email and no ID is not more trustworthy than one with a
 * verified ID and no email, and the levels should not claim otherwise.
 */
public enum VerificationLevel {

	/** Should not occur in practice — registration requires an OTP. Present for completeness. */
	NONE,

	/** Phone confirmed by OTP. Every registered user starts here. */
	PHONE_VERIFIED,

	/** Government ID checked by an admin. The threshold that unlocks the M4 signup bonus. */
	ID_VERIFIED,

	/** ID and education both checked. The strongest badge a tutor can hold. */
	FULLY_VERIFIED;

	public boolean isAtLeast(VerificationLevel other) {
		return ordinal() >= other.ordinal();
	}
}
