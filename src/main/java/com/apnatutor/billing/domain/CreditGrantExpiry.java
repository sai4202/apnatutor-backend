package com.apnatutor.billing.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A record that the expiry job has dealt with one grant.
 *
 * <p>Bookkeeping <em>about</em> the ledger rather than part of it. A grant that was fully spent
 * before it lapsed needs to be marked as handled, but nothing moved — and a zero-amount ledger entry
 * is not a movement of money, which is why {@code credit_transactions_amount_nonzero} rejects one.
 * Keeping this separate lets the ledger hold only real movements while the job still terminates.
 *
 * <p>The grant's id is the primary key, so processing is idempotent by construction: a second run
 * cannot insert a second row.
 */
@Entity
@Table(name = "credit_grant_expiries")
public class CreditGrantExpiry {

	@Id
	@Column(name = "credit_txn_id")
	private Long creditTxnId;

	@Column(name = "tutor_id", nullable = false)
	private Long tutorId;

	/** Zero is meaningful: the tutor spent them before they lapsed. */
	@Column(name = "credits_expired", nullable = false)
	private int creditsExpired;

	@Column(name = "expiry_txn_id")
	private Long expiryTxnId;

	@Column(name = "processed_at", insertable = false, updatable = false)
	private Instant processedAt;

	protected CreditGrantExpiry() {
		// Required by JPA.
	}

	public static CreditGrantExpiry writtenOff(
			Long grantId, Long tutorId, int creditsExpired, Long expiryTxnId) {
		CreditGrantExpiry record = new CreditGrantExpiry();
		record.creditTxnId = grantId;
		record.tutorId = tutorId;
		record.creditsExpired = creditsExpired;
		record.expiryTxnId = expiryTxnId;
		return record;
	}

	/** Nothing to write off — the credits were used before they lapsed. */
	public static CreditGrantExpiry spentBeforeLapsing(Long grantId, Long tutorId) {
		return writtenOff(grantId, tutorId, 0, null);
	}

	public Long getCreditTxnId() {
		return creditTxnId;
	}

	public Long getTutorId() {
		return tutorId;
	}

	public int getCreditsExpired() {
		return creditsExpired;
	}

	public Long getExpiryTxnId() {
		return expiryTxnId;
	}

	public Instant getProcessedAt() {
		return processedAt;
	}
}
