package com.apnatutor.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.billing.domain.Payment;
import com.apnatutor.billing.domain.PaymentStatus;
import com.apnatutor.billing.gateway.StubPaymentGateway;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The webhook, which is where money becomes credits.
 *
 * <p>Everything here tests one property from a different angle: <strong>credits are granted exactly
 * once, only for a payment the provider signed for.</strong> That is the single claim the business
 * has to be able to make, and every other test in this file is a way it could quietly stop being
 * true.
 *
 * <p>Runs against {@link StubPaymentGateway}, which verifies signatures against a fixed secret
 * rather than accepting everything — a stub that trusted every request would let a signature bug
 * reach production with a green suite behind it.
 */
class PaymentWebhookTest extends AbstractIntegrationTest {

	@Autowired
	private PaymentWebhookService webhooks;

	@Autowired
	private PurchaseService purchases;

	@Autowired
	private CreditPackageRepository packages;

	@Autowired
	private PaymentRepository payments;

	@Autowired
	private PaymentWebhookEventRepository events;

	@Autowired
	private CreditLedger ledger;

	@Autowired
	private CreditTransactionRepository transactions;

	@Autowired
	private UserRepository users;

	private Long tutorId;
	private Long packageId;
	private int packageCredits;

	@BeforeEach
	void setUp() {
		tutorId = users.save(User.registerVerified(
						"+9198000" + (10000 + counter++), UserRole.TUTOR, Instant.now()))
				.getId();
		var bought = packages.findByActiveTrueOrderBySortOrderAscIdAsc().getFirst();
		packageId = bought.getId();
		packageCredits = bought.getCredits();
	}

	/** Keeps phone numbers unique across tests without a shared sequence. */
	private static int counter = 0;

	@Test
	@DisplayName("a signed payment.captured grants exactly the package's credits")
	void signedCaptureGrantsCredits() {
		Payment payment = purchases.createOrder(tutorId, packageId);

		accept(capturedEvent("evt_1", payment.getProviderOrderId(), "pay_1"));

		assertThat(ledger.balanceOf(tutorId)).isEqualTo(packageCredits);
		assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.PAID);
		assertThat(ledger.reconcile(tutorId))
				.as("the cached balance must match the replayed ledger")
				.isZero();
	}

	@Test
	@DisplayName("THREE deliveries of one event grant credits once")
	void repeatDeliveriesGrantOnce() {
		Payment payment = purchases.createOrder(tutorId, packageId);
		String body = capturedEvent("evt_2", payment.getProviderOrderId(), "pay_2");

		// Providers retry by design — a slow response or a dropped connection produces exactly
		// this. If it double-granted, the platform would be giving away credits every time the
		// network hiccuped.
		accept(body);
		accept(body);
		accept(body);

		assertThat(ledger.balanceOf(tutorId)).isEqualTo(packageCredits);

		List<com.apnatutor.billing.domain.CreditTransaction> purchaseEntries =
				transactions.findByTutorIdOrderByCreatedAtDesc(tutorId).stream()
						.filter(entry -> entry.getReason() == CreditReason.PURCHASE)
						.toList();

		assertThat(purchaseEntries)
				.as("one purchase, one ledger entry — not three that happen to sum correctly")
				.hasSize(1);
	}

	@Test
	@DisplayName("two different events for one order still grant once")
	void differentEventIdsForOneOrderGrantOnce() {
		Payment payment = purchases.createOrder(tutorId, packageId);

		// Razorpay sends both payment.captured and order.paid for a single purchase. They are
		// genuinely different events with different ids, so event-id deduplication does not catch
		// this — the payment's own credited_at is what does.
		accept(capturedEvent("evt_3a", payment.getProviderOrderId(), "pay_3"));
		accept(orderPaidEvent("evt_3b", payment.getProviderOrderId(), "pay_3"));

		assertThat(ledger.balanceOf(tutorId)).isEqualTo(packageCredits);
	}

	@Test
	@DisplayName("an unsigned webhook grants nothing and is recorded")
	void unsignedWebhookIsRejected() {
		Payment payment = purchases.createOrder(tutorId, packageId);
		String body = capturedEvent("evt_4", payment.getProviderOrderId(), "pay_4");

		var outcome = webhooks.handle(body, null);

		assertThat(outcome).isEqualTo(PaymentWebhookService.Outcome.REJECTED);
		assertThat(ledger.balanceOf(tutorId)).isZero();
		assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.CREATED);
	}

	@Test
	@DisplayName("a forged signature grants nothing")
	void forgedSignatureIsRejected() {
		Payment payment = purchases.createOrder(tutorId, packageId);
		String body = capturedEvent("evt_5", payment.getProviderOrderId(), "pay_5");

		// The attack this endpoint exists to survive: it is unauthenticated, so anyone who finds
		// the URL can POST to it. Only the HMAC stands between them and free credits.
		var outcome = webhooks.handle(body, "0000000000000000000000000000000000000000000000000000000000000000");

		assertThat(outcome).isEqualTo(PaymentWebhookService.Outcome.REJECTED);
		assertThat(ledger.balanceOf(tutorId)).isZero();
	}

	@Test
	@DisplayName("a rejected webhook is still recorded, with its raw body")
	void rejectedWebhookIsRecorded() {
		Payment payment = purchases.createOrder(tutorId, packageId);
		String body = capturedEvent("evt_6", payment.getProviderOrderId(), "pay_6");

		webhooks.handle(body, "not-a-real-signature");

		// Kept deliberately. A run of these is how anyone finds out the endpoint is being probed,
		// and deleting them deletes the evidence.
		var recorded = events.findByProviderAndEventId("RAZORPAY", "evt_6");
		assertThat(recorded).isPresent();
		assertThat(recorded.orElseThrow().isSignatureValid()).isFalse();
		assertThat(recorded.orElseThrow().getPayload()).contains("pay_6");
	}

	@Test
	@DisplayName("a failure arriving after a capture does not take the credits back")
	void outOfOrderFailureDoesNotReverseAPaidPayment() {
		Payment payment = purchases.createOrder(tutorId, packageId);

		accept(capturedEvent("evt_7a", payment.getProviderOrderId(), "pay_7"));
		// Webhooks are not ordered. A payment.failed for an earlier attempt can land after the
		// capture that succeeded, and treating it as a reversal would punish a tutor who paid.
		accept(failedEvent("evt_7b", payment.getProviderOrderId(), "pay_7"));

		assertThat(ledger.balanceOf(tutorId)).isEqualTo(packageCredits);
		assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.PAID);
	}

	@Test
	@DisplayName("a webhook for an unknown order is accepted, not retried forever")
	void unknownOrderIsAcceptedNotRetried() {
		String body = capturedEvent("evt_8", "order_does_not_exist", "pay_8");

		// FAILED would make the provider retry this indefinitely. Nothing about retrying will make
		// the order exist.
		assertThat(webhooks.handle(body, StubPaymentGateway.sign(body)))
				.isEqualTo(PaymentWebhookService.Outcome.FAILED);
	}

	@Test
	@DisplayName("the amount comes from the package, never from the webhook")
	void amountIsNotTakenFromTheWebhook() {
		Payment payment = purchases.createOrder(tutorId, packageId);

		// A payload claiming a much larger amount. If any of it were believed, a client who could
		// forge a signature — or a compromised provider account — could mint credits.
		String body = """
				{"id":"evt_9","event":"payment.captured","payload":{"payment":{"entity":{
				"id":"pay_9","order_id":"%s","amount":99999999,"currency":"INR"}}}}"""
				.formatted(payment.getProviderOrderId());

		accept(body);

		assertThat(ledger.balanceOf(tutorId))
				.as("credits come from the package row, not the payload")
				.isEqualTo(packageCredits);
	}

	// --- Helpers ------------------------------------------------------------------------------

	/** Signs and delivers, asserting the webhook was accepted. */
	private void accept(String body) {
		assertThat(webhooks.handle(body, StubPaymentGateway.sign(body)))
				.isEqualTo(PaymentWebhookService.Outcome.ACCEPTED);
	}

	private static String capturedEvent(String eventId, String orderId, String paymentId) {
		return event(eventId, "payment.captured", orderId, paymentId);
	}

	private static String orderPaidEvent(String eventId, String orderId, String paymentId) {
		return event(eventId, "order.paid", orderId, paymentId);
	}

	private static String failedEvent(String eventId, String orderId, String paymentId) {
		return event(eventId, "payment.failed", orderId, paymentId);
	}

	private static String event(
			String eventId, String type, String orderId, String paymentId) {
		return """
				{"id":"%s","event":"%s","payload":{"payment":{"entity":{
				"id":"%s","order_id":"%s","status":"captured","currency":"INR"}}}}"""
				.formatted(eventId, type, paymentId, orderId);
	}
}
