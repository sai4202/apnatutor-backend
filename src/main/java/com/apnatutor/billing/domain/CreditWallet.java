package com.apnatutor.billing.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A tutor's credit balance.
 *
 * <p><strong>This is a cache.</strong> The ledger is the authority (SOURCE_OF_TRUTH.md Invariant 1);
 * this exists so a balance check does not require summing a year of history on every page load. If
 * the two disagree, the ledger is right and this is rebuilt.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Two unlocks racing on the same wallet could each read a balance of 5, each write 0, and hand
 * the tutor two leads for the price of one. That is prevented by a <strong>pessimistic row lock</strong>
 * taken when the wallet is loaded for spending ({@code CreditLedger.spend}), not by a version
 * column here — the unlock already needs a lock to make the cap check and the debit atomic, and one
 * mechanism is easier to reason about than two.
 */
@Entity
@Table(name = "credit_wallets")
public class CreditWallet {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "tutor_id", nullable = false, unique = true)
	private Long tutorId;

	@Column(nullable = false)
	private int balance;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	protected CreditWallet() {
		// Required by JPA.
	}

	public static CreditWallet createFor(Long tutorId) {
		CreditWallet wallet = new CreditWallet();
		wallet.tutorId = tutorId;
		wallet.balance = 0;
		return wallet;
	}

	/**
	 * Applies a signed change.
	 *
	 * @throws IllegalStateException if it would take the balance negative — the database has the
	 *     same constraint, but failing here gives a usable message rather than a constraint
	 *     violation
	 */
	public void apply(int delta) {
		int next = balance + delta;
		if (next < 0) {
			throw new IllegalStateException(
					"Insufficient credits: balance %d, needed %d".formatted(balance, -delta));
		}
		this.balance = next;
	}

	public boolean canAfford(int credits) {
		return balance >= credits;
	}

	public Long getId() {
		return id;
	}

	public Long getTutorId() {
		return tutorId;
	}

	public int getBalance() {
		return balance;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
