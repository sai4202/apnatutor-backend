package com.apnatutor.billing.dto;

import java.time.Instant;
import java.util.List;

import com.apnatutor.billing.domain.CreditPackage;
import com.apnatutor.billing.domain.Payment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response shapes for buying credits. */
public final class BillingDtos {

	private BillingDtos() {
	}

	// --- Requests -----------------------------------------------------------------------------

	@Schema(description = "Which package to buy. The price is read server-side from the package.")
	public record CreateOrderRequest(@NotNull Long packageId) {
	}

	/**
	 * What Razorpay's checkout widget hands back to the browser.
	 *
	 * <p>Used only to update the UI promptly. Credits are granted by the webhook, never by this —
	 * see {@code PaymentController.verifyCheckout}.
	 */
	@Schema(description = "The checkout result, as reported by the browser. Never trusted for crediting.")
	public record VerifyCheckoutRequest(
			@NotBlank @Size(max = 80) String razorpayOrderId,
			@NotBlank @Size(max = 80) String razorpayPaymentId,
			@NotBlank @Size(max = 200) String razorpaySignature) {
	}

	@Schema(description = "Create or reprice a package")
	public record PackageRequest(
			@NotBlank @Size(max = 60) String name,
			@NotNull @Min(1) @Max(100_000) Integer credits,
			@NotNull @Min(1) Long pricePaise,
			// Boxed: Jackson 3 rejects null for a primitive, so an omitted flag would be a parse
			// error rather than the false it obviously means (backend/CLAUDE.md).
			Boolean highlighted,
			Integer sortOrder) {
	}

	// --- Responses ----------------------------------------------------------------------------

	@Schema(description = "A credit package on the storefront")
	public record PackageView(
			Long id,
			String name,
			int credits,
			long pricePaise,
			@Schema(description = "Paise per credit, for the discount line")
			long pricePerCreditPaise,
			boolean highlighted,
			boolean active) {

		public static PackageView from(CreditPackage source) {
			return new PackageView(
					source.getId(),
					source.getName(),
					source.getCredits(),
					source.getPricePaise(),
					source.pricePerCreditPaise(),
					source.isHighlighted(),
					source.isActive());
		}
	}

	/**
	 * An order the browser can hand to the checkout widget.
	 *
	 * <p>Carries the <em>publishable</em> key id only. The secret never leaves the server; it is
	 * what signs the checkout result and would let anyone forge one.
	 */
	@Schema(description = "An order, ready for the checkout widget")
	public record OrderView(
			Long paymentId,
			String providerOrderId,
			String provider,
			@Schema(description = "The publishable key. Never the secret.")
			String keyId,
			long amountPaise,
			String currency,
			int credits,
			String packageName) {
	}

	@Schema(description = "One purchase")
	public record PaymentView(
			Long id,
			String providerOrderId,
			int credits,
			long amountPaise,
			String status,
			Instant createdAt,
			Instant creditedAt) {

		public static PaymentView from(Payment source) {
			return new PaymentView(
					source.getId(),
					source.getProviderOrderId(),
					source.getCredits(),
					source.getAmountPaise(),
					source.getStatus().name(),
					source.getCreatedAt(),
					source.getCreditedAt());
		}
	}

	@Schema(description = "Balance, history and what is about to expire")
	public record WalletView(
			int balance,
			@Schema(description = "Credits lapsing within the next 30 days")
			int expiringSoon,
			List<LedgerEntryView> history,
			List<PaymentView> payments) {
	}

	@Schema(description = "One movement in the credit ledger")
	public record LedgerEntryView(
			Long id,
			@Schema(description = "Signed: positive granted, negative spent")
			int amount,
			String reason,
			int balanceAfter,
			Instant expiresAt,
			Instant at) {
	}
}
