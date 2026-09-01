package com.apnatutor.verification.domain;

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
 * One verification request.
 *
 * <p>{@code documentUrl} is a storage key for an Aadhaar scan, a PAN card or a degree certificate.
 * It is admin-only and must never be mapped into a public DTO — the public answer is the resulting
 * badge, which is the only part a parent needs.
 */
@Entity
@Table(name = "verifications")
public class Verification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private VerificationType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private VerificationStatus status = VerificationStatus.PENDING;

	@Column(name = "document_url", length = 500)
	private String documentUrl;

	@Column(name = "reviewed_by")
	private Long reviewedBy;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "rejection_reason", length = 500)
	private String rejectionReason;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	protected Verification() {
		// Required by JPA.
	}

	public static Verification submit(Long userId, VerificationType type, String documentUrl) {
		Verification verification = new Verification();
		verification.userId = userId;
		verification.type = type;
		verification.documentUrl = documentUrl;
		verification.status = VerificationStatus.PENDING;
		return verification;
	}

	/**
	 * Records an admin approval.
	 *
	 * <p>Only a pending request can be decided. Re-approving an already-decided one would overwrite
	 * who reviewed it and when, destroying the audit trail that makes a decision defensible.
	 */
	public void approve(Long adminUserId, Instant at) {
		requirePending();
		this.status = VerificationStatus.APPROVED;
		this.reviewedBy = adminUserId;
		this.reviewedAt = at;
		this.rejectionReason = null;
	}

	/** Records an admin rejection. The reason is mandatory — see the class comment on V7. */
	public void reject(Long adminUserId, String reason, Instant at) {
		requirePending();
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("A rejection must state a reason");
		}
		this.status = VerificationStatus.REJECTED;
		this.reviewedBy = adminUserId;
		this.reviewedAt = at;
		this.rejectionReason = reason;
	}

	private void requirePending() {
		if (status != VerificationStatus.PENDING) {
			throw new IllegalStateException(
					"This request was already " + status.name().toLowerCase());
		}
	}

	public boolean isApproved() {
		return status == VerificationStatus.APPROVED;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public VerificationType getType() {
		return type;
	}

	public VerificationStatus getStatus() {
		return status;
	}

	public String getDocumentUrl() {
		return documentUrl;
	}

	public Long getReviewedBy() {
		return reviewedBy;
	}

	public Instant getReviewedAt() {
		return reviewedAt;
	}

	public String getRejectionReason() {
		return rejectionReason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
