package com.apnatutor.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bucket algorithm — {@code M5-07.3}.
 *
 * <p>A plain unit test, no Spring. This is arithmetic, and the properties worth pinning down are
 * about the algorithm rather than about the wiring: that a burst is capped at capacity, that the
 * allowance comes back over time, and that two keys cannot spend each other's permits.
 */
class RateLimiterTest {

	@Test
	@DisplayName("a caller gets exactly its permits, then is refused")
	void permitsAreExact() {
		RateLimiter limiter = new RateLimiter();
		RateLimitPolicy policy = new RateLimitPolicy("test", 3, Duration.ofMinutes(1));

		assertThat(limiter.check("a", policy).allowed()).isTrue();
		assertThat(limiter.check("a", policy).allowed()).isTrue();
		assertThat(limiter.check("a", policy).allowed()).isTrue();

		RateLimiter.Decision refused = limiter.check("a", policy);
		assertThat(refused.allowed()).isFalse();
		assertThat(refused.retryAfterSeconds())
				.as("a Retry-After of 0 reads as 'try again immediately', which is the opposite "
						+ "of the instruction being given")
				.isGreaterThanOrEqualTo(1);
	}

	@Test
	@DisplayName("keys do not share an allowance")
	void keysAreIndependent() {
		RateLimiter limiter = new RateLimiter();
		RateLimitPolicy policy = new RateLimitPolicy("test", 1, Duration.ofMinutes(1));

		assertThat(limiter.check("a", policy).allowed()).isTrue();
		assertThat(limiter.check("a", policy).allowed()).isFalse();

		assertThat(limiter.check("b", policy).allowed())
				.as("one caller exhausting their allowance must not lock out everybody else")
				.isTrue();
	}

	@Test
	@DisplayName("the same key holds separate allowances under different policies")
	void policiesAreNamespaced() {
		RateLimiter limiter = new RateLimiter();
		RateLimitPolicy searching = new RateLimitPolicy("search", 1, Duration.ofMinutes(1));
		RateLimitPolicy unlocking = new RateLimitPolicy("unlock", 1, Duration.ofMinutes(1));

		assertThat(limiter.check("tutor-7", searching).allowed()).isTrue();
		assertThat(limiter.check("tutor-7", searching).allowed()).isFalse();

		assertThat(limiter.check("tutor-7", unlocking).allowed())
				.as("a tutor who has searched a lot must still be able to buy a lead")
				.isTrue();
	}

	@Test
	@DisplayName("the allowance refills over time")
	void bucketsRefill() {
		RateLimiter limiter = new RateLimiter();
		// A 20ms window, so a real pause is short enough to keep the test fast and long enough to
		// be unambiguous. Refill is computed from System.nanoTime, not from a wall clock.
		RateLimitPolicy policy = new RateLimitPolicy("test", 1, Duration.ofMillis(20));

		assertThat(limiter.check("a", policy).allowed()).isTrue();
		assertThat(limiter.check("a", policy).allowed()).isFalse();

		await(60);

		assertThat(limiter.check("a", policy).allowed())
				.as("a token bucket refills continuously; a caller is not locked out for a "
						+ "whole window because of one refusal")
				.isTrue();
	}

	@Test
	@DisplayName("a burst cannot exceed capacity, however long the caller waited")
	void refillIsCappedAtCapacity() {
		RateLimiter limiter = new RateLimiter();
		RateLimitPolicy policy = new RateLimitPolicy("test", 2, Duration.ofMillis(10));

		// Idle far longer than the window. A fixed-window counter would let the next instant spend
		// a full window's worth on top of what a bucket allows; capacity is the cap either way.
		await(200);

		assertThat(limiter.check("a", policy).allowed()).isTrue();
		assertThat(limiter.check("a", policy).allowed()).isTrue();
		assertThat(limiter.check("a", policy).allowed())
				.as("THE POINT: waiting longer never buys more than capacity")
				.isFalse();
	}

	@Test
	@DisplayName("a policy that allows nothing, or never resets, is rejected at construction")
	void policiesAreValidated() {
		assertThatThrownBy(() -> new RateLimitPolicy("bad", 0, Duration.ofMinutes(1)))
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RateLimitPolicy("bad", 1, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** A real pause. The limiter reads {@code System.nanoTime()}, which no injected clock reaches. */
	private static void await(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
