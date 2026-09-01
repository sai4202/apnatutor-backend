package com.apnatutor.billing.gateway;

/**
 * The payment provider, behind an interface.
 *
 * <p>Two implementations: {@code RazorpayGateway} talks to Razorpay, {@code StubPaymentGateway}
 * pretends to. The stub is what lets the entire purchase flow — orders, webhooks, idempotent
 * crediting, the wallet UI — be built and tested before anyone opens a Razorpay account, and it is
 * the same pattern already used for SMS and email.
 *
 * <p>It is also the seam a second provider goes through. Indian payments fragment: a business that
 * starts on Razorpay often adds Cashfree or PhonePe later, and that should be a new class here
 * rather than an edit to every call site.
 */
public interface PaymentGateway {

	/** Identifies the provider in {@code payments.provider}. Must be stable — it is stored. */
	String name();

	/**
	 * Creates an order with the provider.
	 *
	 * @param amountPaise the amount, in paise, taken from the package server-side — never from the
	 *     client
	 * @param receipt our own reference, echoed back by the provider
	 * @throws PaymentGatewayException if the provider is unreachable or refuses
	 */
	GatewayOrder createOrder(long amountPaise, String currency, String receipt);

	/**
	 * Verifies a webhook delivery against its signature header.
	 *
	 * <p>Must be computed over the <strong>raw bytes as received</strong>. Re-serialising the parsed
	 * JSON changes key order and whitespace, and the signature will not match — the single most
	 * common way webhook verification is quietly broken.
	 *
	 * @return false for a missing, malformed or mismatched signature. Never throws: an invalid
	 *     signature is an expected event worth recording, not an exception.
	 */
	boolean verifyWebhookSignature(String rawBody, String signatureHeader);

	/**
	 * Verifies the signature the checkout widget hands the browser after payment.
	 *
	 * <p>Distinct from the webhook signature: different secret, different payload, and it arrives
	 * over a channel the user controls. It is a fast path for updating the UI, never the authority
	 * for granting credits — that is the webhook's job, because a browser can simply close.
	 */
	boolean verifyCheckoutSignature(String orderId, String paymentId, String signature);

	/** What the provider gave back when the order was created. */
	record GatewayOrder(String orderId, long amountPaise, String currency) {
	}
}
