package com.apnatutor.billing.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One attempt to buy credits.
 *
 * <h2>Why credits and amount are copied, not looked up</h2>
 *
 * <p>Both are stored on the row rather than read back through {@code package_id}. A package repriced
 * next month must not rewrite what someone paid today — a receipt that changes retroactively is not
 * a receipt.
 *
 * <h2>What makes granting idempotent</h2>
 *
 * <p>{@link #creditedAt}. A webhook provider will deliver the same event more than once; that is
 * normal operation, not a fault. {@link #markPaid} refuses to move a payment that has already been
 * credited, so the second and third deliveries find their work done and change nothing.
 */
@Entity
@Table(name = "payments")
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "tutor_id", nullable = false)
	private Long tutorId;

	@Column(name = "package_id", nullable = false)
	private Long packageId;

	@Column(nullable = false)
	private int credits;

	@Column(name = "amount_paise", nullable = false)
	private long amountPaise;

	@Column(nullable = false, length = 3)
	private String currency = "INR";

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private PaymentStatus status = PaymentStatus.CREATED;

	@Column(nullable = false, length = 20)
	private String provider = "RAZORPAY";

	@Column(name = "provider_order_id", length = 80)
	private String providerOrderId;

	@Column(name = "provider_payment_id", length = 80)
	private String providerPaymentId;

	@Column(name = "credited_at")
	private Instant creditedAt;

	@Column(name = "credit_txn_id")
	private Long creditTxnId;

	@Column(name = "failure_reason", length = 300)
	private String failureReason;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	protected Payment() {
		// Required by JPA.
	}

	/** An order, before the tutor has paid anything. */
	public static Payment order(Long tutorId, CreditPackage bought, String providerOrderId) {
		Payment payment = new Payment();
		payment.tutorId = tutorId;
		payment.packageId = bought.getId();
		// Copied, deliberately. See the class javadoc.
		payment.credits = bought.getCredits();
		payment.amountPaise = bought.getPricePaise();
		payment.providerOrderId = providerOrderId;
		payment.status = PaymentStatus.CREATED;
		return payment;
	}

	/**
	 * Records that the provider confirmed the money.
	 *
	 * @return true if this call is the one that moved it, false if it was already paid — in which
	 *     case the caller must not grant credits
	 */
	public boolean markPaid(String providerPaymentId, Instant now) {
		if (status == PaymentStatus.PAID) {
			// A repeat delivery of the same event. Not an error: providers retry by design.
			return false;
		}
		this.status = PaymentStatus.PAID;
		this.providerPaymentId = providerPaymentId;
		this.creditedAt = now;
		return true;
	}

	public void recordCreditTransaction(Long creditTxnId) {
		this.creditTxnId = creditTxnId;
	}

	public void markFailed(String reason) {
		if (status == PaymentStatus.PAID) {
			// A failure notice arriving after a confirmed payment is out-of-order delivery, not a
			// reversal. Taking credits back here would punish a tutor who paid.
			return;
		}
		this.status = PaymentStatus.FAILED;
		this.failureReason = truncate(reason);
	}

	public void markCancelled(String reason) {
		if (status == PaymentStatus.PAID) {
			return;
		}
		this.status = PaymentStatus.CANCELLED;
		this.failureReason = truncate(reason);
	}

	private static String truncate(String reason) {
		if (reason == null) {
			return null;
		}
		return reason.length() <= 300 ? reason : reason.substring(0, 300);
	}

	public boolean isPaid() {
		return status == PaymentStatus.PAID;
	}

	public boolean isCredited() {
		return creditedAt != null;
	}

	public Long getId() {
		return id;
	}

	public Long getTutorId() {
		return tutorId;
	}

	public Long getPackageId() {
		return packageId;
	}

	public int getCredits() {
		return credits;
	}

	public long getAmountPaise() {
		return amountPaise;
	}

	public String getCurrency() {
		return currency;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public String getProvider() {
		return provider;
	}

	public String getProviderOrderId() {
		return providerOrderId;
	}

	public String getProviderPaymentId() {
		return providerPaymentId;
	}

	public Instant getCreditedAt() {
		return creditedAt;
	}

	public Long getCreditTxnId() {
		return creditTxnId;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
