package com.apnatutor.review.domain;

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
 * One student's review of one tutor, and the tutor's single reply to it.
 *
 * <p>{@code tutorId} and {@code studentId} are <strong>user ids</strong>, matching
 * {@code lead_unlocks}, {@code credit_wallets} and {@code refund_requests}. Eligibility to write a
 * review is a question about {@code lead_unlocks} (Invariant 2), so keying by account keeps that
 * check to a single join.
 *
 * <p>The review and the reply carry independent {@link ModerationStatus} values. An approved review
 * may hold a pending reply, and a rejected reply leaves the review published — they are written by
 * different people days apart, and a tutor's answer being refused is no reason to unpublish the
 * student's words.
 */
@Entity
@Table(name = "reviews")
public class Review {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "tutor_id", nullable = false)
	private Long tutorId;

	@Column(name = "student_id", nullable = false)
	private Long studentId;

	@Column(nullable = false)
	private short rating;

	@Column(length = 160)
	private String title;

	@Column(length = 2000)
	private String body;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private ModerationStatus status = ModerationStatus.PENDING;

	@Column(name = "moderated_by")
	private Long moderatedBy;

	@Column(name = "moderated_at")
	private Instant moderatedAt;

	@Column(name = "rejection_reason", length = 500)
	private String rejectionReason;

	@Column(name = "tutor_reply", length = 2000)
	private String tutorReply;

	@Enumerated(EnumType.STRING)
	@Column(name = "tutor_reply_status", length = 16)
	private ModerationStatus tutorReplyStatus;

	@Column(name = "tutor_reply_at")
	private Instant tutorReplyAt;

	@Column(name = "tutor_reply_moderated_by")
	private Long tutorReplyModeratedBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	protected Review() {
		// Required by JPA.
	}

	public static Review write(Long tutorId, Long studentId, int rating, String title, String body) {
		Review review = new Review();
		review.tutorId = tutorId;
		review.studentId = studentId;
		review.setRating(rating);
		review.title = trimToNull(title);
		review.body = trimToNull(body);
		review.status = ModerationStatus.PENDING;
		return review;
	}

	/**
	 * Edits a review that has not yet been decided.
	 *
	 * <p>Only while {@code PENDING}. Editing an approved review would put unmoderated text on a
	 * public page under a moderator's approval — the one hole that makes the whole queue
	 * decorative. Once published, the student's route is to ask an admin.
	 */
	public void reviseWhilePending(int rating, String title, String body) {
		if (status != ModerationStatus.PENDING) {
			throw new IllegalStateException(
					"This review has already been " + status.name().toLowerCase()
							+ " and can no longer be edited");
		}
		setRating(rating);
		this.title = trimToNull(title);
		this.body = trimToNull(body);
	}

	public void approve(Long adminUserId, Instant at) {
		requirePending();
		this.status = ModerationStatus.APPROVED;
		this.moderatedBy = adminUserId;
		this.moderatedAt = at;
		this.rejectionReason = null;
	}

	public void reject(Long adminUserId, String reason, Instant at) {
		requirePending();
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("A rejection must state a reason");
		}
		this.status = ModerationStatus.REJECTED;
		this.moderatedBy = adminUserId;
		this.moderatedAt = at;
		this.rejectionReason = reason;
	}

	/**
	 * Withdraws an approval, sending the review back to the queue.
	 *
	 * <p>Exists because reviews get reported after publication. The aggregate must be recomputed
	 * whenever this is called — which is exactly why aggregates are derived from the table rather
	 * than incremented on approval; see {@code RatingAggregator}.
	 */
	public void unpublish(Long adminUserId, Instant at) {
		if (status != ModerationStatus.APPROVED) {
			throw new IllegalStateException("Only a published review can be withdrawn");
		}
		this.status = ModerationStatus.PENDING;
		this.moderatedBy = adminUserId;
		this.moderatedAt = at;
	}

	// --- The reply ----------------------------------------------------------------------------

	/** Records the single reply. It waits for moderation exactly as the review did. */
	public void reply(String text, Instant at) {
		if (status != ModerationStatus.APPROVED) {
			throw new IllegalStateException(
					"This review is not published yet, so there is nothing to reply to");
		}
		if (tutorReply != null) {
			throw new IllegalStateException("You have already replied to this review");
		}
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("A reply cannot be empty");
		}
		this.tutorReply = text.trim();
		this.tutorReplyStatus = ModerationStatus.PENDING;
		this.tutorReplyAt = at;
	}

	public void approveReply(Long adminUserId) {
		requireReplyPending();
		this.tutorReplyStatus = ModerationStatus.APPROVED;
		this.tutorReplyModeratedBy = adminUserId;
	}

	public void rejectReply(Long adminUserId) {
		requireReplyPending();
		this.tutorReplyStatus = ModerationStatus.REJECTED;
		this.tutorReplyModeratedBy = adminUserId;
	}

	/** True only once the reply has been approved. */
	public boolean hasPublishedReply() {
		return tutorReplyStatus == ModerationStatus.APPROVED;
	}

	public boolean isApproved() {
		return status == ModerationStatus.APPROVED;
	}

	// --- Internals ----------------------------------------------------------------------------

	private void setRating(int rating) {
		if (rating < 1 || rating > 5) {
			throw new IllegalArgumentException("A rating must be between 1 and 5");
		}
		this.rating = (short) rating;
	}

	private void requirePending() {
		if (status != ModerationStatus.PENDING) {
			throw new IllegalStateException(
					"This review was already " + status.name().toLowerCase());
		}
	}

	private void requireReplyPending() {
		if (tutorReplyStatus != ModerationStatus.PENDING) {
			throw new IllegalStateException(tutorReplyStatus == null
					? "There is no reply on this review"
					: "This reply was already " + tutorReplyStatus.name().toLowerCase());
		}
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	// --- Accessors ----------------------------------------------------------------------------

	public Long getId() {
		return id;
	}

	public Long getTutorId() {
		return tutorId;
	}

	public Long getStudentId() {
		return studentId;
	}

	public short getRating() {
		return rating;
	}

	public String getTitle() {
		return title;
	}

	public String getBody() {
		return body;
	}

	public ModerationStatus getStatus() {
		return status;
	}

	public Long getModeratedBy() {
		return moderatedBy;
	}

	public Instant getModeratedAt() {
		return moderatedAt;
	}

	public String getRejectionReason() {
		return rejectionReason;
	}

	public String getTutorReply() {
		return tutorReply;
	}

	public ModerationStatus getTutorReplyStatus() {
		return tutorReplyStatus;
	}

	public Instant getTutorReplyAt() {
		return tutorReplyAt;
	}

	public Long getTutorReplyModeratedBy() {
		return tutorReplyModeratedBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
