package com.apnatutor.ratelimit;

import java.time.Duration;

/**
 * How many requests a caller gets, and over what period — {@code M5-07}.
 *
 * <p>Named, because the name is what appears in the log line when a limit fires. "rate limit
 * exceeded" tells whoever is on call nothing; "auth limit exceeded for 203.0.113.4" tells them
 * whether to look at an attack or at a misconfigured client.
 *
 * @param name identifies the bucket in logs and metrics, and keeps two policies on one key apart
 * @param permits requests allowed per window
 * @param window the period those permits are spread over
 */
public record RateLimitPolicy(String name, int permits, Duration window) {

	public RateLimitPolicy {
		if (permits < 1) {
			throw new IllegalArgumentException("A policy must allow at least one request");
		}
		if (window == null || window.isZero() || window.isNegative()) {
			throw new IllegalArgumentException("A policy needs a positive window");
		}
	}

	/** Permits per nanosecond — the token bucket's refill rate. */
	double refillPerNano() {
		return (double) permits / window.toNanos();
	}
}
