package com.apnatutor.user.domain;

/**
 * How a fee is quoted.
 *
 * <p>Both are common in India and mean very different things: ₹500 per hour and ₹500 per month are
 * an order of magnitude apart. Storing the unit alongside the amount is what stops a search filter
 * comparing them as if they were the same number.
 */
public enum FeeUnit {
	PER_HOUR,
	PER_MONTH
}
