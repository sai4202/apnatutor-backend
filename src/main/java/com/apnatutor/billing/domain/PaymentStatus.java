package com.apnatutor.billing.domain;

/**
 * Where a payment has got to.
 *
 * <p>Only {@link #PAID} ever grants credits, and only once — {@code payments.credited_at} is what
 * records that it happened.
 */
public enum PaymentStatus {

	/**
	 * An order exists with the provider; nobody has paid yet.
	 *
	 * <p>The state most people forget. A tutor who closes the checkout window leaves a row here
	 * forever unless something goes looking, which is what the reconciliation job is for.
	 */
	CREATED,

	/** The provider confirmed the money. The only state that grants credits. */
	PAID,

	/** The provider declined it. */
	FAILED,

	/** The tutor abandoned checkout, or we timed the order out. */
	CANCELLED,

	/** Money returned. Distinct from a credit refund, which is a ledger entry, not a payment. */
	REFUNDED
}
