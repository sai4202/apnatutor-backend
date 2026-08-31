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

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
