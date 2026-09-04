package com.apnatutor.user.domain;

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
 * A user account. Phone is the login identity (ADR #9).
 *
 * <p>Entities never leave the service layer — controllers speak DTOs. Keeping that boundary is what
 * stops a contact phone number leaking into a public JSON response by accident, which is the
 * highest-consequence bug class in this product.
 */
@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** E.164, canonical (+919876543210). Normalised on the way in so one number is one account. */
	@Column(nullable = false, unique = true, length = 16)
	private String phone;

	@Column(length = 255)
	private String email;

	/** Null for OTP-only accounts, which is the normal case. */
	@Column(name = "password_hash", length = 72)
	private String passwordHash;

	// STRING, never ORDINAL: an ordinal silently remaps every existing row if the enum is reordered.
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private UserRole role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private UserStatus status = UserStatus.ACTIVE;

	@Column(name = "phone_verified_at")
	private Instant phoneVerifiedAt;

	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

	@Column(name = "last_active_at")
	private Instant lastActiveAt;

	// --- Suspension (M5-05.3) ------------------------------------------------------------------
	//
	// Current state, not history. The audit log (M5-08) records who decided what and when; these
	// three answer "is this account blocked right now, and what do we tell them" on the login path,
	// which cannot afford a scan of an append-only table.

	@Column(name = "suspended_at")
	private Instant suspendedAt;

	/** Shown to the user at login. Mandatory whenever the status is SUSPENDED — see V17. */
	@Column(name = "suspension_reason", columnDefinition = "text")
	private String suspensionReason;

	@Column(name = "suspended_by")
	private Long suspendedBy;

	/**
	 * When personal data was erased (M5-10).
	 *
	 * <p>The row itself is never removed: the credit ledger and the audit log both reference it
	 * and both are append-only, so deleting it would either cascade rows out of an immutable
	 * ledger or leave dangling references in one.
	 */
	@Column(name = "deleted_at")
	private Instant deletedAt;

	// Written by the database (DEFAULT now() and the set_updated_at trigger), so they are read-only
	// here. Letting JPA write them would mean two sources of truth for the same fact.
	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	protected User() {
		// Required by JPA.
	}

	private User(String phone, UserRole role) {
		this.phone = phone;
		this.role = role;
		this.status = UserStatus.ACTIVE;
	}

	/**
	 * Creates an account for a phone number that has just proven itself via OTP.
	 *
	 * <p>The phone is verified at creation because the only path here is a successful OTP check —
	 * there is no way to register an unverified number.
	 */
	public static User registerVerified(String phone, UserRole role, Instant verifiedAt) {
		User user = new User(phone, role);
		user.phoneVerifiedAt = verifiedAt;
		return user;
	}

	/** Only active accounts may authenticate. */
	public boolean canAuthenticate() {
		return status == UserStatus.ACTIVE;
	}

	public void markActive(Instant at) {
		this.lastActiveAt = at;
	}

	/**
	 * Blocks the account.
	 *
	 * <p>The reason is required rather than optional, and it is required <em>here</em> rather than
	 * only at the controller: it is shown to the suspended user, and it is the only thing that makes
	 * the decision reviewable by whoever picks up the appeal. A blank reason produces an account
	 * nobody can explain and nobody dares reinstate.
	 *
	 * @throws IllegalStateException if the account is already suspended, or has been deleted
	 * @throws IllegalArgumentException if the reason is blank
	 */
	public void suspend(Long adminUserId, String reason, Instant at) {
		if (status == UserStatus.DELETED) {
			throw new IllegalStateException("A deleted account cannot be suspended");
		}
		if (status == UserStatus.SUSPENDED) {
			throw new IllegalStateException("This account is already suspended");
		}
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("A suspension needs a reason");
		}

		this.status = UserStatus.SUSPENDED;
		this.suspendedAt = at;
		this.suspensionReason = reason.strip();
		this.suspendedBy = adminUserId;
	}

	/**
	 * Lifts a suspension.
	 *
	 * <p>Clears the reason as well as the status. Leaving a stale reason on an active account is how
	 * a future screen ends up showing "suspended for fraud" next to a user in good standing.
	 *
	 * @throws IllegalStateException if the account is not currently suspended
	 */
	public void reinstate() {
		if (status != UserStatus.SUSPENDED) {
			throw new IllegalStateException("This account is not suspended");
		}

		this.status = UserStatus.ACTIVE;
		this.suspendedAt = null;
		this.suspensionReason = null;
		this.suspendedBy = null;
	}

	public Long getId() {
		return id;
	}

	public String getPhone() {
		return phone;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public UserRole getRole() {
		return role;
	}

	public UserStatus getStatus() {
		return status;
	}

	public Instant getPhoneVerifiedAt() {
		return phoneVerifiedAt;
	}

	public Instant getEmailVerifiedAt() {
		return emailVerifiedAt;
	}

	public Instant getLastActiveAt() {
		return lastActiveAt;
	}

	/**
	 * Erases the personal data on this account, keeping the row.
	 *
	 * <p>The phone is replaced rather than cleared, because the column is NOT NULL, uniquely
	 * indexed and CHECKed against E.164 — three constraints that between them rule out null, a
	 * constant and free text. {@code +99} is not an assigned country code, so a value built from
	 * it can never collide with a real number, and appending the id keeps it unique by
	 * construction.
	 *
	 * @throws IllegalStateException if the account has already been erased
	 */
	public void anonymise(Instant at) {
		if (status == UserStatus.DELETED) {
			throw new IllegalStateException("This account has already been deleted");
		}

		this.phone = "+99" + String.format("%010d", id);
		this.email = null;
		this.passwordHash = null;
		this.status = UserStatus.DELETED;
		this.deletedAt = at;
		this.phoneVerifiedAt = null;
		this.emailVerifiedAt = null;
		// A suspension reason is a note about a person who no longer has an account here.
		this.suspensionReason = null;
		this.suspendedAt = null;
		this.suspendedBy = null;
	}

	public boolean isDeleted() {
		return status == UserStatus.DELETED;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public Instant getSuspendedAt() {
		return suspendedAt;
	}

	public String getSuspensionReason() {
		return suspensionReason;
	}

	public Long getSuspendedBy() {
		return suspendedBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
