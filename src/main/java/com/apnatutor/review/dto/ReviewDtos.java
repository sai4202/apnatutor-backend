package com.apnatutor.review.dto;

import java.time.Instant;

import com.apnatutor.review.ReviewService.OwnedReview;
import com.apnatutor.review.ReviewService.PublishedReview;
import com.apnatutor.review.domain.ModerationStatus;
import com.apnatutor.review.domain.Review;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request and response shapes for reviews.
 *
 * <p>Four response types, deliberately. The pattern is the one used for verification and tutor
 * profiles: each audience gets a record carrying only the fields it is allowed to see, so a field
 * cannot leak to the wrong reader through a conditional that a later edit gets wrong. {@link
 * PublicView} has no {@code studentId} and no {@code status} on it at all — an unapproved review
 * cannot be represented in it.
 *
 * <p>Note what is absent everywhere: {@code avgRating} and {@code reviewCount}. They are derived
 * from this table by the database and are never accepted from a client ({@code M5-04.2}). Leaving
 * them off every request record is what makes that structural rather than a check.
 */
public final class ReviewDtos {

	private ReviewDtos() {
	}

	@Schema(description = "A student's review of a tutor they were connected to")
	public record SubmitRequest(
			@Schema(example = "5", description = "1 to 5. The only required field.")
			@Min(value = 1, message = "Give a rating from 1 to 5")
			@Max(value = 5, message = "Give a rating from 1 to 5")
			int rating,

			@Schema(example = "Patient and very well prepared")
			@Size(max = 160)
			String title,

			@Schema(example = "My daughter went from failing to a B in one term.")
			@Size(max = 2000)
			String body) {
	}

	@Schema(description = "A tutor's single public answer to a review")
	public record ReplyRequest(
			@NotBlank(message = "Write your reply")
			@Size(max = 2000)
			String text) {
	}

	@Schema(description = "A moderator's decision to refuse publication")
	public record RejectRequest(
			@Schema(
					example = "This names another student. Please reword it without naming anyone.",
					description = "Shown to the student who wrote the review. Mandatory.")
			@NotBlank(message = "Tell the student why, so they can rewrite it")
			@Size(max = 500)
			String reason) {
	}

	/**
	 * What a visitor sees on a tutor's public profile.
	 *
	 * <p>No student id, no moderation status, no rejection reason. The reply is present only once
	 * it too has been approved, so a pending answer is invisible rather than shown as "awaiting
	 * review" — a visitor has no use for the queue state of someone else's text.
	 */
	@Schema(description = "A published review")
	public record PublicView(
			Long id,
			int rating,
			String title,
			String body,
			@Schema(example = "Priya S.", description = "Masked — never the full name")
			String reviewerName,
			Instant createdAt,
			@Schema(description = "Present only when the tutor has replied and it was approved")
			String tutorReply,
			Instant tutorRepliedAt) {

		public static PublicView from(PublishedReview published) {
			Review review = published.review();
			boolean replyVisible = review.hasPublishedReply();
			return new PublicView(
					review.getId(),
					review.getRating(),
					review.getTitle(),
					review.getBody(),
					published.reviewerName(),
					review.getCreatedAt(),
					replyVisible ? review.getTutorReply() : null,
					replyVisible ? review.getTutorReplyAt() : null);
		}
	}

	/** What the student who wrote it sees, including why it was refused. */
	@Schema(description = "A review, as its author sees it")
	public record OwnerView(
			Long id,
			@Schema(description = "tutor_profiles.id — the same id every tutor link uses")
			Long tutorProfileId,
			String tutorName,
			int rating,
			String title,
			String body,
			ModerationStatus status,
			@Schema(description = "Present only when rejected — what to change")
			String rejectionReason,
			Instant createdAt,
			String tutorReply,
			@Schema(description = "Whether the tutor's reply is published yet")
			ModerationStatus tutorReplyStatus) {

		public static OwnerView from(OwnedReview owned) {
			Review review = owned.review();
			return new OwnerView(
					review.getId(),
					owned.tutorProfileId(),
					owned.tutorName(),
					review.getRating(),
					review.getTitle(),
					review.getBody(),
					review.getStatus(),
					review.getRejectionReason(),
					review.getCreatedAt(),
					review.getTutorReply(),
					review.getTutorReplyStatus());
		}
	}

	/**
	 * What the reviewed tutor sees.
	 *
	 * <p>Includes reviews still pending, because they are about them and they will see them the
	 * moment they publish. No reviewer name: a tutor who can put a name to a poor rating has a
	 * student to argue with, and the student wrote it expecting the platform to stand between them.
	 */
	@Schema(description = "A review of me, as the tutor sees it")
	public record TutorView(
			Long id,
			int rating,
			String title,
			String body,
			ModerationStatus status,
			Instant createdAt,
			String tutorReply,
			ModerationStatus tutorReplyStatus,
			@Schema(description = "False once a reply exists — there is exactly one per review")
			boolean canReply) {

		public static TutorView from(Review review) {
			return new TutorView(
					review.getId(),
					review.getRating(),
					review.getTitle(),
					review.getBody(),
					review.getStatus(),
					review.getCreatedAt(),
					review.getTutorReply(),
					review.getTutorReplyStatus(),
					review.isApproved() && review.getTutorReply() == null);
		}
	}

	/** The moderation queue view. Everything, including who decided what. */
	@Schema(description = "A review, as a moderator sees it")
	public record AdminView(
			Long id,
			Long tutorId,
			Long studentId,
			int rating,
			String title,
			String body,
			ModerationStatus status,
			String rejectionReason,
			Long moderatedBy,
			Instant moderatedAt,
			String tutorReply,
			ModerationStatus tutorReplyStatus,
			Instant tutorReplyAt,
			Long tutorReplyModeratedBy,
			Instant createdAt) {

		public static AdminView from(Review review) {
			return new AdminView(
					review.getId(),
					review.getTutorId(),
					review.getStudentId(),
					review.getRating(),
					review.getTitle(),
					review.getBody(),
					review.getStatus(),
					review.getRejectionReason(),
					review.getModeratedBy(),
					review.getModeratedAt(),
					review.getTutorReply(),
					review.getTutorReplyStatus(),
					review.getTutorReplyAt(),
					review.getTutorReplyModeratedBy(),
					review.getCreatedAt());
		}
	}
}
