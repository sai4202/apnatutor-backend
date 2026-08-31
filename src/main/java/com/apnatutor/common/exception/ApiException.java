package com.apnatutor.common.exception;

import com.apnatutor.common.web.ErrorCode;

/**
 * Base class for every deliberately-raised application error.
 *
 * <p>Carrying the {@link ErrorCode} on the exception means the HTTP status and response code are
 * decided at the point the problem is detected, by the code that understands it — not guessed later
 * by a handler matching on exception types.
 *
 * <p>Extends {@code RuntimeException} so it does not pollute service signatures, and suppresses
 * stack trace capture: these represent expected outcomes (a bad OTP, a capped lead), they are
 * thrown on hot paths, and filling in a stack trace for each one is pure waste. Genuine faults are
 * unchecked exceptions from elsewhere, and those keep their traces.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;

	public ApiException(ErrorCode errorCode, String message) {
		// writableStackTrace = false: see class javadoc.
		super(message, null, false, false);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	// --- Convenience factories for the common cases ------------------------------------------

	public static ApiException notFound(String what) {
		return new ApiException(ErrorCode.NOT_FOUND, what + " not found");
	}

	public static ApiException forbidden(String message) {
		return new ApiException(ErrorCode.FORBIDDEN, message);
	}

	public static ApiException conflict(String message) {
		return new ApiException(ErrorCode.CONFLICT, message);
	}
}
