package com.apnatutor.billing;

import java.time.Instant;

import com.apnatutor.billing.domain.RefundRequest;
import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * Deciding disputes.
 *
 * <p>The queue carries each tutor's dispute rate alongside the claim, because that is the context
 * that changes the decision: the same complaint from someone who has disputed one lead in fifty
 * reads very differently from one who has disputed half of them.
 */
@RestController
@RequestMapping("/api/v1/admin/refunds")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin — refunds", description = "Dispute queue and decisions")
public class AdminRefundController {

	private static final int MAX_PAGE_SIZE = 50;

	private final RefundService refunds;

	public AdminRefundController(RefundService refunds) {
		this.refunds = refunds;
	}

	@Schema(description = "A decision on a dispute")
	public record DecisionRequest(
			@Schema(description = "Required when rejecting — the tutor is shown this")
			@Size(max = 1000) String note) {
	}

	@Schema(description = "A dispute, with the context needed to decide it")
	public record QueueEntry(
			Long id,
			Long unlockId,
			Long tutorId,
			Long requirementId,
			String reason,
			String details,
			int credits,
			@Schema(description = "Share of this tutor's unlocks they have disputed, 0-1")
			double tutorDisputeRate,
			Instant createdAt) {
	}

	@Schema(description = "A decided dispute")
	public record DecisionView(Long id, String status, int credits, String decisionNote) {

		static DecisionView from(RefundRequest source) {
			return new DecisionView(
					source.getId(),
					source.getStatus().name(),
					source.getCredits(),
					source.getDecisionNote());
		}
	}

	@GetMapping
	@Operation(summary = "Disputes awaiting a decision, oldest first")
	public ResponseEntity<PageResponse<QueueEntry>> queue(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		var pageable = PageRequest.of(
				Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

		return ResponseEntity.ok(PageResponse.from(
				refunds.pendingQueue(pageable),
				request -> new QueueEntry(
						request.getId(),
						request.getUnlockId(),
						request.getTutorId(),
						request.getRequirementId(),
						request.getReason().name(),
						request.getDetails(),
						request.getCredits(),
						refunds.disputeRateFor(request.getTutorId()),
						request.getCreatedAt())));
	}

	@PostMapping("/{id}/approve")
	@Operation(
			summary = "Uphold a dispute",
			description = "Returns the credits and frees the enquiry's response slot for another "
					+ "tutor.")
	public ResponseEntity<DecisionView> approve(
			CurrentUser currentUser,
			@PathVariable Long id,
			@Valid @RequestBody(required = false) DecisionRequest request) {

		return ResponseEntity.ok(DecisionView.from(refunds.approve(
				id, currentUser.userId(), request == null ? null : request.note())));
	}

	@PostMapping("/{id}/reject")
	@Operation(
			summary = "Decline a dispute",
			description = "A note is required. A rejection with no reason is one the tutor can "
					+ "neither argue with nor learn from.")
	public ResponseEntity<DecisionView> reject(
			CurrentUser currentUser,
			@PathVariable Long id,
			@Valid @RequestBody RejectRequest request) {

		return ResponseEntity.ok(DecisionView.from(
				refunds.reject(id, currentUser.userId(), request.note())));
	}

	@Schema(description = "Declining a dispute. The note is shown to the tutor.")
	public record RejectRequest(@NotBlank @Size(max = 1000) String note) {
	}
}
