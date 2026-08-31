package com.apnatutor.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A stored refresh token, held as a SHA-256 hash of the value given to the client.
 *
 * <p>Possession of the raw token <em>is</em> the credential, so a database leak must not be a
 * session leak. SHA-256 rather than BCrypt: the token is already 256 bits of random, so there is no
 * low-entropy secret to slow an attacker down, and refresh sits on a hot path.
 *
 * <p>{@code familyId} tracks a rotation lineage. Each refresh issues a new token in the same family
 * and revokes the old one. If an already-rotated token is presented again, either it was stolen or
 * it was replayed — either way the family is compromised and every token in it is revoked.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	protected RefreshToken() {
		// Required by JPA.
	}

	public RefreshToken(Long userId, String tokenHash, UUID familyId, Instant expiresAt) {
		this.userId = userId;
		this.tokenHash = tokenHash;
		this.familyId = familyId;
		this.expiresAt = expiresAt;
	}

	/** Starts a fresh lineage — a new login, as opposed to a rotation of an existing session. */
	public static RefreshToken startFamily(Long userId, String tokenHash, Instant expiresAt) {
		return new RefreshToken(userId, tokenHash, UUID.randomUUID(), expiresAt);
	}

	/** Continues this lineage with a new token value. */
	public RefreshToken rotate(String newTokenHash, Instant newExpiresAt) {
		return new RefreshToken(userId, newTokenHash, familyId, newExpiresAt);
	}

	public boolean isActive(Instant now) {
		return revokedAt == null && now.isBefore(expiresAt);
	}

	public void revoke(Instant at) {
		if (revokedAt == null) {
			this.revokedAt = at;
		}
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public UUID getFamilyId() {
		return familyId;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
