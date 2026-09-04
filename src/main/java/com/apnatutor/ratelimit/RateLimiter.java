package com.apnatutor.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Token buckets, in memory — {@code M5-07.3}.
 *
 * <h2>Token bucket rather than a fixed window</h2>
 *
 * <p>A fixed window lets a caller spend a full window's permits at 11:59:59 and another full
 * window's at 12:00:00 — double the intended rate, at the moment an attacker is most likely to be
 * looking for it. A bucket refills continuously, so the burst it allows is exactly its capacity and
 * never twice that.
 *
 * <h2>In memory, and the assumption that carries</h2>
 *
 * <p>This holds state in one JVM, so it limits per instance: two instances behind a load balancer
 * would allow twice the configured rate. That is the same single-instance assumption already
 * recorded as debt <strong>T13</strong> for the credit expiry job, and it is recorded again as
 * <strong>T20</strong> rather than left implicit. It is the right trade today — a shared limiter
 * means Redis, which is a service to run, monitor and pay for before there is a second instance to
 * justify it — and the first thing to fix when scaling out.
 *
 * <h2>The map cannot grow without bound</h2>
 *
 * <p>Keys come from the network, so an unbounded map keyed on them is itself the denial of service
 * this class exists to prevent. Buckets are swept when the map grows past {@link #MAX_BUCKETS},
 * and a <strong>full bucket is dropped</strong> — a caller with all their permits is
 * indistinguishable from one that has never been seen, so evicting them is free rather than a
 * decision about who to forget.
 */
@Component
public class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	/**
	 * When to sweep. Sized so a busy minute of ordinary traffic never triggers one, and a flood of
	 * distinct keys is bounded well below anything that threatens the heap.
	 */
	static final int MAX_BUCKETS = 50_000;

	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	/** The outcome of one check. */
	public record Decision(boolean allowed, long retryAfterSeconds) {

		static final Decision ALLOWED = new Decision(true, 0);
	}

	/**
	 * Takes one permit for {@code key} under {@code policy}, if there is one.
	 *
	 * @param key what is being limited — an IP, a user id. Namespaced by the policy name, so the
	 *     same user can hold separate allowances for unlocking and for searching.
	 */
	public Decision check(String key, RateLimitPolicy policy) {
		if (buckets.size() > MAX_BUCKETS) {
			sweep();
		}

		// The key is namespaced by policy name, so a bucket only ever sees one policy and can
		// hold its own capacity and refill rate.
		Bucket bucket = buckets.computeIfAbsent(
				policy.name() + "|" + key, ignored -> new Bucket(policy));

		return bucket.tryConsume(System.nanoTime());
	}

	/** Drops every bucket that is back to full. Full is the same as absent, so nothing is lost. */
	private void sweep() {
		long now = System.nanoTime();
		int before = buckets.size();

		buckets.values().removeIf(bucket -> bucket.isFull(now));

		log.info("Rate limiter swept {} idle buckets, {} remain",
				before - buckets.size(), buckets.size());

		if (buckets.size() > MAX_BUCKETS) {
			// Every bucket is actively rate-limited and none could be dropped. That is a flood
			// from many sources, not ordinary traffic, and it is worth saying so loudly — the
			// limiter is holding, but somebody should look.
			log.warn("Rate limiter still holds {} active buckets after a sweep — "
					+ "this looks like a distributed flood", buckets.size());
		}
	}

	/** Visible for testing: how many buckets are currently held. */
	int size() {
		return buckets.size();
	}

	/**
	 * One caller's allowance.
	 *
	 * <p>Synchronised rather than lock-free. The critical section is a few arithmetic operations,
	 * contention on a single key is by definition rare — that is what having a limit means — and a
	 * compare-and-swap loop here would be harder to read for no measurable gain.
	 */
	private static final class Bucket {

		private final int capacity;
		private final double refillPerNano;
		private double tokens;
		private long lastRefillNanos;

		Bucket(RateLimitPolicy policy) {
			this.capacity = policy.permits();
			this.refillPerNano = policy.refillPerNano();
			this.tokens = capacity;
			this.lastRefillNanos = System.nanoTime();
		}

		synchronized Decision tryConsume(long now) {
			refill(now);

			if (tokens >= 1) {
				tokens -= 1;
				return Decision.ALLOWED;
			}

			// How long until one whole token exists. Rounded up and floored at one second: a
			// Retry-After of 0 reads as "try again immediately", which is the opposite of the
			// instruction being given.
			double secondsToOneToken = (1 - tokens) / refillPerNano / 1_000_000_000d;
			return new Decision(false, Math.max(1, (long) Math.ceil(secondsToOneToken)));
		}

		/** A bucket back at capacity has no memory left to lose, so the sweep may drop it. */
		synchronized boolean isFull(long now) {
			refill(now);
			return tokens >= capacity;
		}

		private void refill(long now) {
			long elapsed = now - lastRefillNanos;
			if (elapsed <= 0) {
				return;
			}
			tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
			lastRefillNanos = now;
		}
	}
}
