package com.apnatutor.billing;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.apnatutor.billing.domain.CreditPackage;
import com.apnatutor.billing.domain.CreditTransaction;
import com.apnatutor.billing.domain.Payment;
import com.apnatutor.billing.dto.BillingDtos.CreateOrderRequest;
import com.apnatutor.billing.dto.BillingDtos.LedgerEntryView;
import com.apnatutor.billing.dto.BillingDtos.OrderView;
import com.apnatutor.billing.dto.BillingDtos.PackageView;
import com.apnatutor.billing.dto.BillingDtos.PaymentView;
import com.apnatutor.billing.dto.BillingDtos.ReceiptView;
import com.apnatutor.billing.dto.BillingDtos.VerifyCheckoutRequest;
import com.apnatutor.billing.dto.BillingDtos.WalletView;
import com.apnatutor.billing.gateway.PaymentGateway;
import com.apnatutor.common.config.AppProperties;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.user.TutorProfileRepository;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tutor's wallet: balance, history, and buying more credits.
 */
@RestController
@RequestMapping("/api/v1/tutor/wallet")
@PreAuthorize("hasRole('TUTOR')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Wallet", description = "Credit balance, purchases and history")
public class WalletController {

	private static final Logger log = LoggerFactory.getLogger(WalletController.class);

	/** What counts as "expiring soon" on the wallet screen. */
	private static final Duration EXPIRY_WARNING_WINDOW = Duration.ofDays(30);

	private final CreditLedger ledger;
	private final PurchaseService purchases;
	private final PaymentGateway gateway;
	private final AppProperties properties;
	private final UserRepository users;
	private final TutorProfileRepository tutorProfiles;
	private final Clock clock;

	public WalletController(
			CreditLedger ledger,
			PurchaseService purchases,
			PaymentGateway gateway,
			AppProperties properties,
			UserRepository users,
			TutorProfileRepository tutorProfiles,
			Clock clock) {
		this.ledger = ledger;
		this.purchases = purchases;
		this.gateway = gateway;
		this.properties = properties;
		this.users = users;
		this.tutorProfiles = tutorProfiles;
		this.clock = clock;
	}

	@GetMapping
	@Transactional(readOnly = true)
	@Operation(summary = "My balance, ledger and purchases")
	public ResponseEntity<WalletView> wallet(CurrentUser currentUser) {
		List<CreditTransaction> history = ledger.history(currentUser.userId());

		return ResponseEntity.ok(new WalletView(
				ledger.balanceOf(currentUser.userId()),
				expiringSoon(history),
				history.stream().map(WalletController::toEntry).toList(),
				purchases.historyFor(currentUser.userId()).stream()
						.map(PaymentView::from)
						.toList()));
	}

	@GetMapping("/packages")
	@Operation(summary = "Packages available to buy")
	public ResponseEntity<List<PackageView>> packages() {
		return ResponseEntity.ok(
				purchases.storefront().stream().map(PackageView::from).toList());
	}

	@PostMapping("/orders")
	@Operation(
			summary = "Start a purchase",
			description = """
					Creates an order with the payment provider and returns what the checkout \
					widget needs.

					The amount comes from the package server-side. A client cannot name its own \
					price.""")
	public ResponseEntity<OrderView> createOrder(
			CurrentUser currentUser, @Valid @RequestBody CreateOrderRequest request) {

		Payment payment = purchases.createOrder(currentUser.userId(), request.packageId());
		CreditPackage bought = purchases.storefront().stream()
				.filter(candidate -> candidate.getId().equals(request.packageId()))
				.findFirst()
				.orElse(null);

		return ResponseEntity.ok(new OrderView(
				payment.getId(),
				payment.getProviderOrderId(),
				gateway.name(),
				// The publishable key only. The secret signs the checkout result and never leaves
				// the server — handing it out would let anyone forge a successful payment.
				properties.razorpay().keyId(),
				payment.getAmountPaise(),
				payment.getCurrency(),
				payment.getCredits(),
				bought == null ? "Credits" : bought.getName()));
	}

	/**
	 * Reports what the checkout widget told the browser.
	 *
	 * <h2>This does not grant credits</h2>
	 *
	 * <p>It verifies the signature and returns the payment's current status so the page can stop
	 * spinning. The webhook is what credits the wallet, for two reasons: this request comes from the
	 * user's own browser and is therefore forgeable in principle, and the browser can simply close
	 * mid-payment — which happens constantly on mobile.
	 *
	 * <p>So the honest answer here is often "we have your money, the credits are landing", and the
	 * response says so rather than pretending.
	 */
	@PostMapping("/verify")
	@Operation(
			summary = "Report the checkout result",
			description = "Verifies the signature and returns the payment status. Does NOT grant "
					+ "credits — the signed webhook does that.")
	public ResponseEntity<PaymentView> verifyCheckout(
			CurrentUser currentUser, @Valid @RequestBody VerifyCheckoutRequest request) {

		// Ownership first, so a valid signature for someone else's order reveals nothing.
		Payment payment = purchases.ownedBy(request.razorpayOrderId(), currentUser.userId());

		boolean signatureValid = gateway.verifyCheckoutSignature(
				request.razorpayOrderId(),
				request.razorpayPaymentId(),
				request.razorpaySignature());

		if (!signatureValid) {
			// Logged, not returned as an error. Nothing here grants credits, so a bad signature
			// gains an attacker nothing — but a run of them on real orders is worth seeing, and
			// the tutor cannot act on it either way. The real status is the useful answer.
			log.warn("Checkout signature did not verify: payment={} tutor={}",
					payment.getId(), currentUser.userId());
		}

		return ResponseEntity.ok(PaymentView.from(payment));
	}

	@GetMapping("/payments")
	@Operation(summary = "My purchases")
	public ResponseEntity<List<PaymentView>> payments(CurrentUser currentUser) {
		return ResponseEntity.ok(
				purchases.historyFor(currentUser.userId()).stream()
						.map(PaymentView::from)
						.toList());
	}

	/**
	 * A receipt for one purchase.
	 *
	 * <p>Only for payments that were actually paid. A receipt for an order nobody completed is a
	 * document that says money changed hands when it did not, and a tutor holding one has been given
	 * something actively misleading.
	 */
	@GetMapping("/payments/{paymentId}/receipt")
	@Transactional(readOnly = true)
	@Operation(
			summary = "Receipt for a purchase",
			description = "Only available once the payment is confirmed. Returns the data; the "
					+ "browser renders and prints it.")
	public ResponseEntity<ReceiptView> receipt(
			CurrentUser currentUser, @PathVariable Long paymentId) {

		Payment payment = purchases.historyFor(currentUser.userId()).stream()
				.filter(candidate -> candidate.getId().equals(paymentId))
				.findFirst()
				// NOT_FOUND for someone else's payment too — a 403 would confirm it exists.
				.orElseThrow(() -> ApiException.notFound("Payment"));

		if (!payment.isPaid()) {
			throw new ApiException(ErrorCode.CONFLICT,
					"A receipt is only available once the payment is confirmed.");
		}

		String packageName = purchases.storefront().stream()
				.filter(candidate -> candidate.getId().equals(payment.getPackageId()))
				.map(CreditPackage::getName)
				.findFirst()
				// A retired package is gone from the storefront but the receipt must still name
				// what was bought, so fall back to something truthful rather than blank.
				.orElse(payment.getCredits() + " credits");

		User tutor = users.findById(currentUser.userId()).orElseThrow();

		return ResponseEntity.ok(new ReceiptView(
				payment.getId(),
				receiptNumber(payment),
				payment.getCreditedAt(),
				tutorProfiles.findByUserId(currentUser.userId())
						.map(profile -> profile.getDisplayName())
						.filter(name -> name != null && !name.isBlank())
						.orElse("Tutor"),
				tutor.getPhone(),
				packageName,
				payment.getCredits(),
				payment.getAmountPaise(),
				// Null until the GST question is settled (PENDING.md D6). The fields exist now
				// because retrofitting tax onto historical transactions is genuinely unpleasant.
				null,
				null,
				payment.getProviderPaymentId(),
				payment.getStatus().name()));
	}

	/**
	 * A stable, human-facing receipt number.
	 *
	 * <p>Derived from the payment id rather than a separate sequence, so the same payment always
	 * produces the same number however many times a receipt is opened. A receipt whose number
	 * changed between viewings would be worthless as a record.
	 */
	private static String receiptNumber(Payment payment) {
		return "AT-%06d".formatted(payment.getId());
	}

	/**
	 * Credits that lapse within the warning window.
	 *
	 * <p>Summed from the grants that have not yet been expired, not from the balance — the balance
	 * is one number and carries no expiry date. Spends are ignored: they are not attributed to a
	 * particular grant, so this is deliberately an upper bound, and warning about slightly too much
	 * is better than warning about too little.
	 */
	private int expiringSoon(List<CreditTransaction> history) {
		var cutoff = clock.instant().plus(EXPIRY_WARNING_WINDOW);
		var now = clock.instant();

		return history.stream()
				.filter(entry -> entry.getAmount() > 0)
				.filter(entry -> entry.getExpiresAt() != null)
				.filter(entry -> entry.getExpiresAt().isAfter(now))
				.filter(entry -> entry.getExpiresAt().isBefore(cutoff))
				.mapToInt(CreditTransaction::getAmount)
				.sum();
	}

	private static LedgerEntryView toEntry(CreditTransaction entry) {
		return new LedgerEntryView(
				entry.getId(),
				entry.getAmount(),
				entry.getReason().name(),
				entry.getBalanceAfter(),
				entry.getExpiresAt(),
				entry.getCreatedAt());
	}
}
