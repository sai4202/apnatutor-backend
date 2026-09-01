package com.apnatutor.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.apnatutor.billing.domain.CreditPackage;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.billing.domain.CreditTransaction;
import com.apnatutor.billing.domain.Payment;
import com.apnatutor.billing.domain.PaymentStatus;
import com.apnatutor.billing.gateway.PaymentGateway;
import com.apnatutor.billing.gateway.PaymentGatewayException;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.notification.NotificationService;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buying credits.
 *
 * <h2>The one rule everything here serves</h2>
 *
 * <p>Credits are granted exactly once, only for money a payment provider confirmed, and the grant is
 * provable afterwards. Every decision below follows from that.
 *
 * <h2>Why the browser is never believed</h2>
 *
 * <p>Razorpay's checkout widget calls back into the page on success, and it is tempting to grant
 * credits there — it is immediate and the user is watching. It is also a request the user's own
 * browser makes, which means anyone can make it. The amount, the package and the success flag are
 * all read server-side from our own {@code payments} row and the provider's signed webhook; nothing
 * the client sends is trusted beyond an order id used to look that row up.
 *
 * <p>The checkout signature is verified, but only to move the UI along. The webhook is the
 * authority, because the browser can close mid-payment and frequently does.
 */
@Service
public class PurchaseService {

	private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

	/** Fallback if the setting is missing. SOURCE_OF_TRUTH.md §3.4. */
	private static final int DEFAULT_PURCHASED_VALIDITY_DAYS = 365;

	private final CreditPackageRepository packages;
	private final PaymentRepository payments;
	private final CreditLedger ledger;
	private final PaymentGateway gateway;
	private final SettingsService settings;
	private final NotificationService notifications;
	private final Clock clock;

	public PurchaseService(
			CreditPackageRepository packages,
			PaymentRepository payments,
			CreditLedger ledger,
			PaymentGateway gateway,
			SettingsService settings,
			NotificationService notifications,
			Clock clock) {
		this.packages = packages;
		this.payments = payments;
		this.ledger = ledger;
		this.gateway = gateway;
		this.settings = settings;
		this.notifications = notifications;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<CreditPackage> storefront() {
		return packages.findByActiveTrueOrderBySortOrderAscIdAsc();
	}

	/**
	 * Creates an order with the provider and records it as {@code CREATED}.
	 *
	 * <p>The amount comes from the package row, never from the request. A client that could name its
	 * own price would be the entire vulnerability.
	 *
	 * @throws ApiException {@code VALIDATION_FAILED} for an unknown or retired package,
	 *     {@code PAYMENT_GATEWAY_ERROR} if the provider could not be reached
	 */
	@Transactional
	public Payment createOrder(Long tutorId, Long packageId) {
		CreditPackage bought = packages.findById(packageId)
				.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
						"Unknown package: " + packageId));

		if (!bought.isActive()) {
			// Retired packages stay resolvable so old payments still make sense, but they cannot be
			// bought — otherwise a stale price survives in a bookmarked checkout link forever.
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"That package is no longer available.");
		}

		PaymentGateway.GatewayOrder order;
		try {
			order = gateway.createOrder(
					bought.getPricePaise(), "INR", "pkg-" + packageId + "-tutor-" + tutorId);
		} catch (PaymentGatewayException e) {
			// Logged with the cause, returned without it. A provider error message can carry
			// account detail and must not reach a client (SOURCE_OF_TRUTH.md §6).
			log.error("Could not create a payment order for tutor {}: {}", tutorId, e.getMessage());
			throw new ApiException(ErrorCode.PAYMENT_GATEWAY_ERROR,
					"We could not start the payment. Please try again in a moment.");
		}

		Payment payment = payments.save(Payment.order(tutorId, bought, order.orderId()));

		log.info("Payment order created: id={} tutor={} package={} amount={} order={}",
				payment.getId(), tutorId, packageId, bought.getPricePaise(), order.orderId());

		return payment;
	}

	/**
	 * Confirms a payment and grants its credits, at most once.
	 *
	 * <h2>How once-only is guaranteed</h2>
	 *
	 * <p>Three layers, because this is the one place in the product where being wrong costs real
	 * money:
	 *
	 * <ol>
	 *   <li>The payment row is loaded {@code FOR UPDATE}. Two webhook deliveries arriving
	 *       milliseconds apart would otherwise both read {@code credited_at IS NULL} and both grant.
	 *   <li>{@link Payment#markPaid} returns false if the payment was already {@code PAID}, so the
	 *       caller knows not to grant.
	 *   <li>A unique index on {@code (provider, provider_payment_id)} rejects a second row for the
	 *       same provider payment, whatever the application believes.
	 * </ol>
	 *
	 * @return true if this call granted the credits, false if they had already been granted
	 */
	@Transactional
	public boolean confirmPayment(String providerOrderId, String providerPaymentId) {
		Payment payment = payments.findByProviderOrderIdForUpdate(providerOrderId)
				.orElseThrow(() -> ApiException.notFound("Payment"));

		if (!payment.markPaid(providerPaymentId, clock.instant())) {
			// A repeat delivery. Normal operation for any webhook provider, not a fault.
			log.info("Payment {} already credited; ignoring duplicate confirmation for {}",
					payment.getId(), providerPaymentId);
			return false;
		}

		Instant expiresAt = clock.instant().plus(settings.durationDays(
				SettingsService.PURCHASED_VALIDITY_DAYS, DEFAULT_PURCHASED_VALIDITY_DAYS));

		CreditTransaction entry = ledger.grant(
				payment.getTutorId(),
				payment.getCredits(),
				CreditReason.PURCHASE,
				"PAYMENT",
				payment.getId(),
				expiresAt);

		payment.recordCreditTransaction(entry.getId());
		payments.save(payment);

		notifications.notify(
				payment.getTutorId(),
				NotificationType.CREDITS_PURCHASED,
				"%d credits added".formatted(payment.getCredits()),
				"Your payment of Rs %d went through and %d credits are in your wallet."
						.formatted(payment.getAmountPaise() / 100, payment.getCredits()),
				"PAYMENT",
				payment.getId());

		log.info("Payment confirmed: id={} tutor={} credits={} amount={} providerPayment={}",
				payment.getId(), payment.getTutorId(), payment.getCredits(),
				payment.getAmountPaise(), providerPaymentId);

		return true;
	}

	/**
	 * Cancels orders nobody ever paid.
	 *
	 * <p>A tutor who closes the checkout window leaves a {@code CREATED} row behind forever, and
	 * nothing else goes looking for it. Left alone those accumulate until "pending payments" is a
	 * meaningless number and a genuinely stuck payment is invisible among them.
	 *
	 * <p>Cancelling costs nothing: if the provider later confirms one of these after all, the webhook
	 * still finds the row and {@link com.apnatutor.billing.domain.Payment#markPaid} moves it to
	 * {@code PAID} — {@code CANCELLED} is not a terminal state as far as crediting is concerned, and
	 * deliberately so. Money that arrives late is still money that arrived.
	 *
	 * @return how many were cancelled
	 */
	@Transactional
	public int cancelStaleOrders(java.time.Duration olderThan) {
		Instant cutoff = clock.instant().minus(olderThan);
		List<Payment> stale = payments.findByStatusAndCreatedAtBefore(
				PaymentStatus.CREATED, cutoff);

		for (Payment payment : stale) {
			payment.markCancelled("No confirmation received within " + olderThan.toHours()
					+ " hours");
			payments.save(payment);
		}

		if (!stale.isEmpty()) {
			log.info("Cancelled {} stale payment order(s) older than {}", stale.size(), cutoff);
		}

		return stale.size();
	}

	@Transactional
	public void markFailed(String providerOrderId, String reason) {
		payments.findByProviderOrderIdForUpdate(providerOrderId).ifPresent(payment -> {
			payment.markFailed(reason);
			payments.save(payment);
			log.info("Payment failed: id={} reason={}", payment.getId(), reason);
		});
	}

	@Transactional(readOnly = true)
	public List<Payment> historyFor(Long tutorId) {
		return payments.findByTutorIdOrderByCreatedAtDesc(tutorId);
	}

	/**
	 * A tutor's own payment, by order id.
	 *
	 * <p>Ownership is checked here rather than in the controller, so every path that reaches a
	 * payment by a client-supplied id goes through it. Returns NOT_FOUND rather than FORBIDDEN for
	 * someone else's payment: a 403 would confirm the order exists.
	 */
	@Transactional(readOnly = true)
	public Payment ownedBy(String providerOrderId, Long tutorId) {
		Payment payment = payments.findByProviderOrderId(providerOrderId)
				.orElseThrow(() -> ApiException.notFound("Payment"));

		if (!payment.getTutorId().equals(tutorId)) {
			throw ApiException.notFound("Payment");
		}

		return payment;
	}

	/** Exposed so the checkout page can be handed the key it needs to open the widget. */
	public String gatewayName() {
		return gateway.name();
	}

	PaymentStatus statusOf(String providerOrderId) {
		return payments.findByProviderOrderId(providerOrderId)
				.map(Payment::getStatus)
				.orElse(null);
	}
}
