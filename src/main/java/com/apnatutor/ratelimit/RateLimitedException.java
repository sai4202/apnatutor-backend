package com.apnatutor.ratelimit;

import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;

/**
 * Raised when a service-layer limit is hit — {@code M5-07.4}.
 *
 * <p>Carries the retry delay so {@code GlobalExceptionHandler} can set a {@code Retry-After}
 * header. A 429 without one tells a client to back off but not by how much, and the usual response
 * to that is an immediate retry, which is the behaviour the limit exists to stop.
 */
public class RateLimitedException extends ApiException {

	private final long retryAfterSeconds;

	public RateLimitedException(String message, long retryAfterSeconds) {
		super(ErrorCode.RATE_LIMITED, message);
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
