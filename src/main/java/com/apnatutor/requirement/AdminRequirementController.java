package com.apnatutor.requirement;

import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.common.web.PageResponse;
import com.apnatutor.requirement.RequirementModerationService.RemovalResult;
import com.apnatutor.requirement.RequirementViewMapper.Catalog;
import com.apnatutor.requirement.domain.RequirementStatus;
import com.apnatutor.requirement.dto.RequirementDtos.ModerationView;
import com.apnatutor.requirement.dto.RequirementDtos.RemovalOutcome;
import com.apnatutor.requirement.dto.RequirementDtos.RemoveRequest;
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
 * Requirement moderation — {@code M5-05.6}.
 *
 * <p>Class-level {@code @PreAuthorize}, following {@link com.apnatutor.review.AdminReviewController}:
 * a method added here later is protected by default rather than by whoever adds it remembering.
 *
 * <p>Everything this controller serves carries the student's phone number, which no other view of a
 * requirement does — see {@link ModerationView} for why that exception exists.
 */
@RestController
@RequestMapping("/api/v1/admin/requirements")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin — requirements", description = "Spam and fake-lead moderation")
public class AdminRequirementController {

	/** Capped so a caller cannot ask for the whole table in one request. */
	private static final int MAX_PAGE_SIZE = 100;

	private final RequirementModerationService service;
	private final RequirementViewMapper mapper;

	public AdminRequirementController(
			RequirementModerationService service, RequirementViewMapper mapper) {
		this.service = service;
		this.mapper = mapper;
	}

	@GetMapping("/flagged")
	@Operation(
			summary = "Enquiries several different tutors have disputed",
			description = "The queue that matters. One tutor disputing many leads may just be bad "
					+ "at phone calls; three different tutors disputing the same enquiry is a fact "
					+ "about that enquiry, and it is the only way a fake one comes to light.")
	public ResponseEntity<PageResponse<ModerationView>> flagged(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		Catalog catalog = mapper.loadCatalog();
		return ResponseEntity.ok(PageResponse.from(
				service.flaggedQueue(PageRequest.of(Math.max(page, 0), clamp(size))),
				requirement -> mapper.toModerationView(requirement, catalog)));
	}

	@GetMapping
	@Operation(
			summary = "Browse enquiries",
			description = "Newest first, optionally filtered by status. For looking something up "
					+ "after a complaint, rather than for working a queue.")
	public ResponseEntity<PageResponse<ModerationView>> browse(
			@RequestParam(required = false) RequirementStatus status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		Catalog catalog = mapper.loadCatalog();
		return ResponseEntity.ok(PageResponse.from(
				service.browse(status, PageRequest.of(Math.max(page, 0), clamp(size))),
				requirement -> mapper.toModerationView(requirement, catalog)));
	}

	@GetMapping("/removed")
	@Operation(
			summary = "What has been taken down, and why",
			description = "The record of moderation decisions, most recent first. Kept visible "
					+ "because a takedown refunds tutors and is worth being able to justify.")
	public ResponseEntity<PageResponse<ModerationView>> removed(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		Catalog catalog = mapper.loadCatalog();
		return ResponseEntity.ok(PageResponse.from(
				service.removed(PageRequest.of(Math.max(page, 0), clamp(size))),
				requirement -> mapper.toModerationView(requirement, catalog)));
	}

	@PostMapping("/{id}/remove")
	@Operation(
			summary = "Take an enquiry down and refund every tutor who paid for it",
			description = "Not a free action. Removing an enquiry is the platform accepting that it "
					+ "sold a lead it should not have, so every active unlock is refunded in the "
					+ "same transaction. The response says how many tutors that was. The reason is "
					+ "mandatory and is sent to the student.")
	public ResponseEntity<RemovalOutcome> remove(
			CurrentUser currentUser,
			@PathVariable Long id,
			@Valid @RequestBody RemoveRequest request) {

		RemovalResult result = service.remove(id, currentUser.userId(), request.reason());

		return ResponseEntity.ok(new RemovalOutcome(
				mapper.toModerationView(result.requirement(), mapper.loadCatalog()),
				result.tutorsRefunded()));
	}

	@PostMapping("/{id}/restore")
	@Operation(
			summary = "Put a wrongly-removed enquiry back",
			description = "The refunds are not reversed — clawing credits back out of a tutor's "
					+ "wallet to correct our mistake would make it theirs. The status is recomputed "
					+ "from expiry and the unlock count, so an enquiry that expired while it was "
					+ "down comes back expired.")
	public ResponseEntity<ModerationView> restore(CurrentUser currentUser, @PathVariable Long id) {
		return ResponseEntity.ok(mapper.toModerationView(
				service.restore(id, currentUser.userId()), mapper.loadCatalog()));
	}

	private static int clamp(int size) {
		return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
	}
}
