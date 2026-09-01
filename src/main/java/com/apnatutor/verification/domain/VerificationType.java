package com.apnatutor.verification.domain;

/**
 * A claim a user can ask us to verify.
 *
 * <p>Phone is absent on purpose. It is verified by the OTP flow and recorded on {@code
 * users.phone_verified_at}; adding a row here for it would create a second source of truth for the
 * same fact. This enum covers only claims that need a document and a human decision.
 */
public enum VerificationType {

	/** Confirmed by a link sent to the address. No document, no admin review. */
	EMAIL(false),

	/** Aadhaar, PAN or similar. The one that matters most to a parent. */
	ID(true),

	/** Degree certificates and teaching qualifications. */
	EDUCATION(true);

	private final boolean requiresDocument;

	VerificationType(boolean requiresDocument) {
		this.requiresDocument = requiresDocument;
	}

	public boolean requiresDocument() {
		return requiresDocument;
	}
}
