package com.apnatutor.verification.dto;

import java.time.Instant;

import com.apnatutor.verification.domain.Verification;
import com.apnatutor.verification.domain.VerificationStatus;
import com.apnatutor.verification.domain.VerificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request and response shapes for verification.
 *
 * <p>Two response types again, for the same reason as tutor profiles: {@link AdminView} carries the
 * document key because an admin has to open the document to review it, and {@link OwnerView} does
 * not. A conditional field is something a future edit can silently get wrong; an absent field
 * cannot leak.
 */
public final class VerificationDtos {

	private VerificationDtos() {
	}

	@Schema(description = "An admin's decision to refuse a request")
	public record RejectRequest(
			@Schema(
					example = "The document is too blurry to read. Please upload a clearer photo.",
					description = "Shown to the tutor. Mandatory — see below.")
			@NotBlank(message = "Tell the tutor why, so they can fix it")
			@Size(max = 500)
			String reason) {
	}

	/**
	 * What the tutor sees about their own request.
	 *
	 * <p>No {@code documentUrl}: they uploaded it, and re-serving identity documents to anyone who
	 * holds a session adds risk for no benefit.
	 */
	@Schema(description = "A verification request, as its owner sees it")
	public record OwnerView(
			Long id,
			VerificationType type,
			VerificationStatus status,
			@Schema(description = "Present only when rejected — what to fix")
			String rejectionReason,
			Instant submittedAt,
			Instant reviewedAt) {

		public static OwnerView from(Verification verification) {
			return new OwnerView(
					verification.getId(),
					verification.getType(),
					verification.getStatus(),
					verification.getRejectionReason(),
					verification.getCreatedAt(),
					verification.getReviewedAt());
		}
	}

	/** What an admin reviewing the queue sees, including the document to open. */
	@Schema(description = "A verification request, as an admin sees it")
	public record AdminView(
			Long id,
			Long userId,
			VerificationType type,
			VerificationStatus status,
			@Schema(description = "Fetch via /api/v1/admin/files/{key} — admin-only")
			String documentUrl,
			String rejectionReason,
			Long reviewedBy,
			Instant submittedAt,
			Instant reviewedAt) {

		public static AdminView from(Verification verification) {
			return new AdminView(
					verification.getId(),
					verification.getUserId(),
					verification.getType(),
					verification.getStatus(),
					verification.getDocumentUrl(),
					verification.getRejectionReason(),
					verification.getReviewedBy(),
					verification.getCreatedAt(),
					verification.getReviewedAt());
		}
	}
}
