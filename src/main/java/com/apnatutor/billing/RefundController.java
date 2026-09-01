package com.apnatutor.billing;

import java.time.Instant;
import java.util.List;

import com.apnatutor.billing.domain.RefundReason;
import com.apnatutor.billing.domain.RefundRequest;
import com.apnatutor.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tutor disputing a lead that was worth nothing.
 *
 * <p>Deliberately easy to reach and easy to file. A refund path that is hard to find is a refund
 * path that does not exist, and the tutor concludes the credits are simply gone — which is the
 * belief that stops the next purchase.
 */
@RestController
@RequestMapping("/api/v1/tutor/refunds")
@PreAuthorize("hasRole('TUTOR')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Refunds", description = "Disputing a bad lead")
public class RefundController {

	private final RefundService refunds;

	public RefundController(RefundService refunds) {
		this.refunds = refunds;
	}

	@Schema(description = "A dispute over a lead")
	public record RaiseRequest(
			@NotNull Long unlockId,
			@NotNull RefundReason reason,
			@Size(max = 1000) String details) {
	}

	@Schema(description = "A dispute and where it got to")
	public record RefundView(
			Long id,
			Long unlockId,
			String reason,
			String details,
			String status,
			int credits,
			String decisionNote,
			Instant createdAt,
			Instant reviewedAt) {

		static RefundView from(RefundRequest source) {
			return new RefundView(
					source.getId(),
					source.getUnlockId(),
					source.getReason().name(),
					source.getDetails(),
					source.getStatus().name(),
					source.getCredits(),
					source.getDecisionNote(),
					source.getCreatedAt(),
					source.getReviewedAt());
		}
	}

	@PostMapping
	@Operation(
			summary = "Dispute a lead",
			description = """
					Within the refund window, once per lead.

					Reviewed by a person — approval returns the credits and frees the enquiry's \
					response slot for another tutor.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Dispute recorded"),
			@ApiResponse(responseCode = "409",
					description = "REFUND_NOT_ALLOWED — outside the window, or already disputed"),
			@ApiResponse(responseCode = "404", description = "Not your unlock")
	})
	public ResponseEntity<RefundView> raise(
			CurrentUser currentUser, @Valid @RequestBody RaiseRequest request) {

		return ResponseEntity.ok(RefundView.from(refunds.raise(
				request.unlockId(),
				currentUser.userId(),
				request.reason(),
				request.details())));
	}

	@GetMapping
	@Operation(summary = "My disputes")
	public ResponseEntity<List<RefundView>> mine(CurrentUser currentUser) {
		return ResponseEntity.ok(
				refunds.forTutor(currentUser.userId()).stream()
						.map(RefundView::from)
						.toList());
	}

	@GetMapping("/{id}")
	@Operation(summary = "One of my disputes")
	public ResponseEntity<RefundView> one(CurrentUser currentUser, @PathVariable Long id) {
		return refunds.forTutor(currentUser.userId()).stream()
				.filter(request -> request.getId().equals(id))
				.findFirst()
				.map(request -> ResponseEntity.ok(RefundView.from(request)))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}
}
