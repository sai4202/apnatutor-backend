package com.apnatutor.billing.gateway;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A payment provider that takes no money.
 *
 * <p>Selected by {@link PaymentGatewayConfig} whenever no Razorpay credentials are configured. The
 * purchase flow, the webhook, idempotent crediting and the wallet UI can all be built and tested
 * against this before anyone opens a Razorpay account, which is the same reason
 * {@code ConsoleSmsSender} exists.
 *
 * <h2>This must never run in production</h2>
 *
 * <p>It grants credits for nothing. {@link com.apnatutor.common.config.DevModeGuard} refuses to
 * start the application if this bean is active under the {@code prod} profile — a check worth having
 * because "we forgot to set the Razorpay keys" is otherwise indistinguishable from a working
 * deployment right up until the accounting.
 *
 * <p>Signatures verify against a fixed secret rather than returning true unconditionally. A stub
 * that accepts everything would let a signature bug reach production untested, since every test
 * against it would pass.
 */
public class StubPaymentGateway implements PaymentGateway {

	private static final Logger log = LoggerFactory.getLogger(StubPaymentGateway.class);

	/** Documented, fixed, and only ever used when no real provider is configured. */
	public static final String STUB_SECRET = "stub-webhook-secret";

	public StubPaymentGateway() {
		log.warn("No Razorpay credentials configured — using the stub payment gateway. "
				+ "Credits will be granted WITHOUT any money changing hands.");
	}

	@Override
	public String name() {
		// Deliberately not "RAZORPAY". A payment row must never claim a provider that never saw it,
		// or the reconciliation against Razorpay's own records will not add up.
		return "STUB";
	}

	@Override
	public GatewayOrder createOrder(long amountPaise, String currency, String receipt) {
		String orderId = "order_stub_" + UUID.randomUUID().toString().replace("-", "")
				.substring(0, 14);
		log.info("Stub order created: id={} amount={} receipt={}", orderId, amountPaise, receipt);
		return new GatewayOrder(orderId, amountPaise, currency);
	}

	@Override
	public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
		if (rawBody == null || signatureHeader == null) {
			return false;
		}
		return Signatures.matches(Signatures.hmacHex(rawBody, STUB_SECRET), signatureHeader);
	}

	@Override
	public boolean verifyCheckoutSignature(String orderId, String paymentId, String signature) {
		if (orderId == null || paymentId == null) {
			return false;
		}
		return Signatures.matches(
				Signatures.hmacHex(orderId + "|" + paymentId, STUB_SECRET), signature);
	}

	/** Lets tests and the dev tooling produce a signature this gateway will accept. */
	public static String sign(String payload) {
		return Signatures.hmacHex(payload, STUB_SECRET);
	}
}
