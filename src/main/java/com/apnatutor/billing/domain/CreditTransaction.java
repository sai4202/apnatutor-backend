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
 * One entry in the credit ledger.
 *
 * <p><strong>Append-only</strong> (SOURCE_OF_TRUTH.md Invariant 1). There are deliberately no
 * setters and no mutating methods: an entry is constructed, saved, and never touched again. The
 * database enforces the same rule with a trigger, because a Java-side convention only binds code
 * that goes through this class.
 *
 * <p>To correct a mistake, post a compensating entry with {@link CreditReason#ADMIN_ADJUSTMENT}.
 * Both the error and its correction stay visible, which is the entire point of keeping a ledger
 * rather than a balance.
 */
@Entity
@Table(name = "credit_transactions")
public class CreditTransaction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "tutor_id", nullable = false)
	private Long tutorId;

	/** Signed: positive granted, negative spent. */
	@Column(nullable = false)
	private int amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private CreditReason reason;

	@Column(name = "reference_type", length = 32)
	private String referenceType;

	@Column(name = "reference_id")
	private Long referenceId;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "balance_after", nullable = false)
	private int balanceAfter;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	protected CreditTransaction() {
		// Required by JPA.
	}

	public static CreditTransaction of(
			Long tutorId,
			int amount,
			CreditReason reason,
			String referenceType,
			Long referenceId,
			Instant expiresAt,
			int balanceAfter) {

		CreditTransaction entry = new CreditTransaction();
		entry.tutorId = tutorId;
		entry.amount = amount;
		entry.reason = reason;
		entry.referenceType = referenceType;
		entry.referenceId = referenceId;
		entry.expiresAt = expiresAt;
		entry.balanceAfter = balanceAfter;
		return entry;
	}

	public Long getId() {
		return id;
	}

	public Long getTutorId() {
		return tutorId;
	}

	public int getAmount() {
		return amount;
	}

	public CreditReason getReason() {
		return reason;
	}

	public String getReferenceType() {
		return referenceType;
	}

	public Long getReferenceId() {
		return referenceId;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public int getBalanceAfter() {
		return balanceAfter;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
