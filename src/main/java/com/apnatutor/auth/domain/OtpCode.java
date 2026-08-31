package com.apnatutor.auth.domain;

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
 * A one-time passcode issued to a phone number.
 *
 * <p>Only a BCrypt hash of the code is stored. A leaked database must not hand an attacker a set of
 * live login codes.
 *
 * <p>Keyed by phone rather than user: a code is sent before we know whether an account exists, and
 * the API must not reveal which (enumeration defence).
 */
@Entity
@Table(name = "otp_codes")
public class OtpCode {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 16)
	private String phone;

	@Column(name = "code_hash", nullable = false, length = 72)
	private String codeHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private OtpPurpose purpose;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "consumed_at")
	private Instant consumedAt;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	protected OtpCode() {
		// Required by JPA.
	}

	public OtpCode(String phone, String codeHash, OtpPurpose purpose, Instant expiresAt) {
		this.phone = phone;
		this.codeHash = codeHash;
		this.purpose = purpose;
		this.expiresAt = expiresAt;
		this.attempts = 0;
	}

	/**
	 * Whether this code can still be checked: not used, not expired, not exhausted.
	 *
	 * @param maxAttempts cap from configuration (SOURCE_OF_TRUTH.md section 3.4)
	 */
	public boolean isUsable(Instant now, int maxAttempts) {
		return consumedAt == null && now.isBefore(expiresAt) && attempts < maxAttempts;
	}

	/**
	 * Records a failed guess.
	 *
	 * <p>Counted whether or not the code was right, so an attacker cannot get unlimited tries by
	 * abandoning each attempt.
	 */
	public void recordFailedAttempt() {
		this.attempts++;
	}

	/**
	 * Marks the code used. One-time really means one time — a correct code must not be replayable,
	 * or an attacker who observes it once can reuse it until it expires.
	 */
	public void consume(Instant at) {
		this.consumedAt = at;
	}

	public Long getId() {
		return id;
	}

	public String getPhone() {
		return phone;
	}

	public String getCodeHash() {
		return codeHash;
	}

	public OtpPurpose getPurpose() {
		return purpose;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getConsumedAt() {
		return consumedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
