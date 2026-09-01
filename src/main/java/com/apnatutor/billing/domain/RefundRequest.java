package com.apnatutor.billing.domain;

import java.time.Instant;

import com.apnatutor.lead.domain.LeadUnlock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A tutor's claim that a lead was worthless.
 *
 * <p>Kept whatever the outcome, approved or not. A rejected dispute is still evidence — about the
 * tutor if they raise many, about a student if several tutors raise the same complaint against one
 * requirement — and deleting it discards exactly the data that makes the next decision easier.
 */
@Entity
@Table(name = "refund_requests")
public class RefundRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "unlock_id", nullable = false)
	private Long unlockId;

	@Column(name = "tutor_id", nullable = false)
	private Long tutorId;

	@Column(name = "requirement_id", nullable = false)
	private Long requirementId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private RefundReason reason;

	@Column(length = 1000)
	private String details;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private RefundStatus status = RefundStatus.PENDING;

	/** Copied from the unlock. A repricing must never change what gets refunded. */
	@Column(nullable = false)
	private int credits;

	@Column(name = "reviewed_by")
	private Long reviewedBy;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "decision_note", length = 1000)
	private String decisionNote;

	@Column(name = "credit_txn_id")
	private Long creditTxnId;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	protected RefundRequest() {
		// Required by JPA.
	}

	public static RefundRequest raise(LeadUnlock unlock, RefundReason reason, String details) {
		RefundRequest request = new RefundRequest();
		request.unlockId = unlock.getId();
		request.tutorId = unlock.getTutorId();
		request.requirementId = unlock.getRequirementId();
		request.reason = reason;
		request.details = details;
		request.credits = unlock.getCreditsSpent();
		request.status = RefundStatus.PENDING;
		return request;
	}

	public void approve(Long adminUserId, String note, Instant now) {
		requirePending();
		this.status = RefundStatus.APPROVED;
		this.reviewedBy = adminUserId;
		this.reviewedAt = now;
		this.decisionNote = note;
	}

	public void reject(Long adminUserId, String note, Instant now) {
		requirePending();
		if (note == null || note.isBlank()) {
			// A rejection with no reason is the thing a tutor cannot argue with and cannot learn
			// from, and it is where the sense that disputes are pointless comes from.
			throw new IllegalArgumentException("A rejected dispute must say why");
		}
		this.status = RefundStatus.REJECTED;
		this.reviewedBy = adminUserId;
		this.reviewedAt = now;
		this.decisionNote = note;
	}

	public void recordCreditTransaction(Long creditTxnId) {
		this.creditTxnId = creditTxnId;
	}

	private void requirePending() {
		if (status != RefundStatus.PENDING) {
			throw new IllegalStateException("This dispute was already " + status.name().toLowerCase());
		}
	}

	public Long getId() {
		return id;
	}

	public Long getUnlockId() {
		return unlockId;
	}

	public Long getTutorId() {
		return tutorId;
	}

	public Long getRequirementId() {
		return requirementId;
	}

	public RefundReason getReason() {
		return reason;
	}

	public String getDetails() {
		return details;
	}

	public RefundStatus getStatus() {
		return status;
	}

	public int getCredits() {
		return credits;
	}

	public Long getReviewedBy() {
		return reviewedBy;
	}

	public Instant getReviewedAt() {
		return reviewedAt;
	}

	public String getDecisionNote() {
		return decisionNote;
	}

	public Long getCreditTxnId() {
		return creditTxnId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
