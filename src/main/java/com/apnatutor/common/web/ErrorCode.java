package com.apnatutor.common.web;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error identifiers returned in every error response.
 *
 * <p>The frontend switches on these. Messages are for humans and may be reworded at any time, so
 * nothing should ever branch on message text (SOURCE_OF_TRUTH.md section 6).
 *
 * <p><strong>These names are part of the API contract.</strong> Renaming one silently breaks any
 * client that handles it. Add new codes freely; change existing ones only with a deliberate API
 * version bump.
 *
 * <p>Each code carries its HTTP status so the two can never drift apart across handlers.
 */
public enum ErrorCode {

	// --- Generic -----------------------------------------------------------------------------
	/** Request body or parameters failed validation. Accompanied by {@code fieldErrors}. */
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
	/** Body could not be parsed at all — malformed JSON, wrong content type, bad enum value. */
	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
	/** No credentials, or credentials that are expired or invalid. */
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
	/** Authenticated, but not allowed to do this. */
	FORBIDDEN(HttpStatus.FORBIDDEN),
	NOT_FOUND(HttpStatus.NOT_FOUND),
	/** The request conflicts with current state — a duplicate, or an illegal state transition. */
	CONFLICT(HttpStatus.CONFLICT),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
	/** Unexpected server fault. The cause is logged; never exposed to the caller. */
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

	// --- Idempotency (M1-01.6) ---------------------------------------------------------------
	/** A money-moving endpoint was called without the required {@code Idempotency-Key} header. */
	IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST),
	/** The same key was reused with a different request body — almost always a client bug. */
	IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),

	// --- Authentication (M1-03, M1-04) -------------------------------------------------------
	OTP_INVALID(HttpStatus.BAD_REQUEST),
	OTP_EXPIRED(HttpStatus.BAD_REQUEST),
	/** Too many wrong guesses for one code; it is now dead and a new one must be requested. */
	OTP_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
	/** Hourly send cap for this phone number reached (SOURCE_OF_TRUTH.md section 3.4). */
	OTP_SEND_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
	REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
	ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN),

	// --- Profiles (M1-08) --------------------------------------------------------------------
	/** Publishing was attempted on a profile that is not complete enough to be shown. */
	PROFILE_INCOMPLETE(HttpStatus.CONFLICT),

	// --- Marketplace (M3, M4) ----------------------------------------------------------------
	// Declared now because SOURCE_OF_TRUTH.md names them as part of the contract. The endpoints
	// that raise them arrive in M3 and M4.
	/** The requirement already has its maximum number of unlocks (section 3.2). No credits taken. */
	LEAD_UNLOCK_CAP_REACHED(HttpStatus.CONFLICT),
	/** This tutor has already unlocked this requirement. Never charge twice. */
	LEAD_ALREADY_UNLOCKED(HttpStatus.CONFLICT),
	INSUFFICIENT_CREDITS(HttpStatus.PAYMENT_REQUIRED),
	REQUIREMENT_NOT_OPEN(HttpStatus.CONFLICT),
	/** A signature did not verify — a webhook, or the checkout callback. */
	PAYMENT_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST),
	/**
	 * The payment provider could not be reached, or refused the request.
	 *
	 * <p>Distinct from a declined card: nothing was charged, and retrying is the right advice.
	 * 502 rather than 500 because the fault is upstream, which is what a client retry policy and an
	 * alerting rule both need to know.
	 */
	PAYMENT_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY),
	/** A dispute was raised outside the refund window, or on a lead already disputed. */
	REFUND_NOT_ALLOWED(HttpStatus.CONFLICT),

	// --- Reviews (M5-01, M5-03) --------------------------------------------------------------
	/**
	 * The student was never connected to this tutor, so they have nothing to review.
	 *
	 * <p>403 rather than 404: the tutor plainly exists and their profile is public. The refusal is
	 * about the writer, and saying so is what stops it reading as a bug.
	 */
	REVIEW_NOT_ELIGIBLE(HttpStatus.FORBIDDEN),
	/** One review per student-tutor pair. The existing one stays editable while it is pending. */
	REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT),
	/** A tutor gets exactly one public answer per review (SOURCE_OF_TRUTH.md section 3.6). */
	REVIEW_ALREADY_REPLIED(HttpStatus.CONFLICT);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}
