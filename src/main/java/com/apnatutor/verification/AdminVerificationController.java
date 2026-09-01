package com.apnatutor.verification;

import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.common.web.PageResponse;
import com.apnatutor.verification.dto.VerificationDtos.AdminView;
import com.apnatutor.verification.dto.VerificationDtos.RejectRequest;
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
 * The admin review queue.
 *
 * <p>Class-level {@code @PreAuthorize} rather than per-method: every endpoint here is admin-only,
 * and putting the rule on the class means a method added later is protected by default rather than
 * by whoever adds it remembering.
 */
@RestController
@RequestMapping("/api/v1/admin/verifications")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin — verification", description = "Reviewing submitted documents")
public class AdminVerificationController {

	/** Capped so a caller cannot ask for the whole table in one request. */
	private static final int MAX_PAGE_SIZE = 100;

	private final VerificationService service;

	public AdminVerificationController(VerificationService service) {
		this.service = service;
	}

	@GetMapping("/pending")
	@Operation(
			summary = "The review queue",
			description = "Oldest first, so nobody waits indefinitely behind newer submissions. "
					+ "Each entry carries a document key to fetch from /api/v1/admin/files/**.")
	public ResponseEntity<PageResponse<AdminView>> pending(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		return ResponseEntity.ok(PageResponse.from(
				service.pendingQueue(PageRequest.of(Math.max(page, 0), clamp(size))),
				AdminView::from));
	}

	@PostMapping("/{id}/approve")
	@Operation(
			summary = "Approve a request",
			description = "Grants the badge. Records who approved it and when — a badge granted "
					+ "carelessly is worse than no badge, because it converts our carelessness "
					+ "into a parent's misplaced confidence.")
	public ResponseEntity<AdminView> approve(CurrentUser currentUser, @PathVariable Long id) {
		return ResponseEntity.ok(AdminView.from(service.approve(id, currentUser.userId())));
	}

	@PostMapping("/{id}/reject")
	@Operation(
			summary = "Reject a request",
			description = "The reason is mandatory and is shown to the tutor. A tutor told only "
					+ "'rejected' cannot fix anything, and will either give up or resubmit the "
					+ "same document.")
	public ResponseEntity<AdminView> reject(
			CurrentUser currentUser,
			@PathVariable Long id,
			@Valid @RequestBody RejectRequest request) {
		return ResponseEntity.ok(
				AdminView.from(service.reject(id, currentUser.userId(), request.reason())));
	}

	private static int clamp(int size) {
		return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
	}
}
