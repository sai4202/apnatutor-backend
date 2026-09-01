package com.apnatutor.review;

import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.common.web.PageResponse;
import com.apnatutor.review.dto.ReviewDtos.AdminView;
import com.apnatutor.review.dto.ReviewDtos.RejectRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review moderation — {@code M5-02}, {@code M5-03.2}, {@code M5-04.3}.
 *
 * <p>Class-level {@code @PreAuthorize} rather than per-method, following
 * {@link com.apnatutor.verification.AdminVerificationController}: every endpoint here is admin-only
 * and a method added later should be protected by default rather than by whoever adds it
 * remembering.
 *
 * <p>Two queues, not one. A review waiting for a decision and a reply waiting for a decision are
 * different pieces of work with different urgency — a pending review is a student who thinks they
 * were ignored, a pending reply is a tutor who cannot answer criticism already published about
 * them. Merging them buries whichever is rarer.
 */
@RestController
@RequestMapping("/api/v1/admin/reviews")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin — reviews", description = "Moderating reviews and tutor replies")
public class AdminReviewController {

	/** Capped so a caller cannot ask for the whole table in one request. */
	private static final int MAX_PAGE_SIZE = 100;

	private final ReviewService service;

	public AdminReviewController(ReviewService service) {
		this.service = service;
	}

	@GetMapping("/pending")
	@Operation(
			summary = "Reviews awaiting moderation",
			description = "Oldest first. This queue is meant to stay short: an unmoderated review "
					+ "is a student who believes they were ignored.")
	public ResponseEntity<PageResponse<AdminView>> pending(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		return ResponseEntity.ok(PageResponse.from(
				service.pendingQueue(PageRequest.of(Math.max(page, 0), clamp(size))),
				AdminView::from));
	}

	@GetMapping("/replies/pending")
	@Operation(summary = "Tutor replies awaiting moderation", description = "Oldest first.")
	public ResponseEntity<PageResponse<AdminView>> pendingReplies(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		return ResponseEntity.ok(PageResponse.from(
				service.pendingReplyQueue(PageRequest.of(Math.max(page, 0), clamp(size))),
				AdminView::from));
	}

	@PostMapping("/{id}/approve")
	@Operation(
			summary = "Publish a review",
			description = "The tutor is notified, and their rating is recomputed from the table "
					+ "immediately — search sorts on it, so an approval that did not update it "
					+ "would leave the ranking wrong until something else touched the row.")
	public ResponseEntity<AdminView> approve(CurrentUser currentUser, @PathVariable Long id) {
		return ResponseEntity.ok(AdminView.from(service.approve(id, currentUser.userId())));
	}

	@PostMapping("/{id}/reject")
	@Operation(
			summary = "Refuse to publish a review",
			description = "The reason is mandatory and is sent to the student. Silence is "
					+ "indistinguishable from a bug, and reads as quietly binning criticism.")
	public ResponseEntity<AdminView> reject(
			CurrentUser currentUser,
			@PathVariable Long id,
			@Valid @RequestBody RejectRequest request) {

		return ResponseEntity.ok(AdminView.from(
				service.reject(id, currentUser.userId(), request.reason())));
	}

	@PostMapping("/{id}/unpublish")
	@Operation(
			summary = "Take a published review back down",
			description = "For one reported after publication. Returns it to the queue and "
					+ "recomputes the rating, so the average moves back.")
	public ResponseEntity<AdminView> unpublish(CurrentUser currentUser, @PathVariable Long id) {
		return ResponseEntity.ok(AdminView.from(service.unpublish(id, currentUser.userId())));
	}

	@PostMapping("/{id}/reply/approve")
	@Operation(summary = "Publish a tutor's reply")
	public ResponseEntity<AdminView> approveReply(CurrentUser currentUser, @PathVariable Long id) {
		return ResponseEntity.ok(AdminView.from(service.approveReply(id, currentUser.userId())));
	}

	@PostMapping("/{id}/reply/reject")
	@Operation(
			summary = "Refuse a tutor's reply",
			description = "Leaves the review itself published. The two are decided independently.")
	public ResponseEntity<AdminView> rejectReply(CurrentUser currentUser, @PathVariable Long id) {
		return ResponseEntity.ok(AdminView.from(service.rejectReply(id, currentUser.userId())));
	}

	@PostMapping("/recompute-ratings")
	@Operation(
			summary = "Rebuild every tutor's rating from the reviews table",
			description = "Backfill and drift repair (M5-04.3). The counterpart to the credit "
					+ "ledger's reconcile: if a cached column can disagree with the rows it "
					+ "summarises, there has to be a way to put it right. Returns the row count.")
	public ResponseEntity<Integer> recomputeRatings() {
		return ResponseEntity.ok(service.recomputeAllRatings());
	}

	private static int clamp(int size) {
		return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
	}
}
