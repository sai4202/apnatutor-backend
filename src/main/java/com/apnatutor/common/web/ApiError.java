package com.apnatutor.common.web;

import java.util.Map;

/**
 * The single error shape every endpoint returns (SOURCE_OF_TRUTH.md section 6).
 *
 * <pre>
 * { "code": "LEAD_UNLOCK_CAP_REACHED",
 *   "message": "This requirement already has the maximum number of responses.",
 *   "fieldErrors": { "budget": "must be positive" } }
 * </pre>
 *
 * <p>{@code fieldErrors} is null for everything except validation failures, and {@code
 * default-property-inclusion: non_null} keeps it out of the JSON entirely when absent.
 *
 * @param code stable machine identifier — clients branch on this
 * @param message human-readable text — never branch on it, it will be reworded
 * @param fieldErrors field name to message, for validation failures only
 */
public record ApiError(String code, String message, Map<String, String> fieldErrors) {

	public static ApiError of(ErrorCode code, String message) {
		return new ApiError(code.name(), message, null);
	}

	public static ApiError validation(String message, Map<String, String> fieldErrors) {
		return new ApiError(ErrorCode.VALIDATION_FAILED.name(), message, fieldErrors);
	}
}
